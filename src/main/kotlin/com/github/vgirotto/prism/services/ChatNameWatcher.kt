package com.github.vgirotto.prism.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Polls a [ChatNameSource] in the background and reports the chat's name whenever it improves.
 *
 * A name is not available when a chat starts — Claude generates its title after the first
 * assistant turn, and Codex has nothing to derive one from until the user has typed — so a tab
 * necessarily opens as `Chat #N` and is renamed once the CLI has recorded something. Polling
 * (rather than watching) matches how the rest of Prism reads these files, and at this cadence a
 * title that changes mid-session is picked up without the reader ever being hot.
 *
 * ### Never downgrade
 *
 * A resolved name is only replaced by one at least as authoritative (see [ChatName.Origin]). Two
 * things make this necessary rather than cosmetic:
 *
 *  - the tail window is bounded, so a busy turn can push the title records out of view for a
 *    poll or two — without this the tab would fall back to the first-message name and bounce;
 *  - Claude keeps writing `ai-title` records after the user renames a chat, so a generated title
 *    must never win back a tab the user has named.
 *
 * Same-rank changes *are* applied: a retitle is exactly what should show up.
 */
class ChatNameWatcher(
    private val source: ChatNameSource,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : Disposable {

    @Volatile private var disposed = false
    private var future: ScheduledFuture<*>? = null

    /** The best name resolved so far; the floor that [pollOnce] refuses to fall below. */
    @Volatile var current: ChatName? = null
        private set

    /**
     * Start polling. [onName] is invoked on the EDT, only when the name actually changed, and
     * never after [dispose].
     */
    fun start(onName: (ChatName) -> Unit): ChatNameWatcher {
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                val resolved = try {
                    pollOnce()
                } catch (_: Exception) {
                    null // Transient FS errors are non-fatal; the next tick retries.
                }
                if (resolved != null) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed) onName(resolved)
                    }
                }
            },
            0, pollIntervalMs, TimeUnit.MILLISECONDS
        )
        return this
    }

    /**
     * One resolution cycle. Returns the new name if it changed, else null. Synchronous, and free
     * of platform dependencies, so it is unit-testable without an IDE Application.
     */
    @Synchronized
    fun pollOnce(): ChatName? {
        if (disposed) return null
        val candidate = source.poll() ?: return null
        val held = current
        if (held != null && candidate.origin < held.origin) return null
        if (candidate == held) return null
        current = candidate
        return candidate
    }

    override fun dispose() {
        disposed = true
        future?.cancel(false)
        future = null
    }

    companion object {
        /** Titles change on the scale of turns, not keystrokes. */
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
    }
}
