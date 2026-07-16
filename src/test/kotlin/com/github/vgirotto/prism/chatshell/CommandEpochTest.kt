package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Command-epoch transcript behavior (design §8.5), using the Group 0 fixtures and the
 * confirmed JSONL representations:
 *   /compact -> a system/compact_boundary record appended to the SAME file: the divider
 *               renders inline and later turns keep flowing (no reset needed — prior turns
 *               remain valid in the file).
 *   /clear   -> a NEW session file: SessionResolver repoints, and a fresh source starts a
 *               fresh epoch (verified here by an in-place truncation, the same-file signal;
 *               a genuine new-file /clear is a repoint handled upstream).
 */
class CommandEpochTest {

    private val parser = TranscriptParser()

    private fun copyFixtureToTemp(name: String): File {
        val text = javaClass.getResourceAsStream("/transcripts/$name")!!.bufferedReader().readText()
        val f = Files.createTempFile("prism-epoch", ".jsonl").toFile().apply { deleteOnExit() }
        f.writeText(text)
        return f
    }

    @Test
    fun `compact renders a divider inline and keeps prior and later turns`() {
        val f = copyFixtureToTemp("compact-boundary.jsonl")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()

        val ready = states.filterIsInstance<TranscriptState.Ready>().single()
        val blocks = ready.page.messages.flatMap { it.blocks }
        // The compaction divider is present and visible…
        assertTrue(blocks.any { it is CompactBoundaryBlock })
        // …and the pre- and post-compaction user turns both survive.
        val userTexts = ready.page.messages.filter { it.role == "user" }
            .flatMap { it.blocks }.filterIsInstance<TextBlock>().map { it.markdown }
        assertTrue(userTexts.any { it.contains("before compaction") })
        assertTrue(userTexts.any { it.contains("after compaction") })
        // No epoch bump for an in-place /compact append.
        assertEquals(0, ready.epoch)
    }

    @Test
    fun `clear-style truncation starts a fresh epoch`() {
        val f = Files.createTempFile("prism-epoch", ".jsonl").toFile().apply { deleteOnExit() }
        f.writeText("""{"type":"user","uuid":"u1","message":{"role":"user","content":"a fairly long original message to grow the file well past the reset"}}""" + "\n")
        val src = LiveTranscriptSource(f)
        val states = mutableListOf<TranscriptState>()
        src.installListenerForTest { states.add(it) }
        src.pollOnce()
        assertEquals(0, states.filterIsInstance<TranscriptState.Ready>().last().epoch)

        f.writeText("""{"type":"user","uuid":"u2","message":{"role":"user","content":"new"}}""" + "\n")
        src.pollOnce()
        assertEquals(1, states.filterIsInstance<TranscriptState.Ready>().last().epoch,
            "a fresh epoch after the transcript is reset")
    }

    @Test
    fun `local_command system records are preserved but hidden`() {
        val msgs = parser.parseFile(copyFixtureToTemp("compact-boundary.jsonl"))
        val local = msgs.first { m -> m.role == "system" && m.blocks.any { it is UnknownBlock } }
        assertEquals(Visibility.HIDDEN_INTERNAL, local.blocks.first().visibility)
    }
}
