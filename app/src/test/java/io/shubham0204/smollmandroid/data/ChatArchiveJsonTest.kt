package io.shubham0204.smollmandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArchiveJsonTest {
    @Test
    fun `round trip preserves chats, folders, and messages`() {
        val archive =
            ChatArchive(
                exportedAt = 1234L,
                folders = listOf(ArchivedFolder(id = 1L, name = "Work")),
                chats =
                    listOf(
                        ArchivedChat(
                            name = "Release notes",
                            systemPrompt = "Be concise.",
                            dateCreated = 10L,
                            dateUsed = 20L,
                            temperature = 0.4f,
                            folderId = 1L,
                            messages =
                                listOf(
                                    ArchivedMessage("Hello", isUserMessage = true),
                                    ArchivedMessage("Hi!", isUserMessage = false),
                                ),
                        )
                    ),
            )

        val decoded = ChatArchiveJson.decode(ChatArchiveJson.encode(archive))

        assertEquals(archive, decoded)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val json =
            """
            {
              "version": 1,
              "exportedAt": 1,
              "newField": "ignored",
              "folders": [],
              "chats": [
                {
                  "name": "Chat",
                  "messages": [],
                  "futureOption": true
                }
              ]
            }
            """.trimIndent()

        val archive = ChatArchiveJson.decode(json)

        assertEquals(1, archive.chats.size)
        assertEquals("Chat", archive.chats.single().name)
    }

    @Test
    fun `empty archive encodes required version`() {
        val encoded = ChatArchiveJson.encode(ChatArchive())

        assertTrue(encoded.contains("\"version\": ${ChatArchive.CURRENT_VERSION}"))
        assertTrue(encoded.contains("\"chats\": []"))
    }
}
