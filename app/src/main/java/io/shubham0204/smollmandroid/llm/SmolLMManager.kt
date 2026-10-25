/*
 * Copyright (C) 2025 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollmandroid.llm

import android.content.Context
import android.util.Log
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.data.AppDB
import io.shubham0204.smollmandroid.data.Chat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

private const val LOGTAG = "[SmolLMManager-Kt]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

@Single
class SmolLMManager(private val appDB: AppDB, private val context: Context) {
    private val instance = SmolLM()

    // Use ReentrantLock for thread-safe state management without suspending
    private val stateLock = ReentrantLock()

    // The native cache writer is not re-entrant, so serialize cache file I/O
    // independently from the rest of the model state.
    private val cacheLock = ReentrantLock()

    @Volatile
    private var responseGenerationJob: Job? = null

    @Volatile
    private var modelInitJob: Job? = null

    @Volatile
    private var resetChatJob: Job? = null

    @Volatile
    private var benchmarkJob: Job? = null

    @Volatile
    private var chat: Chat? = null

    @Volatile
    private var cachePath: String? = null

    // Use java.util.concurrent.atomic for better thread safety
    val isInstanceLoaded = AtomicBoolean(false)

    @Volatile
    var isInferenceOn = false
        private set

    data class SmolLMResponse(
        val response: String,
        val generationSpeed: Float,
        val generationTimeSecs: Int,
        val contextLengthUsed: Int,
        val usedJinjaTemplate: Boolean = true,
    )

    fun load(
        chat: Chat,
        modelPath: String,
        params: SmolLM.InferenceParams = SmolLM.InferenceParams(),
        onError: (Exception) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val newCachePath = cacheFileFor(chat, modelPath)
        stateLock.withLock {
            // Cancel any in-flight generation/load before replacing the native model
            val previousLoadJob = modelInitJob
            val previousResponseJob = responseGenerationJob
            val previousResetJob = resetChatJob
            val previousBenchmarkJob = benchmarkJob
            val previousChat = this.chat
            val previousCachePath = cachePath
            previousLoadJob?.cancel()
            previousResponseJob?.cancel()
            previousResetJob?.cancel()
            previousBenchmarkJob?.cancel()
            isInstanceLoaded.set(false)

            try {
                modelInitJob = CoroutineScope(Dispatchers.Default).launch {
                    try {
                        // Wait for the cancelled jobs so the native instance is
                        // no longer in use when it is closed below.
                        previousResponseJob?.join()
                        previousLoadJob?.join()
                        previousResetJob?.join()
                        previousBenchmarkJob?.join()
                        if (previousChat != null && previousCachePath != null) {
                            if (saveCacheForChat(previousCachePath, previousChat)) {
                                LOGD("Previous KV cache saved")
                            }
                        }
                        // Release the previous model. Without this the native
                        // pointer is overwritten on every chat/model switch and
                        // the old model's memory is leaked.
                        instance.close()
                        isInstanceLoaded.set(false)
                        // Do not leave the previous chat/cache path attached to a closed
                        // native instance. If the load below fails, a later load would try
                        // to save a cache through a null handle.
                        this@SmolLMManager.chat = null
                        this@SmolLMManager.cachePath = null
                        instance.load(modelPath, params)
                        this@SmolLMManager.chat = chat
                        this@SmolLMManager.cachePath = newCachePath?.absolutePath
                        LOGD("Model loaded")

                        if (chat.systemPrompt.isNotEmpty()) {
                            instance.addSystemPrompt(chat.systemPrompt)
                            LOGD("System prompt added")
                        }

                        appDB.getMessagesForModel(chat.id).forEach { message ->
                            if (message.isUserMessage) {
                                instance.addUserMessage(message.message)
                                LOGD("User message added: ${message.message}")
                            } else {
                                instance.addAssistantMessage(message.message)
                                LOGD("Assistant message added: ${message.message}")
                            }
                        }
                        val restored =
                            newCachePath?.let { restoreCacheFromDisk(it.absolutePath) } ?: false
                        LOGD(
                            if (restored) {
                                "KV cache restored"
                            } else {
                                "No compatible KV cache, starting fresh"
                            }
                        )

                        withContext(Dispatchers.Main) {
                            isInstanceLoaded.set(true)
                            onSuccess()
                        }
                    } catch (e: CancellationException) {
                        LOGD("Model loading cancelled")
                        throw e
                    } catch (e: Exception) {
                        LOGD("Error loading model: ${e.message}")
                        this@SmolLMManager.chat = null
                        this@SmolLMManager.cachePath = null
                        isInstanceLoaded.set(false)
                        try {
                            instance.close()
                        } catch (closeError: Exception) {
                            LOGD("Error closing failed model instance: ${closeError.message}")
                        }
                        withContext(Dispatchers.Main) {
                            onError(e)
                        }
                    }
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /**
     * Names the persisted KV cache for a chat. Only the identity of the model file and the context
     * size take part in the name: the restored cache is validated against the model and context in
     * native code, and every turn compares the prompt token by token, so a changed chat template or
     * system prompt reuses the prefix that both prompts share instead of throwing the cache away.
     */
    private fun cacheFileFor(
        chat: Chat,
        modelPath: String,
    ): File {
        val modelFile = File(modelPath)
        val cacheKey =
            buildString {
                append(chat.id)
                append('_').append(chat.llmModelId)
                append('_').append(chat.contextSize)
                append('_').append(modelFile.length())
                append('_').append(modelFile.lastModified())
            }
        val cacheDir = File(context.filesDir, "kv_cache").apply { mkdirs() }
        return File(cacheDir, "$cacheKey.seq")
    }

    /**
     * Saves a cache only when its native transcript covers exactly the messages persisted for
     * [chat]. A response can finish natively just as a chat is unloaded or cancelled, before its
     * assistant row reaches the database; saving that cache unchanged would leave it ahead of the
     * transcript and make the next load discard it.
     *
     * Use the loaded [chat] object rather than re-reading the row: chat settings are written to the
     * database before the old native instance is unloaded, so the row can already describe the next
     * system prompt while the native transcript still contains the previous one.
     */
    private fun saveCacheForChat(path: String, chat: Chat): Boolean =
        try {
            // The native transcript also contains the system prompt when it is
            // non-empty, so include it when matching the persisted message count.
            val messageCount =
                (if (chat.systemPrompt.isNotEmpty()) 1 else 0) +
                    appDB.getMessagesForModel(chat.id).size
            cacheLock.withLock {
                if (!instance.trimToMessageCount(messageCount)) {
                    LOGD("KV cache not saved: native transcript is out of sync with chat")
                    return@withLock false
                }
                instance.saveCache(path)
            }
        } catch (e: Exception) {
            LOGD("Error saving KV cache: ${e.message}")
            false
        }

    /**
     * Persists the cache of the loaded chat, if there is one. [chatId] guards against writing a
     * cache whose tokens belong to a chat that is no longer loaded.
     */
    private fun persistCache(logMessage: String, chatId: Long? = null) {
        stateLock.withLock {
            val latestChat = chat
            val latestCachePath = cachePath
            if (!isInstanceLoaded.get() || latestChat == null || latestCachePath == null) {
                return
            }
            if (chatId != null && latestChat.id != chatId) {
                return
            }
            if (saveCacheForChat(latestCachePath, latestChat)) {
                LOGD(logMessage)
            }
        }
    }

    private fun restoreCacheFromDisk(path: String): Boolean =
        cacheLock.withLock { instance.restoreCache(path) }

    /**
     * Rebuilds the native conversation from persisted messages without closing/reloading the model.
     * Used after editing or rolling back messages.
     */
    fun resetChat(
        chat: Chat,
        onError: (Exception) -> Unit,
        onSuccess: () -> Unit,
    ) {
        stateLock.withLock {
            if (!isInstanceLoaded.get()) {
                onError(IllegalStateException("Model not loaded"))
                return
            }
            val previousResponseJob = responseGenerationJob
            val previousBenchmarkJob = benchmarkJob
            previousResponseJob?.cancel()
            previousBenchmarkJob?.cancel()
            val previousResetJob = resetChatJob
            resetChatJob =
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        previousResetJob?.join()
                        previousResponseJob?.join()
                        previousBenchmarkJob?.join()
                        isInferenceOn = true
                        this@SmolLMManager.chat = chat
                        val messages = appDB.getMessagesForModel(chat.id)
                        val messageCount =
                            (if (chat.systemPrompt.isNotEmpty()) 1 else 0) + messages.size
                        val trimmed = instance.trimToMessageCount(messageCount)
                        if (trimmed) {
                            LOGD("Chat trimmed to $messageCount messages, KV prefix kept")
                        } else {
                            LOGD("Chat trimmed unavailable, performing full context reset")
                            instance.clearChat()
                            if (chat.systemPrompt.isNotEmpty()) {
                                instance.addSystemPrompt(chat.systemPrompt)
                            }
                            messages.forEach { message ->
                                if (message.isUserMessage) {
                                    instance.addUserMessage(message.message)
                                } else {
                                    instance.addAssistantMessage(message.message)
                                }
                            }
                        }
                        // Persist the rebuilt/trimmed state immediately so a
                        // later crash cannot leave the on-disk cache paired
                        // with a different message list.
                        val currentCachePath = cachePath
                        if (currentCachePath != null && isInstanceLoaded.get()) {
                            if (saveCacheForChat(currentCachePath, chat)) {
                                LOGD("KV cache saved after reset")
                            }
                        }
                        isInferenceOn = false
                        withContext(Dispatchers.Main) { onSuccess() }
                    } catch (e: CancellationException) {
                        isInferenceOn = false
                        throw e
                    } catch (e: Exception) {
                        isInferenceOn = false
                        LOGD("Error resetting chat: ${e.message}")
                        withContext(Dispatchers.Main) { onError(e) }
                    }
                }
        }
    }

    fun unload() = unloadInternal()

    /** Unloads the native instance and suspends until its cache is saved and handle is closed. */
    suspend fun unloadAndWait() {
        unloadInternal().join()
    }

    private fun unloadInternal(): Job {
        lateinit var unloadJob: Job
        stateLock.withLock {
            val previousResponseJob = responseGenerationJob
            val previousLoadJob = modelInitJob
            val previousResetJob = resetChatJob
            val previousBenchmarkJob = benchmarkJob
            previousResponseJob.safeCancelJobIfActive()
            previousLoadJob.safeCancelJobIfActive()
            previousResetJob.safeCancelJobIfActive()
            previousBenchmarkJob.safeCancelJobIfActive()

            val currentChat = chat
            val currentCachePath = cachePath
            val wasLoaded = isInstanceLoaded.get()
            isInstanceLoaded.set(false)
            isInferenceOn = false
            responseGenerationJob = null
            resetChatJob = null
            benchmarkJob = null
            chat = null
            cachePath = null

            // Native teardown can take a while, so run it off the main thread. NonCancellable
            // keeps the cache and native handle consistent even when a new load supersedes it.
            unloadJob =
                CoroutineScope(Dispatchers.Default).launch {
                    withContext(NonCancellable) {
                        previousResponseJob?.join()
                        previousLoadJob?.join()
                        previousResetJob?.join()
                        previousBenchmarkJob?.join()
                        if (wasLoaded && currentChat != null && currentCachePath != null) {
                            if (saveCacheForChat(currentCachePath, currentChat)) {
                                LOGD("KV cache saved before unload")
                            } else {
                                LOGD("No clean KV cache to save before unload")
                            }
                        }
                        try {
                            instance.close()
                        } catch (e: Exception) {
                            LOGD("Error closing instance: ${e.message}")
                        }
                    }
                }
            modelInitJob = unloadJob
        }
        return unloadJob
    }

    @OptIn(FlowPreview::class)
    fun getResponse(
        query: String,
        onPartialResponseGenerated: (String) -> Unit,
        onSuccess: (SmolLMResponse) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        stateLock.withLock {
            // Check if model is loaded
            if (!isInstanceLoaded.get()) {
                onError(IllegalStateException("Model not loaded"))
                return
            }
            val generationChatId =
                chat?.id
                    ?: run {
                        onError(IllegalStateException("No chat loaded"))
                        return
                    }

            // Cancel any existing response generation and wait for it to stop
            // before touching the native instance again.
            val previousResponseJob = responseGenerationJob
            val previousBenchmarkJob = benchmarkJob
            previousResponseJob?.cancel()
            previousBenchmarkJob?.cancel()

            responseGenerationJob = CoroutineScope(Dispatchers.Default).launch {
                try {
                    previousResponseJob?.join()
                    previousBenchmarkJob?.join()
                    resetChatJob?.join()
                    isInferenceOn = true
                    val responseBuilder = StringBuilder()
                    var lastDispatchedResponse = ""

                    val duration = measureTime {
                        instance
                            .getResponseAsFlow(query)
                            .map { piece ->
                                responseBuilder.append(piece)
                                responseBuilder.toString()
                            }
                            .sample(50.milliseconds)
                            .collect { sampledResponse ->
                                lastDispatchedResponse = sampledResponse
                                withContext(Dispatchers.Main) {
                                    onPartialResponseGenerated(sampledResponse)
                                }
                            }
                    }

                    val response = responseBuilder.toString()
                    if (response.isNotEmpty() && response != lastDispatchedResponse) {
                        withContext(Dispatchers.Main) {
                            onPartialResponseGenerated(response)
                        }
                    }

                    // Store the exact text appended to the native conversation. Presentation
                    // transforms (such as highlighting <think> blocks) must happen while
                    // rendering: changing the persisted text would make a restored transcript
                    // disagree with the token prefix saved in the KV cache.
                    //
                    // Use the chat that started this generation rather than whatever is loaded
                    // now. An unload can clear `chat` while this response is finishing; skipping
                    // the insert in that case would leave the native cache ahead of the database
                    // and force a full re-prefill on the next load. The unload path joins this
                    // job before saving, so the row still lands before the cache file.
                    if (appDB.addAssistantMessage(generationChatId, response)) {
                        // Persist every completed turn. Waiting until unload is
                        // unsafe when the process is killed or crashes after the
                        // response has already been written to the database.
                        persistCache("KV cache saved after response", generationChatId)
                    }

                    withContext(Dispatchers.Main) {
                        isInferenceOn = false
                        onSuccess(
                            SmolLMResponse(
                                response = response,
                                generationSpeed = instance.getResponseGenerationSpeed(),
                                generationTimeSecs = duration.inWholeSeconds.toInt(),
                                contextLengthUsed = instance.getContextLengthUsed(),
                                usedJinjaTemplate = instance.usedJinjaTemplate,
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    isInferenceOn = false
                    // The interrupted turn was trimmed back to its prompt prefix, so persist it: a
                    // reload right after a cancel can then restore the conversation instead of
                    // re-prefilling it from scratch.
                    persistCache("KV cache saved after cancel", generationChatId)
                    withContext(Dispatchers.Main) {
                        onCancelled()
                    }
                } catch (e: Exception) {
                    isInferenceOn = false
                    // A failed turn keeps the same reusable prompt prefix as a cancelled one.
                    persistCache("KV cache saved after error", generationChatId)
                    withContext(Dispatchers.Main) {
                        onError(e)
                    }
                }
            }
        }
    }

    private val BENCH_PROMPT_PROCESSING_TOKENS = 512
    private val BENCH_TOKEN_GENERATION_TOKENS = 128
    private val BENCH_SEQUENCE = 1
    private val BENCH_REPETITION = 3

    fun benchmark(onResult: (String) -> Unit) {
        stateLock.withLock {
            if (!isInstanceLoaded.get()) {
                onResult("Load a model before running the benchmark.")
                return
            }
            val previousResponseJob = responseGenerationJob
            val previousResetJob = resetChatJob
            val previousBenchmarkJob = benchmarkJob
            previousResponseJob.safeCancelJobIfActive()
            previousResetJob.safeCancelJobIfActive()
            previousBenchmarkJob.safeCancelJobIfActive()
            benchmarkJob =
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        previousResponseJob?.join()
                        previousResetJob?.join()
                        previousBenchmarkJob?.join()
                        if (!isInstanceLoaded.get()) {
                            withContext(Dispatchers.Main) {
                                onResult("The model was unloaded before the benchmark ran.")
                            }
                            return@launch
                        }
                        isInferenceOn = true
                        val result =
                            instance.benchModel(
                                BENCH_PROMPT_PROCESSING_TOKENS,
                                BENCH_TOKEN_GENERATION_TOKENS,
                                BENCH_SEQUENCE,
                                BENCH_REPETITION,
                            )
                        withContext(Dispatchers.Main) { onResult(result) }
                    } catch (e: CancellationException) {
                        LOGD("Benchmark cancelled")
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { onResult(e.message ?: "Benchmark failed") }
                    } finally {
                        isInferenceOn = false
                    }
                }
        }
    }

    fun stopResponseGeneration() {
        stateLock.withLock {
            responseGenerationJob.safeCancelJobIfActive()
            isInferenceOn = false
        }
    }

    private fun Job?.safeCancelJobIfActive() {
        this?.cancel()
    }
}