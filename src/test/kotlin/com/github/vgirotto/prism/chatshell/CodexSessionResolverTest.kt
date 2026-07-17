package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CodexSessionResolverTest {

    private fun rollout(dir: File, name: String, cwd: String, mtime: Long): File {
        val f = File(dir, name)
        f.parentFile.mkdirs()
        f.writeText(
            """{"timestamp":"2026-07-17T14:29:58Z","type":"session_meta","payload":{"id":"$name","cwd":"$cwd"}}""" + "\n" +
                """{"timestamp":"2026-07-17T14:30:00Z","type":"event_msg","payload":{"type":"user_message","message":"hi"}}""" + "\n"
        )
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun `picks the newest rollout whose cwd matches the project`(@TempDir tmp: File) {
        val sessions = File(tmp, ".codex/sessions/2026/07/17")
        rollout(sessions, "rollout-old.jsonl", "/home/dev/proj", 1_000L)
        val newest = rollout(sessions, "rollout-new.jsonl", "/home/dev/proj", 5_000L)
        rollout(sessions, "rollout-other.jsonl", "/home/dev/other", 9_000L) // newer but wrong cwd

        val resolver = CodexSessionResolver("/home/dev/proj", userHome = tmp.absolutePath)
        assertEquals(newest.absolutePath, resolver.newestForProject()?.absolutePath)
    }

    @Test
    fun `returns null when no rollout matches the project cwd`(@TempDir tmp: File) {
        val sessions = File(tmp, ".codex/sessions/2026/07/17")
        rollout(sessions, "rollout-a.jsonl", "/somewhere/else", 1_000L)

        val resolver = CodexSessionResolver("/home/dev/proj", userHome = tmp.absolutePath)
        assertNull(resolver.newestForProject())
    }

    @Test
    fun `returns null when the sessions root is absent`(@TempDir tmp: File) {
        val resolver = CodexSessionResolver("/home/dev/proj", userHome = tmp.absolutePath)
        assertNull(resolver.newestForProject(), "no ~/.codex/sessions yet is a normal, non-error state")
    }

    @Test
    fun `reads the recorded cwd from session_meta`(@TempDir tmp: File) {
        val sessions = File(tmp, ".codex/sessions/2026/07/17")
        val f = rollout(sessions, "rollout-x.jsonl", "/home/dev/proj", 1_000L)
        val resolver = CodexSessionResolver("/home/dev/proj", userHome = tmp.absolutePath)
        assertEquals("/home/dev/proj", resolver.sessionCwd(f))
    }
}
