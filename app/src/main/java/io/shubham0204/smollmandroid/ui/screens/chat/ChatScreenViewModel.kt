/*
 * Copyright (C) 2024 Shubham Panchal
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

package io.shubham0204.smollmandroid.ui.screens.chat

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.MemoryInfo
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.AppDB
import io.shubham0204.smollmandroid.data.CharacterPreset
import io.shubham0204.smollmandroid.data.CharacterPresetsStore
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.ChatMessage
import io.shubham0204.smollmandroid.data.Folder
import io.shubham0204.smollmandroid.data.HardwareSettingsStore
import io.shubham0204.smollmandroid.data.LLMModel
import io.shubham0204.smollmandroid.data.SharedPrefStore
import io.shubham0204.smollmandroid.data.SystemPrompt
import io.shubham0204.smollmandroid.data.SystemPromptsStore
import io.shubham0204.smollmandroid.llm.ModelsRepository
import io.shubham0204.smollmandroid.llm.SmolLMManager
import io.shubham0204.smollmandroid.llm.speech2text.AudioTranscriptionService
import io.shubham0204.smollmandroid.ui.components.createAlertDialog
import io.shubham0204.smollmandroid.ui.screens.manage_asr.SETTING_DEF_VALUE_SPEECH2TEXT_CURR_MODEL_NAME
import io.shubham0204.smollmandroid.ui.screens.manage_asr.SETTING_DEF_VALUE_SPEECH2_TEXT_ENABLED
import io.shubham0204.smollmandroid.ui.screens.manage_asr.SETTING_KEY_SPEECH2TEXT_CURR_MODEL_NAME
import io.shubham0204.smollmandroid.ui.screens.manage_asr.SETTING_KEY_SPEECH2TEXT_ENABLED
import io.shubham0204.smollmandroid.ui.screens.manage_asr.availableASRModels
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel
import java.util.Date
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

private const val LOGTAG = "[SmolLMAndroid-Kt]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

private val findReasoningContentRegex =
    Regex(
        "<think>(.*?)</think>|<\\|channel>thought\\s*(.*?)\\s*<channel\\|>",
        RegexOption.DOT_MATCHES_ALL,
    )
private val findThinkHtmlBlockRegex = Regex("<blockquote><i><h6>[\\s\\S]*?</i></h6></blockquote>")
private val untitledChatNameRegex = Regex("^Untitled(?: \\d+)?$")

/** Styles raw model reasoning without changing the text that gets persisted. */
internal fun String.withThinkBlocksForDisplay(): String =
    findReasoningContentRegex.replace(this) { matchResult ->
        val reasoning =
            matchResult.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (reasoning.isEmpty()) {
            ""
        } else {
            "<blockquote><i><h6>$reasoning</i></h6></blockquote>"
        }
    }

internal fun String.stripThinkingForClipboard(): String =
    replace(findThinkHtmlBlockRegex, "").replace(findReasoningContentRegex, "").trim()

sealed class ChatScreenUIEvent {
    sealed class ChatEvents {
        data class UpdateChatModel(val model: LLMModel) : ChatScreenUIEvent()

        data class UpdateModel(val model: LLMModel) : ChatScreenUIEvent()

        data object LoadChatModel : ChatScreenUIEvent()

        data class DeleteModel(val model: LLMModel) : ChatScreenUIEvent()

        data class SendUserQuery(val query: String) : ChatScreenUIEvent()

        data object SkipTurn : ChatScreenUIEvent()

        data object StopGeneration : ChatScreenUIEvent()

        data class OnMessageEdited(
            val chatId: Long,
            val oldMessage: ChatMessage,
            val newMessageText: String,
        ) : ChatScreenUIEvent()

        data class OnMessageRollback(val chatId: Long, val message: ChatMessage) :
            ChatScreenUIEvent()

        data class OnDeleteChat(val chat: Chat) : ChatScreenUIEvent()

        data class OnDeleteChatMessages(val chat: Chat) : ChatScreenUIEvent()

        data object NewChat : ChatScreenUIEvent()

        data class SwitchChat(val chat: Chat) : ChatScreenUIEvent()

        data class UpdateChatSettings(val settings: EditableChatSettings, val existingChat: Chat) :
            ChatScreenUIEvent()

        data class StartBenchmark(val onResult: (String) -> Unit) : ChatScreenUIEvent()

        data class StartAudioTranscription(val onLineComplete: (String) -> Unit) :
            ChatScreenUIEvent()

        data object StopAudioTranscription : ChatScreenUIEvent()
    }

    sealed class SystemPromptEvents {
        data class AddSystemPrompt(val name: String, val body: String) : ChatScreenUIEvent()

        data class DeleteSystemPrompt(val id: Long) : ChatScreenUIEvent()
    }

    sealed class FolderEvents {
        data class UpdateChatFolder(val newFolderId: Long) : ChatScreenUIEvent()

        data class AddFolder(val folderName: String) : ChatScreenUIEvent()

        data class UpdateFolder(val folder: Folder) : ChatScreenUIEvent()

        data class DeleteFolder(val folderId: Long) : ChatScreenUIEvent()

        data class DeleteFolderWithChats(val folderId: Long) : ChatScreenUIEvent()
    }

    sealed class DialogEvents {
        data class ToggleChangeFolderDialog(val visible: Boolean) : ChatScreenUIEvent()

        data class ToggleSelectModelListDialog(val visible: Boolean) : ChatScreenUIEvent()

        data class ToggleMoreOptionsPopup(val visible: Boolean) : ChatScreenUIEvent()

        data object ToggleRAMUsageLabel : ChatScreenUIEvent()

        data class ShowContextLengthUsageDialog(val chat: Chat) : ChatScreenUIEvent()
    }
}

data class AudioTranscriptionUIState(
    val isRecording: Boolean = false,
    val isAvailable: Boolean = false,
)

data class ChatScreenUIState(
    val chat: Chat = Chat(),
    val isGeneratingResponse: Boolean = false,
    val modelLoadingState: ChatScreenViewModel.ModelLoadingState =
        ChatScreenViewModel.ModelLoadingState.NOT_LOADED,
    val responseGenerationsSpeed: Float? = null,
    val responseGenerationTimeSecs: Int? = null,
    val memoryUsage: Pair<Float, Float>? = null,
    val folders: ImmutableList<Folder> = emptyList<Folder>().toImmutableList(),
    val chats: ImmutableList<Chat> = emptyList<Chat>().toImmutableList(),
    val models: ImmutableList<LLMModel> = emptyList<LLMModel>().toImmutableList(),
    val messages: ImmutableList<ChatMessage> = emptyList<ChatMessage>().toImmutableList(),
    val systemPrompts: ImmutableList<SystemPrompt> = emptyList<SystemPrompt>().toImmutableList(),
    val characterPresets: ImmutableList<CharacterPreset> =
        emptyList<CharacterPreset>().toImmutableList(),
    val benchmarkResult: String? = null,
    val audioTranscriptionUIState: AudioTranscriptionUIState = AudioTranscriptionUIState(),
    val showChangeFolderDialog: Boolean = false,
    val showSelectModelListDialog: Boolean = false,
    val showMoreOptionsPopup: Boolean = false,
)

@KoinViewModel
class ChatScreenViewModel(
    val context: Context,
    val appDB: AppDB,
    val modelsRepository: ModelsRepository,
    val smolLMManager: SmolLMManager,
    val audioTranscriptionService: AudioTranscriptionService,
    val mdRenderer: MDRenderer,
    val sharedPrefStore: SharedPrefStore,
    val systemPromptsStore: SystemPromptsStore,
    val characterPresetsStore: CharacterPresetsStore,
    val hardwareSettingsStore: HardwareSettingsStore,
) : ViewModel() {
    enum class ModelLoadingState {
        NOT_LOADED, // model loading not started
        IN_PROGRESS, // model loading in-progress
        SUCCESS, // model loading finished successfully
        FAILURE, // model loading failed
    }

    private val _uiState = MutableStateFlow(initializeUIState())
    val uiState: StateFlow<ChatScreenUIState> = _uiState

    private val _renderedPartialResponse = MutableStateFlow<Spanned?>(null)
    val renderedPartialResponse: StateFlow<Spanned?> = _renderedPartialResponse
    private val partialResponseText = MutableStateFlow("")
    private var partialRenderJob: Job? = null

    /** Invalidates callbacks from a superseded generation. */
    private var generationToken = 0L

    // Cache size of 500 messages (stores Pair<rawText, renderedSpanned>)
    private val renderedMessageCache = LruCache<Long, Pair<String, Spanned>>(500)

    // Used to pre-set a value in the query text-field of the chat screen
    // It is set when a query comes from a 'share-text' intent in ChatActivity
    var questionTextDefaultVal: String? = null

    fun consumeQuestionTextDefaultVal(): String? =
        questionTextDefaultVal.also { questionTextDefaultVal = null }

    private var activityManager: ActivityManager

    init {
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        setupCollectors()
        loadModel()
    }

    /**
     * Load the model for the current chat. If chat is configured with a LLM (i.e. chat.llModelId !=
     * -1), then load the model. If not, show the model list dialog. Once the model is finalized,
     * read the system prompt and user messages from the database and add them to the model.
     */
    fun loadModel(onComplete: (ModelLoadingState) -> Unit = {}) {
        val chat = _uiState.value.chat
        val hardwareSettings = hardwareSettingsStore.settings.value
        if (chat.llmModelId == -1L) {
            _uiState.update { it.copy(showSelectModelListDialog = true) }
        } else {
            val model = modelsRepository.getModelFromId(chat.llmModelId)
            if (model == null) {
                val updatedChat = chat.copy(llmModelId = -1L, contextSizeConsumed = 0)
                appDB.updateChat(updatedChat)
                _uiState.update {
                    it.copy(
                        chat = updatedChat.copy(llmModel = null),
                        modelLoadingState = ModelLoadingState.NOT_LOADED,
                        showSelectModelListDialog = true,
                    )
                }
                onComplete(ModelLoadingState.NOT_LOADED)
                return
            }
            _uiState.update {
                it.copy(
                    modelLoadingState = ModelLoadingState.IN_PROGRESS,
                    chat = chat.copy(llmModel = model),
                )
            }
            smolLMManager.load(
                chat,
                model.path,
                SmolLM.InferenceParams(
                    minP = chat.minP,
                    temperature = chat.temperature,
                    storeChats = true,
                    contextSize = chat.contextSize.toLong(),
                    chatTemplate =
                        resolveChatTemplate(model, chat).takeIf {
                            it.isNotBlank() && ("{%" in it || "{{" in it)
                        },
                    numThreads = hardwareSettings.nThreads,
                    useMmap = hardwareSettings.useMmap,
                    useMlock = hardwareSettings.useMlock,
                    topK = chat.topK,
                    topP = chat.topP,
                    repeatPenalty = chat.repeatPenalty,
                    repeatLastN = chat.repeatLastN,
                    seed = chat.seed,
                    enableThinking = chat.enableThinking,
                ),
                onError = { e ->
                    _uiState.update { it.copy(modelLoadingState = ModelLoadingState.FAILURE) }
                    onComplete(ModelLoadingState.FAILURE)
                    createAlertDialog(
                        dialogTitle = context.getString(R.string.dialog_err_title),
                        dialogText = context.getString(R.string.dialog_err_text, e.message),
                        dialogPositiveButtonText =
                            context.getString(R.string.dialog_err_change_model),
                        onPositiveButtonClick = {
                            onEvent(
                                ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog(
                                    visible = true
                                )
                            )
                        },
                        dialogNegativeButtonText = context.getString(R.string.dialog_err_close),
                        onNegativeButtonClick = {},
                    )
                },
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            modelLoadingState = ModelLoadingState.SUCCESS,
                            memoryUsage =
                                if (it.memoryUsage != null) {
                                    getCurrentMemoryUsage()
                                } else {
                                    null
                                },
                        )
                    }
                    onComplete(ModelLoadingState.SUCCESS)
                },
            )
        }
    }

    /** Clears the resources occupied by the model only if the inference is not in progress. */
    fun unloadModel(): Boolean =
        if (!smolLMManager.isInferenceOn) {
            smolLMManager.unload()
            _uiState.update { it.copy(modelLoadingState = ModelLoadingState.NOT_LOADED) }
            true
        } else {
            false
        }

    @SuppressLint("StringFormatMatches")
    fun onEvent(event: ChatScreenUIEvent) {
        when (event) {
            is ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog -> {
                _uiState.update { it.copy(showSelectModelListDialog = event.visible) }
            }

            is ChatScreenUIEvent.DialogEvents.ToggleMoreOptionsPopup -> {
                _uiState.update { it.copy(showMoreOptionsPopup = event.visible) }
            }

            is ChatScreenUIEvent.DialogEvents.ToggleChangeFolderDialog -> {
                _uiState.update { it.copy(showChangeFolderDialog = event.visible) }
            }

            ChatScreenUIEvent.DialogEvents.ToggleRAMUsageLabel -> {
                _uiState.update {
                    it.copy(
                        memoryUsage =
                            if (it.memoryUsage != null) {
                                null
                            } else {
                                getCurrentMemoryUsage()
                            }
                    )
                }
            }

            is ChatScreenUIEvent.DialogEvents.ShowContextLengthUsageDialog -> {
                createAlertDialog(
                    dialogTitle = context.getString(R.string.dialog_ctx_usage_title),
                    dialogText =
                        context.getString(
                            R.string.dialog_ctx_usage_text,
                            event.chat.contextSizeConsumed,
                            event.chat.contextSize,
                        ),
                    dialogPositiveButtonText = context.getString(R.string.dialog_ctx_usage_close),
                    onPositiveButtonClick = {},
                    dialogNegativeButtonText = null,
                    onNegativeButtonClick = null,
                )
            }

            is ChatScreenUIEvent.FolderEvents.UpdateChatFolder -> {
                val updatedChat = _uiState.value.chat.copy(folderId = event.newFolderId)
                appDB.updateChat(updatedChat)
                _uiState.update { it.copy(chat = updatedChat) }
            }

            is ChatScreenUIEvent.FolderEvents.AddFolder -> {
                appDB.addFolder(event.folderName)
            }

            is ChatScreenUIEvent.FolderEvents.UpdateFolder -> {
                appDB.updateFolder(event.folder)
            }

            is ChatScreenUIEvent.FolderEvents.DeleteFolder -> {
                appDB.deleteFolder(event.folderId)
            }

            is ChatScreenUIEvent.FolderEvents.DeleteFolderWithChats -> {
                appDB.deleteFolderWithChats(event.folderId)
            }

            is ChatScreenUIEvent.SystemPromptEvents.AddSystemPrompt -> {
                systemPromptsStore.addPrompt(event.name, event.body)
            }

            is ChatScreenUIEvent.SystemPromptEvents.DeleteSystemPrompt -> {
                systemPromptsStore.deletePrompt(event.id)
            }

            is ChatScreenUIEvent.ChatEvents.UpdateChatModel -> {
                updateChatLLMParams(event.model.id)
                loadModel()
                onEvent(ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog(visible = false))
            }

            is ChatScreenUIEvent.ChatEvents.UpdateModel -> {
                val currentChat = _uiState.value.chat
                val isCurrentModel = currentChat.llmModelId == event.model.id
                val templateChanged =
                    isCurrentModel &&
                        currentChat.llmModel?.chatTemplate != event.model.chatTemplate

                modelsRepository.updateModel(event.model)
                _uiState.update { state ->
                    state.copy(
                        chat =
                            if (isCurrentModel) {
                                state.chat.copy(llmModel = event.model)
                            } else {
                                state.chat
                            },
                    )
                }
                if (templateChanged) {
                    unloadModel()
                    loadModel()
                }
                Toast.makeText(
                        context,
                        context.getString(R.string.chat_model_saved, event.model.name),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }

            is ChatScreenUIEvent.ChatEvents.DeleteModel -> {
                deleteModel(event.model.id)
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_model_deleted, event.model.name),
                    Toast.LENGTH_LONG,
                )
                    .show()
            }

            ChatScreenUIEvent.ChatEvents.LoadChatModel -> loadModel()

            is ChatScreenUIEvent.ChatEvents.SendUserQuery -> {
                sendUserQuery(event.query)
            }

            ChatScreenUIEvent.ChatEvents.SkipTurn -> {
                sendUserQuery("", addMessageToDB = false)
            }

            ChatScreenUIEvent.ChatEvents.StopGeneration -> {
                stopGeneration()
            }

            is ChatScreenUIEvent.ChatEvents.OnMessageEdited -> {
                // Remove the edited message (and anything after it) from the chat,
                // rebuild the native conversation while keeping the model loaded,
                // then send the new text as a fresh query. The query is added to the
                // database exactly once by `sendUserQuery`.
                rollbackMessagesFrom(event.chatId, event.oldMessage.id) {
                    sendUserQuery(event.newMessageText)
                }
            }

            is ChatScreenUIEvent.ChatEvents.OnMessageRollback -> {
                createAlertDialog(
                    dialogTitle = context.getString(R.string.dialog_rollback_title),
                    dialogText = context.getString(R.string.dialog_rollback_text),
                    dialogPositiveButtonText = context.getString(R.string.chat_message_rollback),
                    dialogNegativeButtonText = context.getString(R.string.dialog_neg_cancel),
                    onPositiveButtonClick = {
                        rollbackMessagesFrom(event.chatId, event.message.id)
                    },
                    onNegativeButtonClick = {},
                )
            }

            is ChatScreenUIEvent.ChatEvents.OnDeleteChat -> {
                createAlertDialog(
                    dialogTitle = context.getString(R.string.dialog_title_delete_chat),
                    dialogText =
                        context.getString(R.string.dialog_text_delete_chat, event.chat.name),
                    dialogPositiveButtonText = context.getString(R.string.dialog_pos_delete),
                    dialogNegativeButtonText = context.getString(R.string.dialog_neg_cancel),
                    onPositiveButtonClick = {
                        deleteChat(event.chat)
                        Toast.makeText(
                            context,
                            "Chat '${event.chat.name}' deleted",
                            Toast.LENGTH_LONG,
                        )
                            .show()
                    },
                    onNegativeButtonClick = {},
                )
            }

            is ChatScreenUIEvent.ChatEvents.OnDeleteChatMessages -> {
                createAlertDialog(
                    dialogTitle = context.getString(R.string.chat_options_clear_messages),
                    dialogText = context.getString(R.string.chat_options_clear_messages_text),
                    dialogPositiveButtonText = context.getString(R.string.dialog_pos_clear),
                    dialogNegativeButtonText = context.getString(R.string.dialog_neg_cancel),
                    onPositiveButtonClick = {
                        deleteChatMessages(event.chat)
                        val showClearedToast = {
                            Toast.makeText(
                                    context,
                                    "Chat '${event.chat.name}' cleared",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                        smolLMManager.resetChat(
                            chat = event.chat,
                            onError = {
                                unloadModel()
                                loadModel(
                                    onComplete = {
                                        if (it == ModelLoadingState.SUCCESS) {
                                            showClearedToast()
                                        }
                                    }
                                )
                            },
                            onSuccess = showClearedToast,
                        )
                    },
                    onNegativeButtonClick = {},
                )
            }

            ChatScreenUIEvent.ChatEvents.NewChat -> {
                val chatCount = appDB.getChatsCount()
                val newChat = appDB.addChat(chatName = "Untitled ${chatCount + 1}")
                switchChat(newChat)
            }

            is ChatScreenUIEvent.ChatEvents.SwitchChat -> {
                switchChat(event.chat)
            }

            is ChatScreenUIEvent.ChatEvents.UpdateChatSettings -> {
                val newChat = event.settings.toChat(event.existingChat)
                _uiState.update { it.copy(chat = newChat) }
                appDB.updateChat(newChat)
                unloadModel()
                loadModel()
            }

            is ChatScreenUIEvent.ChatEvents.StartBenchmark -> {
                smolLMManager.benchmark { result -> event.onResult(result) }
            }

            is ChatScreenUIEvent.ChatEvents.StartAudioTranscription -> {
                val asrModelName =
                    sharedPrefStore.get(
                        SETTING_KEY_SPEECH2TEXT_CURR_MODEL_NAME,
                        SETTING_DEF_VALUE_SPEECH2TEXT_CURR_MODEL_NAME,
                    )
                val asrModel =
                    availableASRModels.firstOrNull {
                        it.name == asrModelName &&
                            modelsRepository.isSpeech2TextModelDownloaded(it)
                    }
                        ?: availableASRModels.firstOrNull {
                            modelsRepository.isSpeech2TextModelDownloaded(it)
                        }
                if (asrModel == null) {
                    Toast.makeText(
                            context,
                            "Download a speech model in Settings first.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    _uiState.update {
                        it.copy(
                            audioTranscriptionUIState =
                                it.audioTranscriptionUIState.copy(isRecording = false)
                        )
                    }
                    return
                }
                val error =
                    audioTranscriptionService.startTranscription(asrModel, this) { transcription ->
                        event.onLineComplete(transcription)
                    }
                if (error != null) {
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                    _uiState.update {
                        it.copy(
                            audioTranscriptionUIState =
                                it.audioTranscriptionUIState.copy(isRecording = false)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            audioTranscriptionUIState =
                                AudioTranscriptionUIState(isRecording = true, isAvailable = true)
                        )
                    }
                }
            }

            is ChatScreenUIEvent.ChatEvents.StopAudioTranscription -> {
                _uiState.update {
                    it.copy(
                        audioTranscriptionUIState =
                            it.audioTranscriptionUIState.copy(isRecording = false)
                    )
                }
                audioTranscriptionService.stopTranscription(this)
            }
        }
    }

    private fun initializeUIState(): ChatScreenUIState {
        val defaultChat = appDB.loadDefaultChat()

        val isSpeech2TextEnabled = sharedPrefStore.get(
            SETTING_KEY_SPEECH2TEXT_ENABLED,
            SETTING_DEF_VALUE_SPEECH2_TEXT_ENABLED
        )
        val audioTranscriptionUIState = AudioTranscriptionUIState(
            isAvailable = isSpeech2TextEnabled
        )

        return ChatScreenUIState(
            chat = defaultChat,
            audioTranscriptionUIState = audioTranscriptionUIState
        )
    }

    private fun setupCollectors() {
        viewModelScope.launch {
            launch {
                appDB.getChats().collect { chats ->
                    _uiState.update { it.copy(chats = chats.toImmutableList()) }
                }
            }
            launch {
                appDB.getFolders().collect { folders ->
                    _uiState.update { it.copy(folders = folders.toImmutableList()) }
                }
            }
            launch {
                appDB.getModels().collect { models ->
                    _uiState.update { state ->
                        state.copy(models = models.toImmutableList())
                    }
                }
            }
            launch {
                systemPromptsStore.systemPrompts.collect { prompts ->
                    _uiState.update { it.copy(systemPrompts = prompts.toImmutableList()) }
                }
            }
            launch {
                characterPresetsStore.presets.collect { presets ->
                    _uiState.update { it.copy(characterPresets = presets.toImmutableList()) }
                }
            }
            launch {
                _uiState
                    .map { it.chat }
                    .distinctUntilChanged()
                    .collectLatest { chat ->
                        appDB.getMessages(chat.id).collect { chatMessages ->
                            // Offload Markdown rendering to background thread
                            val renderedMessages =
                                withContext(Dispatchers.Default) {
                                    chatMessages
                                        .map { chatMessage ->
                                            val cached = renderedMessageCache.get(chatMessage.id)
                                            if (cached != null && cached.first == chatMessage.message) {
                                                // Cache hit: reuse existing Spanned instance
                                                chatMessage.renderedMessage = cached.second
                                            } else {
                                                // User prompts are shown verbatim; only
                                                // assistant messages are Markdown.
                                                val rendered =
                                                    if (chatMessage.isUserMessage) {
                                                        SpannableString(chatMessage.message)
                                                    } else {
                                                        mdRenderer.render(
                                                            chatMessage.message
                                                                .withThinkBlocksForDisplay()
                                                        )
                                                    }
                                                renderedMessageCache.put(
                                                    chatMessage.id,
                                                    chatMessage.message to rendered,
                                                )
                                                chatMessage.renderedMessage = rendered
                                            }
                                            chatMessage
                                        }
                                        .toImmutableList()
                                }
                            // Update UI state with the rendered list
                            _uiState.update { it.copy(messages = renderedMessages) }
                        }
                    }
            }
            launch {
                _uiState
                    .map { it.chat }
                    .distinctUntilChanged()
                    .collectLatest { chat ->
                        _uiState.update { uiState ->
                            uiState.copy(
                                chat =
                                    uiState.chat.copy(
                                        llmModel =
                                            modelsRepository.getModelFromId(
                                                uiState.chat.llmModelId
                                            )
                                    )
                            )
                        }
                    }
            }
            launch {
                sharedPrefStore.sharedPrefStoreChanges.collect { prefKey ->
                    if (prefKey == SETTING_KEY_SPEECH2TEXT_ENABLED) {
                        audioTranscriptionService.stopTranscription(this)
                        val isSpeech2TextEnabled = sharedPrefStore.get(
                            SETTING_KEY_SPEECH2TEXT_ENABLED,
                            SETTING_DEF_VALUE_SPEECH2_TEXT_ENABLED
                        )
                        _uiState.update {
                            it.copy(
                                audioTranscriptionUIState = AudioTranscriptionUIState(
                                    isAvailable = isSpeech2TextEnabled,
                                    isRecording = false
                                )
                            )
                        }
                    } else if (prefKey == SETTING_KEY_SPEECH2TEXT_CURR_MODEL_NAME) {
                        audioTranscriptionService.stopTranscription(this)
                        _uiState.update {
                            it.copy(
                                audioTranscriptionUIState = AudioTranscriptionUIState(
                                    isAvailable = true,
                                    isRecording = false
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateChatLLMParams(modelId: Long) {
        val newChat = _uiState.value.chat.copy(llmModelId = modelId)
        _uiState.update { it.copy(chat = newChat) }
        appDB.updateChat(newChat)
    }

    /**
     * Chat templates are owned by models now. Chats created by older versions of the app stored a
     * copy of the template, so use that copy (and persist it to the model) when the model has no
     * template of its own.
     */
    private fun resolveChatTemplate(model: LLMModel, chat: Chat): String {
        if (model.chatTemplate.isNotBlank()) {
            return model.chatTemplate
        }
        // Migrate a legacy per-chat template to the model exactly once. Without this guard,
        // clearing the model template later would resurrect the old chat copy.
        val migrationKey = "chat_template_backfilled_${model.id}"
        if (chat.chatTemplate.isNotBlank() && !sharedPrefStore.get(migrationKey, false)) {
            sharedPrefStore.put(migrationKey, true)
            modelsRepository.updateModel(model.copy(chatTemplate = chat.chatTemplate))
            return chat.chatTemplate
        }
        return model.chatTemplate
    }

    /**
     * Deletes the message with the given id and every message after it in the chat, then rebuilds
     * the native conversation from the remaining messages. The model itself stays loaded, so
     * rollback/edit does not pay the model-loading cost again.
     */
    private fun rollbackMessagesFrom(
        chatId: Long,
        messageId: Long,
        onReloadComplete: () -> Unit = {},
    ) {
        stopGeneration()
        _uiState.value.messages
            .filter { it.chatId == chatId && it.id >= messageId }
            .forEach { renderedMessageCache.remove(it.id) }
        appDB.deleteMessagesFrom(chatId, messageId)
        val chat = _uiState.value.chat
        smolLMManager.resetChat(
            chat = chat,
            onError = {
                unloadModel()
                loadModel(
                    onComplete = {
                        if (it == ModelLoadingState.SUCCESS) {
                            onReloadComplete()
                        }
                    }
                )
            },
            onSuccess = onReloadComplete,
        )
    }

    /**
     * Replaces the generated "Untitled N" placeholder with a short title derived
     * from the first user message. Chats that were already named by the user are
     * left untouched.
     */
    private fun nameChatFromFirstMessage(chat: Chat, query: String): Chat {
        if (!untitledChatNameRegex.matches(chat.name)) {
            return chat
        }
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) {
            return chat
        }
        val title =
            if (normalized.length > 60) {
                normalized.take(60).trimEnd() + "…"
            } else {
                normalized
            }
        val namedChat = chat.copy(name = title)
        _uiState.update { state ->
            if (state.chat.id == namedChat.id) {
                state.copy(chat = namedChat)
            } else {
                state
            }
        }
        appDB.updateChat(namedChat)
        return namedChat
    }

    private fun sendUserQuery(query: String, addMessageToDB: Boolean = true) {
        var chat = uiState.value.chat
        // Update the 'dateUsed' attribute of the current Chat instance
        // when a query is sent by the user
        chat.dateUsed = Date()
        appDB.updateChat(chat)

        if (addMessageToDB) {
            appDB.addUserMessage(chat.id, query)
            chat = nameChatFromFirstMessage(chat, query)
        }
        val token = ++generationToken
        _uiState.update { it.copy(isGeneratingResponse = true) }
        startPartialRendering()
        smolLMManager.getResponse(
            query,
            onPartialResponseGenerated = { resp ->
                if (token == generationToken) {
                    partialResponseText.value = resp
                }
            },
            onSuccess = { response ->
                if (token == generationToken) {
                    stopPartialRendering()
                    val updatedChat = chat.copy(contextSizeConsumed = response.contextLengthUsed)
                    _uiState.update {
                        it.copy(
                            chat = updatedChat,
                            isGeneratingResponse = false,
                            responseGenerationsSpeed = response.generationSpeed,
                            responseGenerationTimeSecs = response.generationTimeSecs,
                            memoryUsage =
                                if (it.memoryUsage != null) {
                                    getCurrentMemoryUsage()
                                } else {
                                    null
                                },
                        )
                    }
                    appDB.updateChat(updatedChat)
                    if (!response.usedJinjaTemplate) {
                        Toast.makeText(
                            context,
                            "Model's Jinja chat template not fully supported, using legacy renderer",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onCancelled = {
                if (token == generationToken) {
                    stopPartialRendering()
                    _uiState.update { it.copy(isGeneratingResponse = false) }
                }
            },
            onError = { exception ->
                if (token == generationToken) {
                    stopPartialRendering()
                    _uiState.update { it.copy(isGeneratingResponse = false) }
                    createAlertDialog(
                        dialogTitle = context.getString(R.string.dialog_err_title),
                        dialogText =
                            context.getString(R.string.dialog_err_text, exception.message),
                        dialogPositiveButtonText =
                            context.getString(R.string.dialog_err_change_model),
                        onPositiveButtonClick = {
                            onEvent(
                                ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog(
                                    visible = true
                                )
                            )
                        },
                        dialogNegativeButtonText = context.getString(R.string.dialog_err_close),
                        onNegativeButtonClick = {},
                    )
                }
            },
        )
    }

    @OptIn(FlowPreview::class)
    private fun startPartialRendering() {
        partialRenderJob?.cancel()
        partialResponseText.value = ""
        _renderedPartialResponse.value = null
        partialRenderJob =
            viewModelScope.launch {
                partialResponseText
                    .sample(120.milliseconds)
                    .collect { text ->
                        if (text.isBlank()) return@collect
                        val rendered =
                            withContext(Dispatchers.Default) {
                                mdRenderer.render(text.withThinkBlocksForDisplay())
                            }
                        _renderedPartialResponse.value = rendered
                    }
            }
    }

    private fun stopPartialRendering() {
        partialRenderJob?.cancel()
        partialRenderJob = null
        partialResponseText.value = ""
        _renderedPartialResponse.value = null
    }

    private fun stopGeneration() {
        generationToken++
        smolLMManager.stopResponseGeneration()
        stopPartialRendering()
        _uiState.update { it.copy(isGeneratingResponse = false) }
    }

    private fun switchChat(chat: Chat) {
        stopGeneration()
        _uiState.update { it.copy(chat = chat) }
        loadModel()
    }

    private fun deleteChat(chat: Chat) {
        stopGeneration()
        appDB.deleteChat(chat)
        switchChat(appDB.loadDefaultChat())
    }

    private fun deleteChatMessages(chat: Chat) {
        stopGeneration()
        appDB.deleteMessages(chat.id)
    }

    private fun deleteModel(modelId: Long) {
        viewModelScope.launch {
            val chatId = _uiState.value.chat.id
            val isCurrentModel = _uiState.value.chat.llmModelId == modelId
            if (isCurrentModel) {
                stopGeneration()
                _uiState.update { it.copy(modelLoadingState = ModelLoadingState.NOT_LOADED) }
                // Wait for the native handle to close before unlinking the model file.
                smolLMManager.unloadAndWait()
                if (_uiState.value.chat.id == chatId &&
                    _uiState.value.chat.llmModelId == modelId
                ) {
                    _uiState.update {
                        it.copy(
                            chat =
                                it.chat.copy(
                                    llmModelId = -1,
                                    llmModel = null,
                                    contextSizeConsumed = 0,
                                )
                        )
                    }
                }
            }
            val deleted =
                withContext(Dispatchers.IO) {
                    // Abort if the model was selected again while the native instance unloaded.
                    if (_uiState.value.chat.llmModelId == modelId) {
                        false
                    } else {
                        modelsRepository.deleteModel(modelId)
                        true
                    }
                }
            if (!deleted) {
                Toast.makeText(
                        context,
                        context.getString(R.string.chat_model_in_use),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                return@launch
            }
            if (isCurrentModel && _uiState.value.chat.id == chatId) {
                _uiState.update {
                    it.copy(
                        modelLoadingState = ModelLoadingState.NOT_LOADED,
                        showSelectModelListDialog = true,
                    )
                }
            }
        }
    }

    /**
     * Get the current memory usage of the device. This method returns the memory consumed (in GBs)
     * and the total memory available on the device (in GBs)
     */
    override fun onCleared() {
        stopGeneration()
        audioTranscriptionService.stopTranscription(this, removeListener = true)
        smolLMManager.unload()
        super.onCleared()
    }

    private fun getCurrentMemoryUsage(): Pair<Float, Float> {
        val memoryInfo = MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemory = (memoryInfo.totalMem) / 1024.0.pow(3.0)
        val usedMemory = (memoryInfo.totalMem - memoryInfo.availMem) / 1024.0.pow(3.0)
        return Pair(usedMemory.toFloat(), totalMemory.toFloat())
    }
}
