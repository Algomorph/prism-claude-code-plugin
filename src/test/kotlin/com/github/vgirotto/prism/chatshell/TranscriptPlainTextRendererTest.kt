package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The no-JCEF fallback (R3) is the only transcript surface in IDEs without an embedded browser
 * (Android Studio), so these assert the same non-lossy contract the JCEF payload has: every
 * non-hidden block appears, and hidden-internal records never do.
 */
class TranscriptPlainTextRendererTest {

    private fun render(vararg messages: TranscriptMessage) =
        TranscriptPlainTextRenderer.render(messages.toList())

    @Test
    fun `renders role headers and visible text`() {
        val out = render(
            TranscriptMessage("m1", "user", blocks = listOf(TextBlock("hello"))),
            TranscriptMessage("m2", "assistant", blocks = listOf(TextBlock("hi back"))),
        )
        assertTrue(out.contains("▌ You"), out)
        assertTrue(out.contains("hello"), out)
        assertTrue(out.contains("▌ Claude"), out)
        assertTrue(out.contains("hi back"), out)
        assertTrue(out.indexOf("▌ You") < out.indexOf("▌ Claude"), "message order not preserved")
    }

    @Test
    fun `collapsed blocks render inline behind a labelled section`() {
        val out = render(
            TranscriptMessage(
                "m1", "assistant",
                blocks = listOf(
                    ThinkingBlock("step by step"),
                    ToolUseBlock("t1", "Read", "{\"file_path\":\"/x\"}"),
                    ToolResultBlock("t1", "file body", isError = false),
                )
            )
        )
        assertTrue(out.contains("▸ Thinking"), out)
        assertTrue(out.contains("    step by step"), out)
        assertTrue(out.contains("▸ Read"), out)
        assertTrue(out.contains("""    {"file_path":"/x"}"""), out)
        assertTrue(out.contains("▸ Output"), out)
        assertTrue(out.contains("    file body"), out)
    }

    @Test
    fun `tool result errors and truncation are marked`() {
        val out = render(
            TranscriptMessage(
                "m1", "assistant",
                blocks = listOf(ToolResultBlock("t1", "boom", isError = true, truncated = true))
            )
        )
        assertTrue(out.contains("▸ Output — error"), out)
        assertTrue(out.contains("…"), out)
    }

    @Test
    fun `hidden-internal records are never rendered`() {
        val out = render(
            TranscriptMessage("m1", "system", blocks = listOf(UnknownBlock("control", "{\"a\":1}"))),
            TranscriptMessage("m2", "user", blocks = listOf(TextBlock("visible"))),
        )
        assertFalse(out.contains("control"), out)
        assertFalse(out.contains("{\"a\":1}"), out)
        assertTrue(out.contains("visible"), out)
    }

    @Test
    fun `images report their media type and blocked images say so`() {
        val out = render(
            TranscriptMessage(
                "m1", "user",
                blocks = listOf(
                    ImageBlock("image/png", base64Data = "AAAA"),
                    ImageBlock("image/png"),
                )
            )
        )
        assertTrue(out.contains("[image: image/png]"), out)
        assertTrue(out.contains("[blocked image]"), out)
    }

    @Test
    fun `labels are injectable for i18n`() {
        val out = TranscriptPlainTextRenderer.render(
            listOf(TranscriptMessage("m1", "assistant", blocks = listOf(ThinkingBlock("x")))),
            TranscriptPlainTextRenderer.Labels(thinking = "Razonamiento"),
        )
        assertTrue(out.contains("▸ Razonamiento"), out)
    }

    @Test
    fun `empty transcript renders as empty string so the status line shows instead`() {
        assertEquals("", render())
        assertEquals(
            "",
            render(TranscriptMessage("m1", "system", blocks = listOf(UnknownBlock("x", "{}")))),
        )
    }
}
