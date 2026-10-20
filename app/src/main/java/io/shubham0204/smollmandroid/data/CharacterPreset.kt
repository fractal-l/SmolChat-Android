package io.shubham0204.smollmandroid.data

import kotlinx.serialization.Serializable

@Serializable
data class CharacterPreset(
    val id: Long = 0,
    val name: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.8f,
    val minP: Float = 0.1f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Int = -1,
)
