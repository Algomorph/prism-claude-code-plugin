package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Detects an in-terminal `/resume` (or the toolbar Resume button, which just types `/resume`
 * — see design §9, R2) so the transcript can rebind to the conversation the user switched to.
 *
 * Prism never sees the CLI's interactive picker choice, so this is a **filesystem heuristic**,
 * not a hard signal: when a sibling `<id>.jsonl` in the project dir starts *actively growing*
 * and overtakes our bound file, that sibling is the resumed conversation.
 *
 * To avoid a spurious rebind at startup (the dir is full of old conversations, and every
 * Prism tab is itself a live Claude session writing here), a sibling only qualifies once it
 * has grown **since we began watching** — a baseline of mtimes is snapshotted on the first
 * poll and after each rebind, and a file counts as "active" only if it is new or its mtime
 * has advanced past that baseline. Pre-existing idle conversations never trigger a switch.
 *
 * Known limitation (accepted, §9): if two visible tabs race a `/resume` within one poll
 * window, or another Claude session in the same project writes concurrently, the wrong
 * sibling can win. Only the *selected* tab watches ([isActive]), which removes the common
 * case; the residual race is left for a later hard-signal fix.
 */
class SiblingTranscriptWatcher(
    private val dir: File,
    private val boundConvId: () -> String,
    private val isActive: () -> Boolean,
    private val onSwitch: (String) -> Unit,
    private val pollIntervalMs: Long = 500,
) : Disposable {

    /** mtimes at the start of the current watch window; files absent here are "new". */
    private var baseline: Map<String, Long> = emptyMap()
    private var baselineTaken = false
    @Volatile private var disposed = false
    private var future: ScheduledFuture<*>? = null

    fun start(): SiblingTranscriptWatcher {
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { try { pollOnce() } catch (_: Exception) { /* transient FS errors are non-fatal */ } },
            0, pollIntervalMs, TimeUnit.MILLISECONDS
        )
        return this
    }

    /** Re-snapshot the baseline so only growth *after this point* can trigger the next switch. */
    @Synchronized
    fun resetBaseline() {
        baseline = currentMtimes()
        baselineTaken = true
    }

    /** One detection cycle. Synchronous and synchronized so it is unit-testable. */
    @Synchronized
    fun pollOnce() {
        if (disposed) return
        val now = currentMtimes()
        if (!baselineTaken) { baseline = now; baselineTaken = true; return }
        if (!isActive()) return

        val boundName = "${boundConvId()}.jsonl"
        val boundMtime = now[boundName] ?: 0L

        // The most recently touched sibling that has actually grown since baseline.
        val candidate = now.entries
            .filter { (name, _) -> name != boundName }
            .filter { (name, mtime) -> mtime > (baseline[name] ?: 0L) } // new or advanced = active
            .maxByOrNull { it.value }
            ?: return

        // It must be more current than our own file to count as "the user moved on".
        if (candidate.value <= boundMtime) return

        val convId = candidate.key.removeSuffix(".jsonl")
        resetBaseline() // adopt the switch point; the next /resume must grow past here
        onSwitch(convId)
    }

    private fun currentMtimes(): Map<String, Long> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.associate { it.name to it.lastModified() }
            ?: emptyMap()

    override fun dispose() {
        disposed = true
        future?.cancel(false)
    }
}
