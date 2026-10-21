package io.shubham0204.smollmandroid.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicLong

@Single
class SystemPromptsStore(private val sharedPrefStore: SharedPrefStore) {
    private val key = "system_prompts"
    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private val initialPrompts = loadPrompts()
    private val nextId = AtomicLong((initialPrompts.maxOfOrNull { it.id } ?: 0L) + 1L)
    private val _systemPrompts = MutableStateFlow(initialPrompts)
    val systemPrompts: StateFlow<List<SystemPrompt>> = _systemPrompts.asStateFlow()

    private fun loadPrompts(): List<SystemPrompt> {
        val json = sharedPrefStore.get(key, "[]")
        return try {
            jsonCodec.decodeFromString<List<SystemPrompt>>(json)
        } catch (e: Exception) {
            Log.e("SystemPromptsStore", "Could not read saved system prompts", e)
            emptyList()
        }
    }

    fun addPrompt(name: String, body: String) {
        val newList =
            _systemPrompts.value +
                SystemPrompt(id = nextId.getAndIncrement(), name = name, body = body)
        savePrompts(newList)
    }

    fun deletePrompt(id: Long) {
        val newList = _systemPrompts.value.filter { it.id != id }
        savePrompts(newList)
    }

    private fun savePrompts(prompts: List<SystemPrompt>) {
        val json = jsonCodec.encodeToString(prompts)
        sharedPrefStore.put(key, json)
        _systemPrompts.value = prompts
    }
}
