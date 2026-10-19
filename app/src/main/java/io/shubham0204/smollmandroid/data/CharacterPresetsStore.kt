package io.shubham0204.smollmandroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Single
class CharacterPresetsStore(private val sharedPrefStore: SharedPrefStore) {
    private val key = "character_presets"
    private val _presets = MutableStateFlow(loadPresets())
    val presets: StateFlow<List<CharacterPreset>> = _presets.asStateFlow()

    private fun loadPresets(): List<CharacterPreset> {
        val json = sharedPrefStore.get(key, "[]")
        return try {
            Json.decodeFromString<List<CharacterPreset>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addPreset(
        name: String,
        systemPrompt: String,
        temperature: Float,
        minP: Float,
    ) {
        val nextId = (_presets.value.maxOfOrNull { it.id } ?: 0L) + 1L
        savePresets(
            _presets.value +
                CharacterPreset(
                    id = nextId,
                    name = name,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                    minP = minP,
                )
        )
    }

    fun updatePreset(preset: CharacterPreset) {
        savePresets(_presets.value.map { if (it.id == preset.id) preset else it })
    }

    fun deletePreset(id: Long) {
        savePresets(_presets.value.filter { it.id != id })
    }

    private fun savePresets(presets: List<CharacterPreset>) {
        sharedPrefStore.put(key, Json.encodeToString(presets))
        _presets.value = presets
    }
}
