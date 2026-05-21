package com.github.vgirotto.prism.model

import com.github.vgirotto.prism.services.ClaudeTtyConnector
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.util.Timer
import java.util.UUID

/**
 * Represents a single Claude Code session with its own process, state, and metadata.
 */
class ClaudeSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Chat",
    val cli: AgentCli = AgentCli.DEFAULT,
) : Disposable {

    var process: Process? = null
    var connector: ClaudeTtyConnector? = null
    var idleTimer: Timer? = null
    var healthTimer: Timer? = null

    @Volatile var model: String = ""
    @Volatile var effort: String = ""
    @Volatile var state: SessionState = SessionState.STOPPED
    @Volatile var userHasInteracted: Boolean = false
    @Volatile var outputActive: Boolean = false
    @Volatile var idleFiredForCurrentInteraction: Boolean = false
    @Volatile var snapshotTakenForCurrentInput: Boolean = false

    enum class SessionState { STOPPED, STARTING, IDLE, WORKING }

    val isAlive: Boolean get() = process?.isAlive == true

    override fun dispose() {
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
