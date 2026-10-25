package io.shubham0204.smollmandroid.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTextFormattingTest {
    @Test
    fun `raw think block is styled for display`() {
        val response = "<think>Let me check.</think>The answer is 42."

        assertEquals(
            "<blockquote><i><h6>Let me check.</i></h6></blockquote>The answer is 42.",
            response.withThinkBlocksForDisplay(),
        )
    }

    @Test
    fun `plain response is unchanged for display`() {
        val response = "The answer is **42**."

        assertEquals(response, response.withThinkBlocksForDisplay())
    }

    @Test
    fun `copied raw response omits think block`() {
        val response = "  <think>Let me check.</think>The answer is 42.  "

        assertEquals("The answer is 42.", response.stripThinkingForClipboard())
    }

    @Test
    fun `copied legacy response omits rendered think block`() {
        val response =
            "<blockquote><i><h6>Let me check.</i></h6></blockquote>The answer is 42."

        assertEquals("The answer is 42.", response.stripThinkingForClipboard())
    }

    @Test
    fun `empty gemma thought channel is removed from display`() {
        val response = "<|channel>thought\n<channel|>*muffled happy sound*"

        assertEquals(
            "*muffled happy sound*",
            response.withThinkBlocksForDisplay(),
        )
    }

    @Test
    fun `gemma thought channel is styled for display`() {
        val response = "<|channel>thought\nLet me check.\n<channel|>The answer is 42."

        assertEquals(
            "<blockquote><i><h6>Let me check.</i></h6></blockquote>The answer is 42.",
            response.withThinkBlocksForDisplay(),
        )
    }

    @Test
    fun `copied response omits gemma thought channel`() {
        val response = "<|channel>thought\nLet me check.\n<channel|>The answer is 42."

        assertEquals("The answer is 42.", response.stripThinkingForClipboard())
    }
}
