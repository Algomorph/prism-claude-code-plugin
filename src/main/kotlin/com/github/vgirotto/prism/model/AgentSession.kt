package com.github.vgirotto.prism.model

import com.github.vgirotto.prism.services.AgentTtyConnector
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.util.Timer
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a single agent session with its own process, state, and metadata.
 */
class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Chat",
    val cli: AgentCli = AgentCli.DEFAULT,
) : Disposable {

    var process: Process? = null
    var connector: AgentTtyConnector? = null
    var idleTimer: Timer? = null
    var healthTimer: Timer? = null

    @Volatile var model: String = ""
    @Volatile var effort: String = ""
    @Volatile var state: SessionState = SessionState.STOPPED
    @Volatile var userHasInteracted: Boolean = false
    @Volatile var outputActive: Boolean = false
    @Volatile var idleFiredForCurrentInteraction: Boolean = false
    @Volatile var snapshotTakenForCurrentInput: Boolean = false

    /** Monotonic reading taken as the session launch begins; 0 until it does. */
    @Volatile var launchStartedAtNanos: Long = 0L

    /** Guards the one-shot "first output" startup timing log. */
    @Volatile var firstOutputLogged: Boolean = false

    /**
     * Serializes every write to this session's PTY.
     *
     * Codex needs a submitting input delivered as two keystrokes — the body, then the
     * Enter — spaced far enough apart not to look like a paste. Two writers racing inside
     * that gap interleave: two Resume clicks put "/resumeresume" in the composer. One
     * thread per session keeps writes ordered without ever blocking the EDT.
     */
    val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AgentPtyWriter-$id").apply { isDaemon = true }
    }

    /**
     * Counted rather than a flag: a second sequence can be queued behind the first, and a
     * plain boolean would be cleared by whichever finishes first, re-enabling the toolbar
     * while keystrokes are still going out.
     */
    private val pendingSequences = AtomicInteger(0)

    /** True while any staged keystroke sequence is still being delivered to the PTY. */
    val sequenceInFlight: Boolean get() = pendingSequences.get() > 0

    fun beginSequence() {
        pendingSequences.incrementAndGet()
    }

    fun endSequence() {
        pendingSequences.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    enum class SessionState { STOPPED, STARTING, IDLE, WORKING }

    val isAlive: Boolean get() = process?.isAlive == true

    /** Ms since the launch began, or -1 if this session hasn't started yet. */
    fun elapsedSinceLaunchMs(): Long =
        if (launchStartedAtNanos == 0L) -1
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launchStartedAtNanos)

    override fun dispose() {
        // Interrupts a sequence mid-flight: its remaining keystrokes are meant for a PTY
        // that is about to be torn down.
        try { writer.shutdownNow() } catch (_: Exception) {}
        pendingSequences.set(0)
        idleTimer?.cancel()
        idleTimer = null
        healthTimer?.cancel()
        healthTimer = null
        try { connector?.close() } catch (_: Exception) {}
        try {
            process?.let { if (it.isAlive) it.destroy() }
        } catch (_: Exception) {}
        process = null
        connector = null
        state = SessionState.STOPPED
    }
}
