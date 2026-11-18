package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * [selectsConversation] decides whether Prism may add `--session-id` to a Claude launch whose
 * configured command already carries arguments.
 */
class SelectsConversationTest {

    @Test
    fun `no arguments leave the conversation to Prism`() {
        assertFalse(selectsConversation(emptyList()))
    }

    @Test
    fun `unrelated arguments leave the conversation to Prism`() {
        assertFalse(selectsConversation(listOf("--plugin-dir", "/my plugins/local", "--model", "opus")))
    }

    @Test
    fun `continue and resume select the conversation`() {
        assertTrue(selectsConversation(listOf("-c")))
        assertTrue(selectsConversation(listOf("--continue")))
        assertTrue(selectsConversation(listOf("-r")))
        assertTrue(selectsConversation(listOf("--resume", "abc")))
    }

    @Test
    fun `an explicit session id selects the conversation, in either spelling`() {
        assertTrue(selectsConversation(listOf("--session-id", "0b7c9a3e-0000-4000-8000-000000000000")))
        assertTrue(selectsConversation(listOf("--session-id=0b7c9a3e-0000-4000-8000-000000000000")))
    }

    @Test
    fun `a value that merely looks like a flag is not mistaken for one`() {
        assertFalse(selectsConversation(listOf("--append-system-prompt", "use --continue carefully")))
    }
}
