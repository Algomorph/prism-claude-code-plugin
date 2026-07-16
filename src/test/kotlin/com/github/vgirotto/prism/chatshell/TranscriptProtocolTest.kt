package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class TranscriptProtocolTest {

    @Test
    fun `delta encodes to base64 of json and round-trips`() {
        val delta = TranscriptDelta(
            epoch = 2, revision = 5,
            operations = listOf(
                DeltaOp.upsert(
                    "msg-1",
                    BlockPayload(
                        role = "assistant", roleLabel = "Claude",
                        blocks = listOf(RenderBlock(kind = "text", markdown = "hello \$x^2\$"))
                    )
                ),
                DeltaOp.remove("msg-0"),
                DeltaOp.reset(),
            )
        )
        val b64 = TranscriptCodec.encodeDelta(delta)
        val json = String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)

        assertTrue(json.contains("\"epoch\":2"))
        assertTrue(json.contains("\"revision\":5"))
        assertTrue(json.contains("\"op\":\"upsert\""))
        assertTrue(json.contains("\"op\":\"remove\""))
        assertTrue(json.contains("\"op\":\"reset\""))
        assertTrue(json.contains("\"markdown\":\"hello \$x^2\$\""))
    }

    @Test
    fun `null fields are omitted so payloads stay minimal`() {
        val json = TranscriptCodec.encodeDeltaJson(
            TranscriptDelta(1, 1, listOf(DeltaOp.upsert("m", BlockPayload("user"))))
        )
        // A text-less user payload must not carry markdown/toolName/etc keys.
        assertFalse(json.contains("markdown"))
        assertFalse(json.contains("toolName"))
        assertFalse(json.contains("imageDataUri"))
        assertFalse(json.contains("\"id\":null"))
    }

    @Test
    fun `payload never carries an html field - Kotlin emits data not markup`() {
        val json = TranscriptCodec.encodeDeltaJson(
            TranscriptDelta(
                1, 1,
                listOf(DeltaOp.upsert("m", BlockPayload("assistant", blocks = listOf(
                    RenderBlock(kind = "text", markdown = "<b>not markup</b>")
                ))))
            )
        )
        // The hostile-looking string is carried verbatim as *data* (markdown), never as
        // an "html" field — the browser sanitizes it.
        assertFalse(json.contains("\"html\""))
        assertTrue(json.contains("markdown"))
    }

    @Test
    fun `ack decodes and rejects garbage`() {
        val ack = TranscriptCodec.decodeAck("{\"epoch\":3,\"revision\":7,\"status\":\"ok\"}")
        assertEquals(3, ack?.epoch)
        assertEquals(7, ack?.revision)
        assertEquals("ok", ack?.status)
        // Not-json returns null rather than throwing.
        org.junit.jupiter.api.Assertions.assertNull(TranscriptCodec.decodeAck("]]not json["))
    }

    @Test
    fun `link scheme allowlist accepts http(s) and rejects dangerous schemes`() {
        assertTrue(TranscriptView.isSafeExternalLink("https://example.com/x"))
        assertTrue(TranscriptView.isSafeExternalLink("http://example.com"))
        assertFalse(TranscriptView.isSafeExternalLink("javascript:alert(1)"))
        assertFalse(TranscriptView.isSafeExternalLink("file:///etc/passwd"))
        assertFalse(TranscriptView.isSafeExternalLink("vbscript:x"))
        assertFalse(TranscriptView.isSafeExternalLink("data:text/html,<script>"))
    }
}
