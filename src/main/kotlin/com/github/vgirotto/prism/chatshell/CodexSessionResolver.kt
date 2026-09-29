package com.github.vgirotto.prism.chatshell

import com.google.gson.JsonParser
import java.io.File

/**
 * Resolves the on-disk Codex rollout file for the current project (design §11).
 *
 * Unlike Claude — where launching with `--session-id <id>` makes the transcript file exactly
 * `<id>.jsonl` — Codex takes no caller-supplied session id, so the rollout path is not known up
 * front. Codex writes to `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl` and records
 * the launch `cwd` in an early `session_meta` record. This resolver picks the
 * most-recently-modified rollout whose `session_meta.cwd` equals the project base.
 *
 * This is a **heuristic** (a second Codex session for the same project from another window
 * could race), deliberately mirroring the accepted `/resume` recency heuristic (§9): an
 * imperfect-but-useful binding beats none. The active session's file keeps the newest mtime as
 * it is appended, so a running session stays selected; a new session or a native resume creates
 * a newer file, which the controller's poller then rebinds to.
 */
class CodexSessionResolver(
    private val projectBasePath: String?,
    userHome: String = System.getProperty("user.home"),
) {
    val sessionsRoot: File = File(userHome, ".codex/sessions")

    /** The newest rollout file whose recorded `cwd` matches the project, or null if none. */
    fun newestForProject(): File? {
        val base = projectBasePath ?: return null
        if (!sessionsRoot.isDirectory) return null
        return sessionsRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }
            .filter { sessionCwd(it) == base }
            .maxByOrNull { it.lastModified() }
    }

    /** Reads `session_meta.cwd` from a rollout file, scanning only the first few records. */
    fun sessionCwd(file: File): String? = try {
        file.useLines { lines ->
            var found: String? = null
            for (line in lines.take(20)) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val json = try { JsonParser.parseString(t).asJsonObject } catch (_: Exception) { continue }
                if (json.get("type")?.asString == "session_meta") {
                    found = json.getAsJsonObject("payload")?.get("cwd")?.asString
                    break
                }
            }
            found
        }
    } catch (_: Exception) {
        null
    }
}
