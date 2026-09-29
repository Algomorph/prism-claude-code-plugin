package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Schema tests against the record shapes the CLIs actually write (sampled from real session
 * files: Claude Code 2.x conversations and Codex 0.146 rollouts).
 */
class ClaudeChatNameParserTest {

    @Test
    fun `a generated title is read from the tail`() {
        val tail = """
            {"type":"user","message":{"content":"hello"},"session_id":"S1"}
            {"type":"ai-title","aiTitle":"Add quick action buttons","sessionId":"C1"}
        """.trimIndent()

        assertEquals(
            ChatName("Add quick action buttons", ChatName.Origin.AGENT_TITLE),
            ClaudeChatNameParser.titleIn(tail),
        )
    }

    @Test
    fun `the newest generated title wins as the agent refines it`() {
        val tail = """
            {"type":"ai-title","aiTitle":"Debug something","sessionId":"C1"}
            {"type":"ai-title","aiTitle":"Fix the JCEF fallback","sessionId":"C1"}
        """.trimIndent()

        assertEquals("Fix the JCEF fallback", ClaudeChatNameParser.titleIn(tail)?.text)
    }

    @Test
    fun `a user-set title outranks a generated one regardless of order`() {
        // Claude keeps emitting ai-title records after a rename, so recency alone would flip the
        // tab back to the generated title on the next turn.
        val tail = """
            {"type":"custom-title","customTitle":"font-settings-menu","sessionId":"C1"}
            {"type":"ai-title","aiTitle":"Terminal font configuration work","sessionId":"C1"}
        """.trimIndent()

        assertEquals(
            ChatName("font-settings-menu", ChatName.Origin.USER_TITLE),
            ClaudeChatNameParser.titleIn(tail),
        )
    }

    @Test
    fun `a tail with no title record yields none`() {
        val tail = """{"type":"assistant","message":{"model":"opus"},"session_id":"S1"}"""
        assertNull(ClaudeChatNameParser.titleIn(tail))
    }

    @Test
    fun `a partial leading line is skipped rather than failing the read`() {
        // A byte-bounded tail almost always starts mid-record.
        val tail = "ent\":\"…\"},\"sessionId\":\"C1\"}\n" +
            """{"type":"ai-title","aiTitle":"Real title","sessionId":"C1"}"""

        assertEquals("Real title", ClaudeChatNameParser.titleIn(tail)?.text)
    }

    @Test
    fun `the newest session_id identifies the file a chat is writing to`() {
        val tail = """
            {"type":"user","sessionId":"C1","session_id":"S1"}
            {"type":"assistant","sessionId":"C2","session_id":"S2"}
        """.trimIndent()

        assertEquals("S2", ClaudeChatNameParser.latestSessionId(tail))
        assertNull(ClaudeChatNameParser.latestSessionId("""{"type":"ai-title","aiTitle":"x"}"""))
    }

    @Test
    fun `the first typed message is found for the turn before a title exists`() {
        val lines = sequenceOf(
            """{"type":"assistant","message":{"content":[]}}""",
            """{"type":"user","message":{"content":[{"type":"text","text":"Rebase the stack"}]}}""",
        )
        assertEquals("Rebase the stack", ClaudeChatNameParser.firstUserMessage(lines))
    }

    @Test
    fun `tool results and meta records are not treated as typed messages`() {
        val lines = sequenceOf(
            """{"type":"user","isMeta":true,"message":{"content":"Caveat: the messages below…"}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}""",
            """{"type":"user","message":{"content":"The real question"}}""",
        )
        assertEquals("The real question", ClaudeChatNameParser.firstUserMessage(lines))
    }

    @Test
    fun `a conversation with nothing typed yet has no first message`() {
        val lines = sequenceOf("""{"type":"mode","mode":"default"}""", "not json at all")
        assertNull(ClaudeChatNameParser.firstUserMessage(lines))
    }
}

class CodexChatNameParserTest {

    @Test
    fun `the first user message names the thread`() {
        val lines = sequenceOf(
            """{"type":"session_meta","payload":{"id":"019f","cwd":"/w/x"}}""",
            """{"type":"turn_context","payload":{"model":"gpt-5.6"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"Review this branch","images":null}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"And then this one"}}""",
        )
        assertEquals(
            ChatName("Review this branch", ChatName.Origin.FIRST_MESSAGE),
            ChatName.of(CodexChatNameParser.firstUserMessage(lines), ChatName.Origin.FIRST_MESSAGE),
        )
    }

    @Test
    fun `agent messages and other events are ignored`() {
        val lines = sequenceOf(
            """{"type":"event_msg","payload":{"type":"task_started"}}""",
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Sure, here goes"}}""",
            """{"type":"response_item","payload":{"type":"message","role":"user"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","text":"Legacy text field"}}""",
        )
        assertEquals("Legacy text field", CodexChatNameParser.firstUserMessage(lines))
    }

    @Test
    fun `a rollout with no typed message yet yields none`() {
        val lines = sequenceOf(
            """{"type":"session_meta","payload":{"id":"019f"}}""",
            """{"type":"event_msg","payload":{"type":"token_count","total":12}}""",
        )
        assertNull(CodexChatNameParser.firstUserMessage(lines))
    }

    @Test
    fun `sub-agent rollouts are recognized so their system prompts cannot name a tab`() {
        val subagent = sequenceOf(
            """{"type":"session_meta","payload":{"id":"019f","thread_source":"subagent","source":{"subagent":{"other":"guardian"}}}}""",
        )
        assertTrue(CodexChatNameParser.isSubagentThread(subagent))

        val user = sequenceOf(
            """{"type":"session_meta","payload":{"id":"019f","thread_source":"user","source":"cli"}}""",
        )
        assertFalse(CodexChatNameParser.isSubagentThread(user))
    }

    @Test
    fun `a rollout without the thread_source field counts as a user thread`() {
        // Older Codex builds omit it; declining to name those would be a regression.
        val legacy = sequenceOf("""{"type":"session_meta","payload":{"id":"019f","cwd":"/w/x"}}""")
        assertFalse(CodexChatNameParser.isSubagentThread(legacy))
        assertFalse(CodexChatNameParser.isSubagentThread(emptySequence()))
    }

    @Test
    fun `a malformed payload does not break the scan`() {
        val lines = sequenceOf(
            """{"type":"event_msg","payload":"not-an-object"}""",
            """{"type":"event_msg"}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"Still found"}}""",
        )
        assertEquals("Still found", CodexChatNameParser.firstUserMessage(lines))
    }
}
