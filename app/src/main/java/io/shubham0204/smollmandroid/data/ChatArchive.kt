package io.shubham0204.smollmandroid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Portable representation of the user's chats. Model IDs are intentionally not restored on
 * import because they are local to the device that produced the export.
 */
@Serializable
data class ChatArchive(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = 0L,
    val folders: List<ArchivedFolder> = emptyList(),
    val chats: List<ArchivedChat> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class ArchivedFolder(
    val id: Long = 0L,
    val name: String = "",
)

@Serializable
data class ArchivedChat(
    val name: String = "",
    val systemPrompt: String = "",
    val dateCreated: Long = 0L,
    val dateUsed: Long = 0L,
    val minP: Float = 0.1f,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Int = -1,
    val enableThinking: Boolean = false,
    val nThreads: Int = 4,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val contextSize: Int = 0,
    val chatTemplate: String = "",
    val folderId: Long = -1L,
    val messages: List<ArchivedMessage> = emptyList(),
)

@Serializable
data class ArchivedMessage(
    val message: String = "",
    val isUserMessage: Boolean = false,
)

data class ChatImportResult(
    val chats: Int,
    val messages: Int,
)

object ChatArchiveJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(archive: ChatArchive): String = json.encodeToString(archive)

    fun decode(text: String): ChatArchive = json.decodeFromString(text)
}
