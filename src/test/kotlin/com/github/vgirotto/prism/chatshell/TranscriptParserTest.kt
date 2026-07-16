package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptParserTest {

    private val parser = TranscriptParser()

    private fun fixture(name: String): List<TranscriptMessage> {
        val text = javaClass.getResourceAsStream("/transcripts/$name")!!
            .bufferedReader().readText()
        return parser.parseLines(text.lineSequence())
    }

    @Test
    fun `short session preserves ordered blocks without dropping tool results`() {
        val msgs = fixture("short-session.jsonl")
        assertEquals(11, msgs.size, "every record becomes a message (nothing dropped)")

        // First message: a plain user prompt (string content).
        assertEquals("user", msgs[0].role)
        assertTrue(msgs[0].blocks.single() is TextBlock)

        // Assistant turn: thinking (collapsed) + text + tool_use, in order.
        val a1 = msgs[1]
        assertEquals("assistant", a1.role)
        assertEquals("claude-opus-4-8", a1.model)
        assertTrue(a1.blocks[0] is ThinkingBlock)
        assertEquals(Visibility.COLLAPSED, a1.blocks[0].visibility)
        assertTrue(a1.blocks[1] is TextBlock)
        val toolUse = a1.blocks[2] as ToolUseBlock
        assertEquals("Read", toolUse.name)
        assertTrue(toolUse.inputJson.contains("file_path"), "tool input JSON is preserved")

        // The tool_result record is NOT dropped and links back to the tool_use id.
        val toolResult = msgs[2].blocks.filterIsInstance<ToolResultBlock>().single()
        assertEquals(toolUse.id, toolResult.toolUseId)
        assertFalse(toolResult.isError)
    }

    @Test
    fun `math delimiters survive intact`() {
        val msgs = fixture("short-session.jsonl")
        val mathText = msgs.flatMap { it.blocks }.filterIsInstance<TextBlock>()
            .first { it.markdown.contains("\\int_0^1") }
        assertTrue(mathText.markdown.contains("$$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$"))
        assertTrue(mathText.markdown.contains("inline \$\\int_0^1 x^2\\,dx\$"))
    }

    @Test
    fun `array tool_result yields text plus image plus tool_reference`() {
        val msgs = fixture("short-session.jsonl")
        val blocks = msgs.flatMap { it.blocks }
        assertTrue(blocks.any { it is ToolResultBlock })
        assertTrue(blocks.any { it is ImageBlock }, "base64 image block preserved")
        val toolRef = blocks.filterIsInstance<ToolReferenceBlock>().firstOrNull()
        assertNotNull(toolRef, "tool_reference block type preserved (not dropped)")
    }

    @Test
    fun `error tool_result is flagged`() {
        val msgs = fixture("short-session.jsonl")
        val err = msgs.flatMap { it.blocks }.filterIsInstance<ToolResultBlock>().first { it.isError }
        assertTrue(err.text.contains("failed"))
    }

    @Test
    fun `base64 image block captures media type and data`() {
        val msgs = fixture("short-session.jsonl")
        val img = msgs.flatMap { it.blocks }.filterIsInstance<ImageBlock>().first()
        assertEquals("image/png", img.mediaType)
        assertNotNull(img.base64Data)
    }

    @Test
    fun `unknown block and record types are preserved not dropped`() {
        val msgs = fixture("unknown-blocks.jsonl")
        val allBlocks = msgs.flatMap { it.blocks }
        // Future content block -> UnknownBlock (kept).
        assertTrue(allBlocks.any { it is UnknownBlock && it.rawType == "future_block_type_v99" })
        // Future top-level record type -> an internal message with a hidden UnknownBlock.
        val futureRecord = msgs.first { it.role == "future_record_type" }
        assertEquals(Visibility.HIDDEN_INTERNAL, futureRecord.blocks.single().visibility)
        // A normal assistant turn after the weirdness still parses.
        assertTrue(msgs.last().blocks.any { it is TextBlock })
    }

    @Test
    fun `compact boundary becomes a visible divider block`() {
        val msgs = fixture("compact-boundary.jsonl")
        val boundary = msgs.flatMap { it.blocks }.filterIsInstance<CompactBoundaryBlock>().single()
        assertEquals(Visibility.VISIBLE, boundary.visibility)
        // local_command system record is preserved but hidden.
        assertTrue(msgs.any { m -> m.role == "system" && m.blocks.any { it is UnknownBlock } })
    }

    @Test
    fun `internal system records are hidden but not dropped`() {
        val msgs = fixture("short-session.jsonl")
        val turnDuration = msgs.first { it.role == "system" }
        assertFalse(turnDuration.isRenderable, "turn_duration must not render")
        assertEquals(Visibility.HIDDEN_INTERNAL, turnDuration.blocks.single().visibility)
    }

    @Test
    fun `partial trailing line is skipped without losing prior messages`() {
        val text = javaClass.getResourceAsStream("/transcripts/short-session.jsonl")!!
            .bufferedReader().readText()
        val truncated = text + "{\"type\":\"assistant\",\"uuid\":\"partial\",\"message\":{\"content\":[{\"type\":\"te"
        val msgs = parser.parseLines(truncated.lineSequence())
        assertEquals(11, msgs.size, "the 11 complete records survive; the partial line is skipped")
    }

    @Test
    fun `large session parses every record`() {
        val msgs = fixture("large-session.jsonl")
        assertEquals(816, msgs.size)
    }
}
