package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli
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
import java.util.concurrent.RejectedExecutionException

@Service(Service.Level.PROJECT)
class AgentProcessManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentProcessManager::class.java)

    /** All active sessions indexed by session ID */
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /** The currently focused session (selected tab) */
    @Volatile
    var activeSessionId: String? = null
        private set

    /** Listeners notified when the agent finishes responding (idle detected) */
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
     * Creates a new agent session with its own PTY process.
     * Returns the session result containing the connector for the terminal widget.
     *
     * [resolvedBinary] is the absolute path the availability preflight resolved, and is
     * shell-quoted before being typed into the PTY. Falls back to the configured value,
     * which may carry arguments and so is passed through verbatim.
     */
    fun createSession(
        sessionName: String = "Chat",
        cli: AgentCli = AgentSettingsState.getInstance().defaultCli,
        resolvedBinary: String? = null,
    ): SessionResult {
        val session = AgentSession(name = sessionName, cli = cli)
        loadModelFromAgentSettings(session)
        session.state = SessionState.STARTING

        val settings = AgentSettingsState.getInstance()
        val binaryPath = resolvedBinary ?: settings.cliPath(cli)
        // `clear` runs after the shell echoes the line and before the agent paints, which
        // hides the prompt without racing the agent's first paint.
        val launchCommand =
            "clear; " + if (resolvedBinary != null) shellQuote(resolvedBinary) else binaryPath
        val shell = settings.shellPath

        val env = HashMap(System.getenv())
        env["TERM"] = "xterm-256color"
        // Claude Code reads this to know it is embedded; it means nothing to other CLIs.
        if (cli == AgentCli.CLAUDE) env["CLAUDE_CODE_WRAPPER"] = "intellij"

        val workDir = project.basePath ?: System.getProperty("user.home")

        log.info("Starting ${cli.name.lowercase()} session '${session.name}' [${session.id}]: binary=$binaryPath, dir=$workDir")

        val command = arrayOf(shell, "-l", "-i")

        session.launchStartedAtNanos = System.nanoTime()
        val process = PtyProcessBuilder(command)
            .setDirectory(workDir)
            .setEnvironment(env)
            .setConsole(false)
            .setInitialColumns(120)
            .setInitialRows(40)
            .start()
        log.info("timing: session [${session.id}] pty spawn ${session.elapsedSinceLaunchMs()} ms")

        val connector = AgentTtyConnector(
            process = process,
            charset = StandardCharsets.UTF_8,
            cli = cli,
            onUserInput = { onUserInput(session) },
            onOutputActivity = { onOutputActivity(session) },
            onStartupParsed = { model, effort -> onStartupParsed(session, model, effort) },
            writeQueue = { write -> submitWrite(session, write = write) },
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

        // Send the agent command after a brief delay for shell init
        Thread {
            try {
                Thread.sleep(500)
                if (process.isAlive) {
                    val cmd = "$launchCommand\n"
                    process.outputStream.write(cmd.toByteArray(StandardCharsets.UTF_8))
                    process.outputStream.flush()
                    log.info("Sent ${cli.name.lowercase()} command to shell [${session.id}]")
                }
            } catch (e: Exception) {
                log.warn("Failed to send ${cli.name.lowercase()} command [${session.id}]", e)
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
        // First printable byte out of the PTY: the login shell's own output, or the echo
        // of the command typed after createSession's fixed delay — whichever lands first.
        if (!session.firstOutputLogged) {
            session.firstOutputLogged = true
            log.info("timing: session [${session.id}] first shell output +${session.elapsedSinceLaunchMs()} ms")
        }
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
        log.info(
            "Startup parsed [${session.id}]: model=$model, effort=$effort" +
                " (+${session.elapsedSinceLaunchMs()} ms)"
        )
        notifyStateListeners(session)
    }

    private fun loadModelFromAgentSettings(session: AgentSession) {
        session.model = ""
        session.effort = "auto"
        if (session.cli != AgentCli.CLAUDE) return
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
        session.idleTimer = Timer("AgentIdleMonitor-${session.id}", true)
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
        session.healthTimer = Timer("AgentHealthMonitor-${session.id}", true)
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

        // Codex treats a "type text then newline" burst arriving in one write as a
        // paste: it drops the text into the composer but never submits, so the user
        // would have to press Enter themselves. For any submitting input (content
        // followed by a line terminator — a slash command or a prompt) stage the
        // body and the Enter key (CR) as separate keystrokes so Codex registers the
        // submit. Claude submits the single burst fine and is left untouched. The
        // model/effort/snapshot bookkeeping above has already run; sendSequence just
        // performs the actual staged write.
        if (session.cli == AgentCli.CODEX) {
            val chunks = codexSubmitChunks(text)
            if (chunks != null) {
                sendSequence(chunks, sessionId)
                return
            }
        }

        // Queued rather than written inline so it can neither overtake nor be overtaken by
        // a staged Codex sequence already sitting in this session's write queue.
        submitWrite(session) {
            process.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        }
    }

    /**
     * Runs [write] on the session's single writer thread, so every write to that PTY is
     * ordered against every other one and none of them lands on the EDT.
     */
    private fun submitWrite(session: AgentSession, onDone: (() -> Unit)? = null, write: () -> Unit) {
        try {
            session.writer.execute {
                try {
                    write()
                } catch (_: InterruptedException) {
                    // Session disposed mid-sequence; the remaining keystrokes are moot.
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    log.warn("Failed to write to PTY [${session.id}]", e)
                } finally {
                    onDone?.invoke()
                }
            }
        } catch (_: RejectedExecutionException) {
            log.debug("Write dropped, session writer already shut down [${session.id}]")
            onDone?.invoke()
        }
    }

    /**
     * Sends an ordered list of input chunks to the active session, pausing
     * [stepDelayMs] between successive chunks so an interactive TUI picker
     * (e.g. Codex's `/model` or `/usage` flows) has time to render each prompt
     * before the next keystroke arrives.
     *
     * Writes run on a background thread so the delays never block the EDT.
     * Unlike [sendText] this never takes a file snapshot — every chunk is a
     * picker keystroke, not a user prompt.
     *
     * The 700 ms default was verified against Codex CLI 0.146.x; revisit it if a
     * future CLI renders its pickers more slowly.
     */
    fun sendSequence(chunks: List<String>, stepDelayMs: Long = 700L) {
        sendSequence(chunks, activeSessionId, stepDelayMs)
    }

    fun sendSequence(chunks: List<String>, sessionId: String?, stepDelayMs: Long = 700L) {
        val session = sessionId?.let { sessions[it] } ?: return
        val process = session.process
        if (process == null || !process.isAlive) {
            log.warn("Cannot send sequence: session not alive [${session.id}]")
            return
        }
        if (chunks.isEmpty()) return

        // A picker interaction behaves like a slash command: reset the idle flags so
        // the idle monitor fires (and the UI auto-refreshes) once the output settles.
        session.idleFiredForCurrentInteraction = false
        session.outputActive = false
        session.beginSequence()

        submitWrite(session, onDone = { session.endSequence() }) {
            for ((index, chunk) in chunks.withIndex()) {
                if (index > 0) Thread.sleep(stepDelayMs)
                if (!process.isAlive) break
                process.outputStream.write(chunk.toByteArray(StandardCharsets.UTF_8))
                process.outputStream.flush()
            }
        }
    }

    /** Optimistically records the active session's model and notifies the UI. */
    fun setSessionModel(model: String) {
        val session = activeSession ?: return
        session.model = model
        notifyStateListeners(session)
    }

    /** Optimistically records the active session's effort and notifies the UI. */
    fun setSessionEffort(effort: String) {
        val session = activeSession ?: return
        session.effort = effort
        notifyStateListeners(session)
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

/**
 * Classifies a [sendText] payload for Codex delivery.
 *
 * Returns the keystroke chunks (`[body, "\r"]`) needed to *submit* [text] when
 * it is a submitting input — real content followed by a trailing line
 * terminator (`\n` or `\r`), such as a slash command (`"/resume\r"`) or a
 * prompt (`"ask something\n"`). Returns `null` when [text] should be written
 * verbatim instead: insert-only text with no trailing terminator (e.g. a
 * "@folder" mention), bare control keys (ESC, Ctrl-V), and bracketed-paste
 * sequences all fall through untouched.
 *
 * Codex needs the Enter keystroke delivered separately from the typed text —
 * a text+newline burst in a single write is interpreted as a paste and never
 * submitted. See [AgentProcessManager.sendText].
 */
internal fun codexSubmitChunks(text: String): List<String>? {
    val trimmed = text.trim().removeSuffix("\r").removeSuffix("\n").trim()
    if (trimmed.isEmpty()) return null
    if (!text.endsWith("\n") && !text.endsWith("\r")) return null
    return listOf(text.trimEnd('\r', '\n'), "\r")
}

/**
 * Single-quotes [path] for the POSIX shell the session command is typed into,
 * so a resolved binary path containing spaces or shell metacharacters launches
 * as one word. Embedded single quotes are closed, escaped, and reopened —
 * `it's/codex` becomes `'it'\''s/codex'`.
 */
internal fun shellQuote(path: String): String =
    "'" + path.replace("'", "'\\''") + "'"
