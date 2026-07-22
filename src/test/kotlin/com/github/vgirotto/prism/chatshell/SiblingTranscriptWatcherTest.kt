package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Drives [SiblingTranscriptWatcher.pollOnce] synchronously (no background executor) to pin the
 * `/resume` follow behavior. The watcher is a **hard signal**, not a heuristic: a chat launched
 * with `--session-id S` is currently writing to the `.jsonl` whose newest content line carries
 * snake-case `"session_id":"S"`, regardless of the file's own conversation id.
 */
class SiblingTranscriptWatcherTest {

    @TempDir lateinit var dir: Path

    /** Write `<convId>.jsonl` whose content line stamps [sessionId] (snake-case), at [mtime]. */
    private fun transcript(convId: String, sessionId: String?, mtime: Long, extra: String = ""): File =
        File(dir.toFile(), "$convId.jsonl").apply {
            val sid = if (sessionId != null) "\"session_id\":\"$sessionId\"," else "\"session_id\":null,"
            writeText("""{"type":"assistant",$sid"sessionId":"$convId"}$extra""" + "\n")
            setLastModified(mtime)
        }

    private fun watcher(
        launch: String,
        bound: String,
        active: Boolean = true,
    ): Pair<SiblingTranscriptWatcher, MutableList<String>> {
        val switches = mutableListOf<String>()
        val w = SiblingTranscriptWatcher(
            dir = dir.toFile(),
            launchSessionId = launch,
            boundConvId = { bound },
            onSwitch = { switches.add(it) },
            isActive = { active },
        )
        return w to switches
    }

    @Test
    fun `our own file is the only match so nothing switches`() {
        transcript("S", sessionId = "S", mtime = 1_000)
        val (w, switches) = watcher(launch = "S", bound = "S")
        w.pollOnce()
        assertEquals(emptyList<String>(), switches, "current == bound must not switch")
    }

    @Test
    fun `a resumed conversation carrying our launch id triggers a switch`() {
        // Launch file stays tiny (only the mode records; no session_id content).
        File(dir.toFile(), "S.jsonl").apply { writeText("{\"type\":\"mode\"}\n"); setLastModified(1_000) }
        // /resume into Y: Y.jsonl already holds its history and now stamps our session_id.
        transcript("Y", sessionId = "S", mtime = 2_000)
        val (w, switches) = watcher(launch = "S", bound = "S")
        w.pollOnce()
        assertEquals(listOf("Y"), switches, "must follow the file carrying our launch session id")
    }

    @Test
    fun `a concurrent chat's file never triggers a switch`() {
        // The cross-talk regression: chat #1 (launch S1) shares the dir with chat #2 (launch S2).
        // Chat #2 writes/resumes — its files carry session_id S2, never S1 — so chat #1's watcher
        // must stay put no matter which of chat #2's files is newest.
        transcript("S1", sessionId = "S1", mtime = 1_000)          // chat #1's own file
        transcript("S2", sessionId = "S2", mtime = 3_000)          // chat #2 fresh, newer
        transcript("Y2", sessionId = "S2", mtime = 4_000)          // chat #2 resumed, newest
        val (w, switches) = watcher(launch = "S1", bound = "S1")
        w.pollOnce()
        assertEquals(emptyList<String>(), switches, "another chat's session id must never hijack us")
    }

    @Test
    fun `the newest file carrying our launch id wins across successive resumes`() {
        File(dir.toFile(), "S.jsonl").apply { writeText("{\"type\":\"mode\"}\n"); setLastModified(1_000) }
        transcript("Y", sessionId = "S", mtime = 2_000)            // first resume
        transcript("Z", sessionId = "S", mtime = 5_000)            // later resume, newest
        val (w, switches) = watcher(launch = "S", bound = "Y")
        w.pollOnce()
        assertEquals(listOf("Z"), switches)
    }

    @Test
    fun `a null session_id line is not a match`() {
        transcript("S", sessionId = "S", mtime = 1_000)
        transcript("Q", sessionId = null, mtime = 9_000)           // newer but unstamped
        val (w, switches) = watcher(launch = "S", bound = "S")
        w.pollOnce()
        assertEquals(emptyList<String>(), switches, "unstamped files must be ignored")
    }

    @Test
    fun `no file carrying our launch id yields no switch`() {
        transcript("A", sessionId = "other", mtime = 1_000)
        transcript("B", sessionId = "other", mtime = 2_000)
        val (w, switches) = watcher(launch = "S", bound = "S")
        w.pollOnce()
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `an inactive watcher never switches`() {
        transcript("Y", sessionId = "S", mtime = 2_000)
        val (w, switches) = watcher(launch = "S", bound = "S", active = false)
        w.pollOnce()
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `the newest session_id line in a growing file is the one that counts`() {
        // A file that was chat #2's then got resumed away: its tail's latest session_id is what
        // matters. Here the newest stamp is ours, so it is our current conversation.
        transcript("Y", sessionId = "old", mtime = 1_000,
            extra = "\n{\"type\":\"assistant\",\"session_id\":\"S\",\"sessionId\":\"Y\"}")
        val (w, switches) = watcher(launch = "S", bound = "S")
        w.pollOnce()
        assertEquals(listOf("Y"), switches)
    }
}
