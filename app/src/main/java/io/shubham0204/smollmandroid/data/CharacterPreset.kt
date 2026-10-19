package io.shubham0204.smollmandroid.data

import kotlinx.serialization.Serializable

@Serializable
data class CharacterPreset(
    val id: Long = 0,
    val name: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.8f,
    val minP: Float = 0.1f,
)
