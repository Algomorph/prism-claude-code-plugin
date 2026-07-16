package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class TranscriptPayloadBuilderTest {

    private val builder = TranscriptPayloadBuilder()

    private fun pngBase64(): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `blocks map to render blocks with correct kinds and visibility`() {
        val msg = TranscriptMessage(
            "m1", "assistant", null, "claude-opus-4-8",
            listOf(
                TextBlock("hello \$x^2\$"),
                ThinkingBlock("secret reasoning"),
                ToolUseBlock("t1", "Read", "{\"file_path\":\"/x\"}"),
                ToolResultBlock("t1", "output", isError = false),
                ToolReferenceBlock("Bash"),
            )
        )
        val payload = builder.buildPayload(msg)
        assertEquals("Claude", payload.roleLabel)
        assertEquals(listOf("text", "thinking", "toolUse", "toolResult", "toolReference"),
            payload.blocks.map { it.kind })
        assertEquals("collapsed", payload.blocks[1].visibility) // thinking
        assertEquals("collapsed", payload.blocks[2].visibility) // tool_use (disclosure)
        assertEquals("collapsed", payload.blocks[3].visibility) // tool_result (disclosure)
        assertEquals("collapsed", payload.blocks[4].visibility) // tool_reference
        assertEquals("hello \$x^2\$", payload.blocks[0].markdown) // math delimiters intact
    }

    @Test
    fun `base64 image is resolved to a data uri in the payload`() {
        val msg = TranscriptMessage("m", "user", null, null, listOf(ImageBlock("image/png", pngBase64())))
        val block = builder.buildPayload(msg).blocks.single()
        assertEquals("image", block.kind)
        assertTrue(block.imageDataUri?.startsWith("data:image/png;base64,") == true)
    }

    @Test
    fun `svg image becomes a blocked marker with no data uri`() {
        val msg = TranscriptMessage("m", "user", null, null,
            listOf(ImageBlock("image/svg+xml", Base64.getEncoder().encodeToString("<svg/>".toByteArray()))))
        val block = builder.buildPayload(msg).blocks.single()
        assertEquals("image", block.kind)
        assertEquals(null, block.imageDataUri)
        assertEquals("[blocked image]", block.imageAlt)
    }

    @Test
    fun `payload serializes to data never html`() {
        val msg = TranscriptMessage("m", "assistant", null, null,
            listOf(TextBlock("<b>not markup</b> and \$\\int\$")))
        val delta = builder.buildDelta(listOf(msg), epoch = 0, revision = 1)
        val json = TranscriptCodec.encodeDeltaJson(delta)
        assertFalse(json.contains("\"html\""))
        assertTrue(json.contains("markdown"))
        // The hostile string travels as data (markdown), to be sanitized in the browser.
        assertTrue(json.contains("not markup"))
    }

    @Test
    fun `buildDelta resets then upserts renderable messages and omits hidden-only ones`() {
        val visible = TranscriptMessage("v", "assistant", null, null, listOf(TextBlock("hi")))
        val hiddenOnly = TranscriptMessage("h", "system", null, null,
            listOf(UnknownBlock("turn_duration", "{}", Visibility.HIDDEN_INTERNAL)))
        val delta = builder.buildDelta(listOf(visible, hiddenOnly), 0, 1)
        assertEquals("reset", delta.operations.first().op)
        val upserts = delta.operations.filter { it.op == "upsert" }
        assertEquals(1, upserts.size, "the all-hidden system message is omitted from the payload")
        assertEquals("v", upserts.single().id)
    }

    @Test
    fun `compact boundary maps to a visible divider block`() {
        val msg = TranscriptMessage("c", "system", null, null, listOf(CompactBoundaryBlock("compacted")))
        val block = builder.buildPayload(msg).blocks.single()
        assertEquals("compactBoundary", block.kind)
        assertEquals("visible", block.visibility)
        assertEquals("compacted", block.label)
    }
}
