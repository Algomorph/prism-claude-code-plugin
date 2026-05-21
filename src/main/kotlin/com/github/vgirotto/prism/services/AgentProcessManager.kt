package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.model.AgentSession.SessionState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jediterm.terminal.TtyConnector
import com.google.gson.JsonParser
import com.pty4j.PtyProcessBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AgentProcessManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentProcessManager::class.java)

    /** All active sessions indexed by session ID */
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /** The currently focused session (selected tab) */
    @Volatile
    var activeSessionId: String? = null
        private set

    /** Listeners notified when Claude finishes responding (idle detected) */
    private val idleListeners = mutableListOf<(String) -> Unit>()

    /** Listeners notified when session state changes */
    private val stateListeners = mutableListOf<(AgentSession) -> Unit>()

    /** Listeners notified when a session process dies unexpectedly */
    private val processDeathListeners = mutableListOf<(String, String) -> Unit>()

    // ── Backwards-compatible accessors (delegate to active session) ──

    val currentModel: String get() = activeSession?.model ?: ""
    val currentEffort: String get() = activeSession?.effort ?: ""
    val sessionState: SessionState get() = activeSession?.state ?: SessionState.STOPPED

    val activeSession: AgentSession? get() = activeSessionId?.let { sessions[it] }

    fun getSession(sessionId: String): AgentSession? = sessions[sessionId]
    fun getAllSessions(): List<AgentSession> = sessions.values.toList()

    data class SessionResult(
        val sessionId: String,
        val process: Process,
        val connector: TtyConnector,
    )

    fun addIdleListener(listener: (String) -> Unit) {
        idleListeners.add(listener)
    }

    fun addStateListener(listener: (AgentSession) -> Unit) {
        stateListeners.add(listener)
    }

    fun addProcessDeathListener(listener: (sessionId: String, sessionName: String) -> Unit) {
        processDeathListeners.add(listener)
    }

    /**
     * Switches the active session to the given session ID.
     * Notifies state listeners so UI updates reflect the new session.
     */
    fun setActiveSession(sessionId: String) {
        activeSessionId = sessionId
        sessions[sessionId]?.let { notifyStateListeners(it) }
    }

    private fun notifyStateListeners(session: AgentSession) {
        ApplicationManager.getApplication().invokeLater {
            for (l in stateListeners) {
                try { l(session) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Creates a new Claude session with its own PTY process.
     * Returns the session result containing the connector for the terminal widget.
     */
    fun createSession(sessionName: String = "Chat"): SessionResult {
        val session = AgentSession(name = sessionName)
        loadModelFromClaudeSettings(session)
        session.state = SessionState.STARTING

        val settings = AgentSettingsState.getInstance()
        val claudePath = settings.claudePath
        val shell = settings.shellPath

        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        env["CLAUDE_CODE_WRAPPER"] = "intellij"

        val workDir = project.basePath ?: System.getProperty("user.home")

        log.info("Starting Claude session '${session.name}' [${session.id}]: claude=$claudePath, dir=$workDir")

        val command = arrayOf(shell, "-l", "-i")

        val process = PtyProcessBuilder(command)
            .setDirectory(workDir)
            .setEnvironment(env)
            .setConsole(false)
            .setInitialColumns(120)
            .setInitialRows(40)
            .start()

        val connector = AgentTtyConnector(
            process = process,
            charset = StandardCharsets.UTF_8,
            onUserInput = { onUserInput(session) },
            onOutputActivity = { onOutputActivity(session) },
            onStartupParsed = { model, effort -> onStartupParsed(session, model, effort) },
        )

        session.process = process
        session.connector = connector
        sessions[session.id] = session

        // Set as active if first session
        if (activeSessionId == null) {
            activeSessionId = session.id
        }

        // Reset snapshot to a fresh full copy of the current project state.
        // Using resetSnapshot() (not takeSnapshot()) ensures the new baseline includes any changes
        // made by previous sessions, so the startup idle does not create a spurious interaction.
        try {
            FileSnapshotService.getInstance(project).resetSnapshot()
        } catch (e: Exception) {
            log.debug("Failed to reset snapshot for new session", e)
        }

        startIdleMonitor(session)
        startProcessHealthMonitor(session)

        // Send the claude command after a brief delay for shell init
        Thread {
            try {
                Thread.sleep(500)
                if (process.isAlive) {
                    val cmd = "$claudePath\n"
                    process.outputStream.write(cmd.toByteArray(StandardCharsets.UTF_8))
                    process.outputStream.flush()
                    log.info("Sent claude command to shell [${session.id}]")
                }
            } catch (e: Exception) {
                log.warn("Failed to send claude command [${session.id}]", e)
            }
        }.start()

        notifyStateListeners(session)

        return SessionResult(session.id, process, connector)
    }

    private fun onUserInput(session: AgentSession) {
        session.userHasInteracted = true
        if (!session.snapshotTakenForCurrentInput) {
            session.snapshotTakenForCurrentInput = true
            session.idleFiredForCurrentInteraction = false
            try {
                val snapshotService = FileSnapshotService.getInstance(project)
                snapshotService.lastSessionName = session.name
                snapshotService.takeSnapshot()
                log.info("Snapshot taken on user input [${session.id}]")
            } catch (e: Exception) {
                log.debug("Failed to take snapshot on input", e)
            }
        }
    }

    private fun onOutputActivity(session: AgentSession) {
        session.outputActive = true
        if (session.userHasInteracted && !session.idleFiredForCurrentInteraction) {
            val wasWorking = session.state == SessionState.WORKING
            session.state = SessionState.WORKING
            notifyStateListeners(session)

            // Warn if multiple sessions are working simultaneously
            if (!wasWorking) {
                val workingCount = sessions.values.count { it.state == SessionState.WORKING }
                if (workingCount > 1) {
                    log.warn("$workingCount sessions working simultaneously — file conflicts possible")
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("Prism")
                            .createNotification(
                                com.github.vgirotto.prism.i18n.PrismBundle.message("notification.title"),
                                com.github.vgirotto.prism.i18n.PrismBundle.message("notification.concurrent.warning", workingCount),
                                com.intellij.notification.NotificationType.WARNING
                            )
                            .notify(project)
                    }
                }
            }
        }
    }

    private fun onStartupParsed(session: AgentSession, model: String, effort: String) {
        if (model.isNotEmpty()) session.model = model
        if (effort.isNotEmpty()) session.effort = effort
        log.info("Startup parsed [${session.id}]: model=$model, effort=$effort")
        notifyStateListeners(session)
    }

    private fun loadModelFromClaudeSettings(session: AgentSession) {
        session.model = ""
        session.effort = "auto" // Claude Code default when effortLevel is not in settings.json
        try {
            val settingsFile = File(System.getProperty("user.home"), ".claude/settings.json")
            if (!settingsFile.exists()) return

            val json = JsonParser.parseString(settingsFile.readText()).asJsonObject

            if (json.has("model")) {
                val rawModel = json.get("model").asString
                session.model = rawModel.replace(Regex("\\[.*]"), "").trim().lowercase()
            }
            if (json.has("effortLevel")) {
                session.effort = json.get("effortLevel").asString.trim().lowercase()
            }
        } catch (e: Exception) {
            log.warn("Failed to read Claude settings.json: ${e.message}", e)
        }
    }

    private fun startIdleMonitor(session: AgentSession) {
        session.idleTimer?.cancel()
        session.idleTimer = Timer("ClaudeIdleMonitor-${session.id}", true)
        session.idleTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val connector = session.connector ?: return
                val idle = connector.getIdleTimeMs()

                if (session.outputActive && idle >= 2000 && !session.idleFiredForCurrentInteraction) {
                    session.outputActive = false
                    session.idleFiredForCurrentInteraction = true
                    session.snapshotTakenForCurrentInput = false // Reset for next interaction
                    session.connector?.tryParseStartup()
                    session.state = SessionState.IDLE
                    notifyStateListeners(session)
                    log.info("Idle detected [${session.id}], triggering auto-refresh")
                    ApplicationManager.getApplication().invokeLater {
                        for (listener in idleListeners) {
                            try { listener(session.id) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }, 1000, 500)
    }

    private fun startProcessHealthMonitor(session: AgentSession) {
        session.healthTimer?.cancel()
        session.healthTimer = Timer("ClaudeHealthMonitor-${session.id}", true)
        session.healthTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val process = session.process ?: return
                if (!process.isAlive && session.state != SessionState.STOPPED) {
                    log.warn("Process died unexpectedly [${session.id}]")
                    session.state = SessionState.STOPPED
                    notifyStateListeners(session)
                    ApplicationManager.getApplication().invokeLater {
                        for (listener in processDeathListeners) {
                            try { listener(session.id, session.name) } catch (_: Exception) {}
                        }
                    }
                    this.cancel()
                }
            }
        }, 2000, 2000)
    }

    /**
     * Sends text to the active session.
     */
    fun sendText(text: String) {
        sendText(text, activeSessionId)
    }

    /**
     * Sends text to a specific session.
     */
    fun sendText(text: String, sessionId: String?) {
        val session = sessionId?.let { sessions[it] } ?: return
        val process = session.process
        if (process == null || !process.isAlive) {
            log.warn("Cannot send text: session not alive [${session.id}]")
            return
        }

        val trimmed = text.trim().removeSuffix("\r").removeSuffix("\n").trim()
        if (trimmed.startsWith("/model ")) {
            session.model = trimmed.removePrefix("/model ").trim()
            notifyStateListeners(session)
        } else if (trimmed.startsWith("/effort ")) {
            session.effort = trimmed.removePrefix("/effort ").trim()
            notifyStateListeners(session)
        }

        // Reset idle flags so the idle monitor can fire after slash command output
        if (trimmed.startsWith("/")) {
            session.idleFiredForCurrentInteraction = false
            session.outputActive = false
        }

        // Take snapshot before sending user input (but not for slash commands)
        if (text.endsWith("\n") && text.trim().isNotEmpty() && !trimmed.startsWith("/")) {
            try {
                val snapshotService = FileSnapshotService.getInstance(project)
                snapshotService.lastSessionName = session.name
                snapshotService.takeSnapshot()
            } catch (e: Exception) {
                log.debug("Failed to take snapshot", e)
            }
        }

        try {
            process.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        } catch (e: Exception) {
            log.warn("Failed to send text [${session.id}]", e)
        }
    }

    fun isSessionAlive(): Boolean = activeSession?.isAlive == true

    fun isSessionAlive(sessionId: String): Boolean = sessions[sessionId]?.isAlive == true

    /**
     * Destroys a specific session.
     */
    fun destroySession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.dispose()
        log.info("Session destroyed: ${session.name} [${session.id}]")

        if (activeSessionId == sessionId) {
            activeSessionId = sessions.keys.firstOrNull()
            activeSession?.let { notifyStateListeners(it) }
        }
    }

    /**
     * Destroys all sessions.
     */
    fun destroyAllSessions() {
        for (session in sessions.values) {
            session.dispose()
        }
        sessions.clear()
        activeSessionId = null
    }

    override fun dispose() {
        destroyAllSessions()
        idleListeners.clear()
        stateListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): AgentProcessManager =
            project.getService(AgentProcessManager::class.java)
    }
}
