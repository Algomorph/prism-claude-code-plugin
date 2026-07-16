package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LiveTranscriptSourceTest {

    private fun tempFile(): File = Files.createTempFile("prism-live", ".jsonl").toFile().apply { deleteOnExit() }

    private fun userLine(id: String, text: String) =
        """{"type":"user","uuid":"$id","timestamp":"2026-07-15T12:00:00.000Z","message":{"role":"user","content":"$text"}}"""

    private fun assistantLine(id: String, text: String) =
        """{"type":"assistant","uuid":"$id","timestamp":"2026-07-15T12:00:01.000Z","message":{"model":"m","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun `absent file is NoTranscriptYet, not Error`() {
        val src = LiveTranscriptSource(File("/no/such/dir/x.jsonl"))
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()
        assertTrue(states.any { it is TranscriptState.NoTranscriptYet })
        assertTrue(states.none { it is TranscriptState.Error })
    }

    @Test
    fun `first content delivers a windowed Ready, then appends deliver Appended`() {
        val f = tempFile()
        f.writeText(userLine("u1", "hello") + "\n" + assistantLine("a1", "hi") + "\n")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }

        src.pollOnce()
        val ready = states.filterIsInstance<TranscriptState.Ready>().single()
        assertEquals(2, ready.page.messages.size)
        assertEquals(0, ready.epoch)

        // Append a new turn.
        f.appendText(userLine("u2", "more") + "\n" + assistantLine("a2", "ok") + "\n")
        src.pollOnce()
        val appended = states.filterIsInstance<TranscriptState.Appended>().last()
        assertEquals(listOf("u2", "a2"), appended.messages.map { it.id })
        assertEquals(0, appended.epoch)
    }

    @Test
    fun `partial trailing line is not delivered until completed`() {
        val f = tempFile()
        f.writeText(userLine("u1", "hi") + "\n")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()
        // Append an incomplete line (no newline yet).
        f.appendText("{\"type\":\"assistant\",\"uuid\":\"a1\",\"message\":{\"content\":[{\"type\":\"te")
        src.pollOnce()
        assertTrue(states.filterIsInstance<TranscriptState.Appended>().isEmpty(),
            "no Appended until the line is complete")
        // Complete it.
        f.appendText("xt\",\"text\":\"done\"}]}}\n")
        src.pollOnce()
        assertEquals(listOf("a1"), states.filterIsInstance<TranscriptState.Appended>().last().messages.map { it.id })
    }

    @Test
    fun `in-place truncation bumps the epoch and re-delivers Ready`() {
        val f = tempFile()
        // A long initial file so a later short rewrite is genuinely shorter than the offset
        // (that shrink is what marks truncation; a fresh /clear session is a *new* file,
        // handled by SessionResolver repointing, not by same-file rotation).
        f.writeText(userLine("u1", "a reasonably long first user message to grow the file") + "\n" +
            assistantLine("a1", "and a reasonably long assistant reply as well here") + "\n")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()
        assertEquals(0, states.filterIsInstance<TranscriptState.Ready>().last().epoch)

        // Truncate the file to a much shorter content.
        f.writeText(userLine("u9", "x") + "\n")
        src.pollOnce()
        val ready = states.filterIsInstance<TranscriptState.Ready>().last()
        assertEquals(1, ready.epoch, "epoch bumps on truncation")
        assertEquals(listOf("u9"), ready.page.messages.map { it.id })
    }

    @Test
    fun `updating an existing message id re-upserts rather than duplicating`() {
        val f = tempFile()
        f.writeText(userLine("u1", "hi") + "\n")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()
        // A later record with the same uuid (streaming update).
        f.appendText(assistantLine("u1", "updated") + "\n")
        src.pollOnce()
        val appended = states.filterIsInstance<TranscriptState.Appended>().last()
        assertEquals(listOf("u1"), appended.messages.map { it.id })
    }
}
