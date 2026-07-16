package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Drives [SiblingTranscriptWatcher.pollOnce] synchronously (no background executor) to pin the
 * `/resume` switch heuristic (§9): only a sibling that grows *after* watching began and
 * overtakes the bound file counts, and only while the tab is active.
 */
class SiblingTranscriptWatcherTest {

    @TempDir lateinit var dir: Path

    private fun jsonl(id: String, mtime: Long, text: String = "x"): File =
        File(dir.toFile(), "$id.jsonl").apply { writeText(text); setLastModified(mtime) }

    private fun watcher(bound: String, active: Boolean = true): Pair<SiblingTranscriptWatcher, MutableList<String>> {
        val switches = mutableListOf<String>()
        val w = SiblingTranscriptWatcher(
            dir = dir.toFile(),
            boundConvId = { bound },
            isActive = { active },
            onSwitch = { switches.add(it) },
        )
        return w to switches
    }

    @Test
    fun `first poll only snapshots baseline and never switches`() {
        jsonl("bound", 1_000)
        jsonl("old", 5_000) // newer, but pre-existing
        val (w, switches) = watcher("bound")
        w.pollOnce()
        assertEquals(emptyList<String>(), switches, "baseline poll must not act")
    }

    @Test
    fun `pre-existing idle siblings never trigger a switch`() {
        jsonl("bound", 1_000)
        val old = jsonl("old", 9_000)
        val (w, switches) = watcher("bound")
        w.pollOnce() // baseline
        w.pollOnce() // still idle
        old.setLastModified(9_000) // unchanged
        w.pollOnce()
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `a sibling that grows past baseline and overtakes bound triggers a switch`() {
        jsonl("bound", 1_000)
        val resumed = jsonl("resumed", 500) // exists but older than bound at baseline
        val (w, switches) = watcher("bound")
        w.pollOnce() // baseline
        // User /resumes into `resumed`: it grows and overtakes the bound file.
        resumed.writeText("x".repeat(50)); resumed.setLastModified(2_000)
        w.pollOnce()
        assertEquals(listOf("resumed"), switches)
    }

    @Test
    fun `the bound file growing does not trigger a switch`() {
        val bound = jsonl("bound", 1_000)
        jsonl("other", 500)
        val (w, switches) = watcher("bound")
        w.pollOnce()
        bound.setLastModified(3_000) // our own conversation advancing
        w.pollOnce()
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `an inactive (background) tab never switches`() {
        jsonl("bound", 1_000)
        val resumed = jsonl("resumed", 500)
        val (w, switches) = watcher("bound", active = false)
        w.pollOnce()
        resumed.setLastModified(9_000)
        w.pollOnce()
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `after a switch the baseline resets so it does not immediately re-fire`() {
        jsonl("bound", 1_000)
        val resumed = jsonl("resumed", 500)
        val (w, switches) = watcher("bound")
        w.pollOnce()
        resumed.setLastModified(2_000)
        w.pollOnce() // fires once
        w.pollOnce() // resumed still 2_000, not grown past the new baseline
        assertEquals(listOf("resumed"), switches, "no repeat fire without further growth")
    }
}
