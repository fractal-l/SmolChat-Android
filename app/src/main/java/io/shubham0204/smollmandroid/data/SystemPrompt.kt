package io.shubham0204.smollmandroid.data

import kotlinx.serialization.Serializable

@Serializable
data class SystemPrompt(
    val id: Long = 0L,
    val name: String = "",
    val body: String = "",
)
