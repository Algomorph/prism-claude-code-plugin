package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Follows an in-terminal `/resume` (or the toolbar Resume button, which just types `/resume`
 * — design §9, R2) so the transcript can rebind to the conversation the user switched to.
 *
 * ## Hard signal, not a guess
 *
 * A chat is launched with `--session-id <launchSessionId>`, and *every* real content line
 * Claude writes carries that id in a snake-case `"session_id"` field — even after a `/resume`,
 * when the lines are appended to a *different* conversation file:
 *
 * ```
 * // fresh chat, file <S>.jsonl:      "sessionId":"<S>", "session_id":"<S>"
 * // after /resume into <Y>, file <Y>.jsonl: "sessionId":"<Y>", "session_id":"<S>"
 * ```
 *
 * So the conversation a chat is *currently* writing to is simply the `.jsonl` whose most
 * recent `session_id`-bearing line equals our [launchSessionId]. That makes rebind
 * **deterministic and per-chat**: chat A only ever matches its own unique launch id, so a
 * concurrent chat B writing (or resuming) in the same project dir can never hijack A's
 * transcript — the cross-talk the old mtime-overtake heuristic allowed is now structurally
 * impossible.
 *
 * Note the resumed conversation's file already holds its full history (it was written when the
 * conversation was originally created), so once `session_id` first appears in it — on the first
 * post-resume turn — rebinding shows the complete transcript. Claude does not stamp
 * `session_id` until that first turn, so a just-resumed, not-yet-prompted chat still shows its
 * (empty) launch file until then; that is inherent to when the CLI writes the marker.
 */
class SiblingTranscriptWatcher(
    private val dir: File,
    private val launchSessionId: String,
    private val boundConvId: () -> String,
    private val onSwitch: (String) -> Unit,
    private val isActive: () -> Boolean = { true },
    private val pollIntervalMs: Long = 500,
) : Disposable {

    @Volatile private var disposed = false
    private var future: ScheduledFuture<*>? = null

    fun start(): SiblingTranscriptWatcher {
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { try { pollOnce() } catch (_: Exception) { /* transient FS errors are non-fatal */ } },
            0, pollIntervalMs, TimeUnit.MILLISECONDS
        )
        return this
    }

    /** One detection cycle. Synchronous and synchronized so it is unit-testable. */
    @Synchronized
    fun pollOnce() {
        if (disposed || !isActive()) return

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return

        // Our current conversation is the most recently touched file whose latest content line
        // carries our launch session id. Read newest-first and stop at the first match so we
        // touch as few file tails as possible.
        val current = files
            .sortedByDescending { it.lastModified() }
            .firstOrNull { latestSessionId(it) == launchSessionId }
            ?.name?.removeSuffix(".jsonl")
            ?: return

        if (current != boundConvId()) onSwitch(current)
    }

    /**
     * The snake-case `session_id` of the most recent line that has one, read from the file's
     * tail only (these files can be tens of MB). Returns null if the tail carries none.
     */
    private fun latestSessionId(file: File): String? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len == 0L) return null
                val window = minOf(len, TAIL_BYTES)
                raf.seek(len - window)
                val bytes = ByteArray(window.toInt())
                raf.readFully(bytes)
                SESSION_ID_RE.findAll(String(bytes, Charsets.UTF_8)).lastOrNull()?.groupValues?.get(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun dispose() {
        disposed = true
        future?.cancel(false)
    }

    private companion object {
        /** How many trailing bytes of each transcript to scan for the newest `session_id`. */
        const val TAIL_BYTES = 64L * 1024

        /** Matches `"session_id":"<value>"`; a `null` value simply doesn't match. */
        val SESSION_ID_RE = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
    }
}
