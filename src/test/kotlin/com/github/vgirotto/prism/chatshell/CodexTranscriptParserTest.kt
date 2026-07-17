package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexTranscriptParserTest {

    private val parser = CodexTranscriptParser()

    private fun fixture(name: String): List<TranscriptMessage> {
        val text = javaClass.getResourceAsStream("/transcripts/$name")!!
            .bufferedReader().readText()
        return parser.parseLines(text.lineSequence())
    }

    @Test
    fun `every record becomes a message, nothing dropped`() {
        val msgs = fixture("codex-session.jsonl")
        assertEquals(13, msgs.size, "one message per record (hidden ones retained, R7)")
    }

    @Test
    fun `user turn comes from event_msg, assistant from response_item`() {
        val msgs = fixture("codex-session.jsonl")
        val user = msgs.first { it.role == "user" && it.blocks.any { b -> b is TextBlock } }
        assertEquals("hello codex", (user.blocks.single() as TextBlock).markdown)

        val asst = msgs.first { it.role == "assistant" && it.blocks.any { b -> b is TextBlock } }
        assertEquals("Here is the answer.", (asst.blocks.single() as TextBlock).markdown)
    }

    @Test
    fun `duplicate response_item user and developer preamble are not rendered`() {
        val msgs = fixture("codex-session.jsonl")
        // Exactly one renderable user text block — the response_item[role=user] copy (carrying
        // AGENTS.md context) and the developer preamble are hidden.
        val renderableUserTexts = msgs.filter { it.role == "user" }
            .flatMap { it.blocks }.filterIsInstance<TextBlock>()
        assertEquals(1, renderableUserTexts.size)
        // No rendered text mentions the injected AGENTS.md context.
        assertTrue(msgs.flatMap { it.blocks }.filterIsInstance<TextBlock>().none { it.markdown.contains("AGENTS.md") })
    }

    @Test
    fun `agent_message event duplicate is not rendered`() {
        val msgs = fixture("codex-session.jsonl")
        val assistantTexts = msgs.filter { it.role == "assistant" }
            .flatMap { it.blocks }.filterIsInstance<TextBlock>()
        assertEquals(1, assistantTexts.size, "assistant text comes only from response_item/message")
    }

    @Test
    fun `custom tool call and output become tool blocks linked by call id`() {
        val msgs = fixture("codex-session.jsonl")
        val toolUse = msgs.flatMap { it.blocks }.filterIsInstance<ToolUseBlock>().single()
        assertEquals("exec", toolUse.name)
        assertEquals("call_1", toolUse.id)
        assertTrue(toolUse.inputJson.contains("ls -la"))

        val toolResult = msgs.flatMap { it.blocks }.filterIsInstance<ToolResultBlock>().single()
        assertEquals("call_1", toolResult.toolUseId)
        assertTrue(toolResult.text.contains("file1.txt"))
    }

    @Test
    fun `ANSI escapes are stripped from tool output`() {
        val msgs = fixture("codex-session.jsonl")
        val toolResult = msgs.flatMap { it.blocks }.filterIsInstance<ToolResultBlock>().single()
        assertFalse(toolResult.text.contains("\u001B"), "no raw escape survives")
        assertTrue(toolResult.text.startsWith("file1.txt"), "dim codes around file1.txt removed")
    }

    @Test
    fun `encrypted reasoning yields no block but a summary reasoning renders as thinking`() {
        val msgs = fixture("codex-session.jsonl")
        val thinking = msgs.flatMap { it.blocks }.filterIsInstance<ThinkingBlock>()
        assertEquals(1, thinking.size, "only the reasoning with a plaintext summary renders")
        assertEquals("Thinking about it.", thinking.single().text)
    }

    @Test
    fun `compacted record becomes a visible divider`() {
        val msgs = fixture("codex-session.jsonl")
        val boundary = msgs.flatMap { it.blocks }.filterIsInstance<CompactBoundaryBlock>().single()
        assertEquals(Visibility.VISIBLE, boundary.visibility)
        assertEquals("system", msgs.first { it.blocks.any { b -> b is CompactBoundaryBlock } }.role)
    }

    @Test
    fun `internal records are retained but hidden`() {
        val msgs = fixture("codex-session.jsonl")
        // session_meta, turn_context, token_count, developer/user messages, encrypted reasoning,
        // agent_message: all present but non-renderable.
        val hidden = msgs.filterNot { it.isRenderable }
        assertTrue(hidden.size >= 6, "the plumbing records are kept but not shown")
    }

    @Test
    fun `partial trailing line is skipped without losing prior records`() {
        val text = javaClass.getResourceAsStream("/transcripts/codex-session.jsonl")!!
            .bufferedReader().readText()
        val truncated = text + "{\"type\":\"response_item\",\"payload\":{\"type\":\"mess"
        val msgs = parser.parseLines(truncated.lineSequence())
        assertEquals(13, msgs.size, "the 13 complete records survive; the partial line is skipped")
    }
}
