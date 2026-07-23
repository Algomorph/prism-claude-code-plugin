package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexValidationService
import com.github.vgirotto.prism.services.FileSnapshotService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.github.vgirotto.prism.terminal.PrismTerminalSettingsProvider
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.BorderLayout
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {

    private val log = Logger.getInstance(AgentToolWindowFactory::class.java)

    companion object {
        val SESSION_ID_KEY = Key.create<String>("AgentSessionId")
        val DIFF_PANEL_KEY = Key.create<DiffPanel>("AgentDiffPanel")

        private var sessionCounter = 0

        fun nextSessionName(): String {
            sessionCounter++
            return "Chat #$sessionCounter"
        }

        fun resetCounter() {
            sessionCounter = 0
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        resetCounter()

        var changesVisible = AgentSettingsState.getInstance().showChangesOnStartup
        var lastProportion = 0.65f

        // Toggle action for the Changes panel
        val toggleChangesAction = object : ToggleAction(
            PrismBundle.message("toolwindow.toggle.changes"),
            if (changesVisible) PrismBundle.message("toolwindow.hide.changes") else PrismBundle.message("toolwindow.show.changes"),
            AllIcons.Actions.PreviewDetails
        ), DumbAware {
            override fun isSelected(e: AnActionEvent): Boolean = changesVisible

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                changesVisible = state
                val activeContent = toolWindow.contentManager.selectedContent ?: return
                val splitter = activeContent.component as? JBSplitter ?: return
                val dp = activeContent.getUserData(DIFF_PANEL_KEY) ?: return
                if (state) {
                    splitter.secondComponent = dp
                    splitter.proportion = lastProportion
                } else {
                    lastProportion = splitter.proportion
                    splitter.secondComponent = null
                }
            }

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.text = if (changesVisible) PrismBundle.message("toolwindow.hide.changes") else PrismBundle.message("toolwindow.show.changes")
            }
        }

        val newSessionAction = NewSessionPopupAction(
            createSessionTab = { cli -> createSessionTab(project, toolWindow, changesVisible, cli) },
        )

        val historyAction = object : DumbAwareAction(
            PrismBundle.message("toolwindow.history"), PrismBundle.message("toolwindow.history.desc"), AllIcons.Vcs.History
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                showHistoryTab(project, toolWindow)
            }
        }

        toolWindow.setTitleActions(listOf(newSessionAction, historyAction, toggleChangesAction))

        // Listen for tab selection changes. Session teardown is deliberately not wired
        // here — see the content disposer in buildSessionTab.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val sessionId = event.content.getUserData(SESSION_ID_KEY)
                if (sessionId != null) {
                    AgentProcessManager.getInstance(project).setActiveSession(sessionId)
                }
                event.content.getUserData(DIFF_PANEL_KEY)?.refreshDiff()
            }
        })

        // Idle listener: compute one new diff off the UI thread, then show it on all DiffPanels.
        AgentProcessManager.getInstance(project).addIdleListener {
            val panels = (0 until toolWindow.contentManager.contentCount).mapNotNull { i ->
                toolWindow.contentManager.getContent(i)?.getUserData(DIFF_PANEL_KEY)
            }
            if (panels.isEmpty()) return@addIdleListener

            ApplicationManager.getApplication().executeOnPooledThread {
                val diff = FileSnapshotService.getInstance(project).refreshVfsAndComputeDiff()
                if (diff.changes.isEmpty()) return@executeOnPooledThread

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    panels.forEach { it.showDiff(diff) }
                }
            }
        }

        // Process death listener: notify when session dies unexpectedly
        AgentProcessManager.getInstance(project).addProcessDeathListener { sessionId, sessionName ->
            log.warn("Session process died: $sessionName [$sessionId]")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Prism")
                .createNotification(
                    PrismBundle.message("notification.title"),
                    "Session '$sessionName' ended unexpectedly.\n\nClick 'Restart' to start a new session.",
                    NotificationType.WARNING
                )
                .notify(project)
        }

        // Create the first session tab
        if (AgentSettingsState.getInstance().autoStartOnOpen) {
            createSessionTab(project, toolWindow, changesVisible)
        }
    }

    /**
     * Creates a new tab with its own terminal session and DiffPanel.
     * Each tab owns its DiffPanel — no shared component, no parent issues.
     */
    fun createSessionTab(
        project: Project,
        toolWindow: ToolWindow,
        changesVisible: Boolean,
        cli: AgentCli = AgentSettingsState.getInstance().defaultCli,
    ) {
        // Validate the requested CLI is available before creating UI, using the
        // user-configured path so custom binary locations are honored. The check
        // stats the filesystem and reads the login-shell environment, which can
        // block while the platform loads it, and IntelliJ forbids blocking I/O on
        // the EDT, so resolve it on a pooled thread and build the tab UI back on
        // the EDT once the CLI is confirmed present.
        val settings = AgentSettingsState.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            // Keep the resolved absolute path, not just a yes/no: the session
            // launches this exact binary instead of re-resolving the configured
            // string through the shell's own PATH.
            val preflightStartedAtNanos = System.nanoTime()
            val resolvedBinary = when (cli) {
                AgentCli.CLAUDE ->
                    ClaudeValidationService.getInstance().getClaudePath(settings.claudePath)
                AgentCli.CODEX ->
                    CodexValidationService.getInstance().getCodexPath(settings.codexPath)
            }
            val preflightMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preflightStartedAtNanos)
            log.info(
                "timing: ${cli.name.lowercase()} preflight resolve took $preflightMs ms" +
                    " → ${resolvedBinary ?: "not found"}"
            )
            ApplicationManager.getApplication().invokeLater {
                if (resolvedBinary == null) {
                    log.warn("${cli.name.lowercase()} CLI not found at configured path or on PATH")
                    showCliNotFoundError(project, toolWindow, cli)
                    return@invokeLater
                }
                buildSessionTab(project, toolWindow, changesVisible, cli, resolvedBinary)
            }
        }
    }

    /**
     * Builds the tab UI (terminal, toolbar, diff panel) and starts the agent
     * session. Must run on the EDT; [createSessionTab] performs the off-EDT
     * availability preflight before invoking this.
     */
    private fun buildSessionTab(
        project: Project,
        toolWindow: ToolWindow,
        changesVisible: Boolean,
        cli: AgentCli,
        resolvedBinary: String,
    ) {
        val disposable = Disposer.newDisposable("AgentSession")
        Disposer.register(toolWindow.disposable, disposable)

        try {
            val settingsProvider = PrismTerminalSettingsProvider()
            val terminalWidget = JBTerminalWidget(project, settingsProvider, disposable)

            // The picker takes focus so the press that closes it never reaches the terminal;
            // the gate covers the auto-repeat presses that arrive once the popup is gone.
            EscapeKeyGate(terminalWidget.component, disposable)

            val escapeAction = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    log.debug("Escape forwarded to the PTY")
                    AgentProcessManager.getInstance(project).sendText("\u001B")
                }
            }
            escapeAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                terminalWidget.component,
                disposable
            )

            // Shift+Enter sends CSI u escape sequence for newline without submitting
            val shiftEnterAction = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    AgentProcessManager.getInstance(project).sendText("\u001b[13;2u")
                }
            }
            shiftEnterAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)),
                terminalWidget.component,
                disposable
            )

            // Ctrl+V is handled specially per platform (see below). The rest are
            // CLI shortcuts IntelliJ intercepts before they reach the PTY, so we
            // explicitly forward them as control characters.
            val cliShortcuts = mapOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK) to "\u0013",     // Ctrl+S (stash prompt)
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK) to "\u001A",     // Ctrl+Z (suspend)
                KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK) to "\u000F",     // Ctrl+O (verbose output)
                KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK) to "\u0014",     // Ctrl+T (toggle tasks)
                KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK) to "\u0007",     // Ctrl+G (edit in $EDITOR)
                KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK) to "\u001F",  // Ctrl+Shift+- (undo)
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.META_DOWN_MASK) to "\u001Bp",    // Meta+P (switch model)
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK) to "\u001b[Z" // Shift+Tab (auto-accept)
            )

            for ((keyStroke, sequence) in cliShortcuts) {
                val action = object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        AgentProcessManager.getInstance(project).sendText(sequence)
                    }
                }
                action.registerCustomShortcutSet(
                    CustomShortcutSet(keyStroke),
                    terminalWidget.component,
                    disposable
                )
            }

            // Ctrl+V: on Linux IntelliJ swallows the keystroke before it reaches
            // the PTY and the X11 clipboard isn't reliably readable by the child
            // process, so we paste from the JVM clipboard ourselves. On macOS and
            // Windows the native passthrough works well (Cmd+V pastes text, Ctrl+V
            // pastes images via the agent CLI), so we leave it untouched.
            val pasteAction = if (SystemInfo.isLinux) {
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        handleSmartPaste(project)
                    }
                }
            } else {
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        AgentProcessManager.getInstance(project).sendText("\u0016")
                    }
                }
            }
            pasteAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)),
                terminalWidget.component,
                disposable
            )

            val toolbar = AgentToolbar(project)
            val terminalWithToolbar = JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(terminalWidget.component, BorderLayout.CENTER)
            }

            // Each tab gets its own DiffPanel (no parent-sharing issues)
            val diffPanel = DiffPanel(project) {
                // When history is cleared, reset ALL DiffPanels across all tabs
                for (i in 0 until toolWindow.contentManager.contentCount) {
                    toolWindow.contentManager.getContent(i)
                        ?.getUserData(DIFF_PANEL_KEY)
                        ?.clearAndReset()
                }
            }

            val isSideDock = toolWindow.anchor == ToolWindowAnchor.LEFT ||
                toolWindow.anchor == ToolWindowAnchor.RIGHT

            val splitter = JBSplitter(isSideDock, if (isSideDock) 0.6f else 0.65f).apply {
                firstComponent = terminalWithToolbar
                dividerWidth = 3
            }

            if (changesVisible) {
                splitter.secondComponent = diffPanel
            }

            splitter.addHierarchyListener {
                val tw = ToolWindowManager.getInstance(project).getToolWindow("Prism")
                if (tw != null) {
                    val shouldBeVertical = tw.anchor == ToolWindowAnchor.LEFT ||
                        tw.anchor == ToolWindowAnchor.RIGHT
                    if (splitter.orientation != shouldBeVertical) {
                        splitter.orientation = shouldBeVertical
                        splitter.proportion = if (shouldBeVertical) 0.6f else 0.65f
                    }
                }
            }

            val sessionName = nextSessionName()
            val content = toolWindow.contentManager.factory.createContent(
                splitter, sessionName, false
            )
            content.isCloseable = true
            content.putUserData(DIFF_PANEL_KEY, diffPanel)

            // The session lives and dies with the tab, and only tab *disposal* means the
            // tab is gone. Reordering tabs by dragging one removes its Content with
            // dispose = false and re-adds the same instance at the new index, so tearing
            // the session down on ContentManagerListener.contentRemoved killed the dragged
            // tab's PTY: the tab came back with its terminal painted but frozen, since
            // nothing was left on the other end of it. Every real close path (tab X, Close
            // Tab, Close All) removes with dispose = true, which runs this disposer.
            val tabClosed = AtomicBoolean(false)
            content.setDisposer {
                tabClosed.set(true)
                content.getUserData(SESSION_ID_KEY)?.let { sessionId ->
                    AgentProcessManager.getInstance(project).destroySession(sessionId)
                }
                Disposer.dispose(disposable)
            }

            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)

            // Start agent session
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val pm = AgentProcessManager.getInstance(project)
                    val result = pm.createSession(sessionName, cli, resolvedBinary)

                    content.putUserData(SESSION_ID_KEY, result.sessionId)

                    // The tab can be closed while the PTY is still spawning, before the
                    // session ID the disposer looks for exists. Tear it down here instead
                    // of leaving an orphaned agent process behind.
                    if (tabClosed.get()) {
                        pm.destroySession(result.sessionId)
                        return@executeOnPooledThread
                    }

                    pm.setActiveSession(result.sessionId)

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            terminalWidget.createTerminalSession(result.connector)
                            terminalWidget.start()
                            log.info("Agent session started: $sessionName [${result.sessionId}]")
                        } catch (e: Exception) {
                            log.error("Failed to connect terminal session", e)
                            notifyError(project, PrismBundle.message("toolwindow.error.terminal", e.message ?: ""))
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to create agent process", e)
                    notifyError(project, PrismBundle.message("toolwindow.error.start", e.message ?: ""))
                }
            }
        } catch (e: Exception) {
            log.error("Failed to create agent terminal widget", e)
            showFallbackContent(project, toolWindow, e.message ?: "Unknown error")
        }
    }

    private fun showHistoryTab(project: Project, toolWindow: ToolWindow) {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i)
            if (content?.displayName == PrismBundle.message("toolwindow.tab.history")) {
                toolWindow.contentManager.setSelectedContent(content)
                // History is scoped to the active session's CLI, which may have changed
                // to another agent since this tab was built.
                (content.component as? HistoryPanel)?.loadHistory()
                return
            }
        }

        val historyPanel = HistoryPanel(project)
        val content = toolWindow.contentManager.factory.createContent(
            historyPanel, PrismBundle.message("toolwindow.tab.history"), false
        )
        content.isCloseable = true
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        historyPanel.loadHistory()
    }

    private fun showCliNotFoundError(project: Project, toolWindow: ToolWindow, cli: AgentCli) {
        val (heading, installCmd, notificationTitle, message) = when (cli) {
            AgentCli.CLAUDE -> CliNotFoundCopy(
                heading = "Claude not found",
                installCmd = "npm install -g @anthropic-ai/claude-code",
                notificationTitle = "Claude Code",
                message = ClaudeValidationService.getInstance().getClaudeNotFoundMessage(),
            )
            AgentCli.CODEX -> CliNotFoundCopy(
                heading = "Codex not found",
                installCmd = "npm install -g @openai/codex",
                notificationTitle = "Codex",
                message = CodexValidationService.getInstance().getCodexNotFoundMessage(),
            )
        }

        val label = JLabel(
            "<html><center>" +
                "<h3>$heading</h3>" +
                "<p>Install it with:</p>" +
                "<code>$installCmd</code>" +
                "<p>Then start a new session</p>" +
                "</center></html>",
            SwingConstants.CENTER
        )
        val content = toolWindow.contentManager.factory.createContent(label, "Error", false)
        toolWindow.contentManager.addContent(content)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Prism")
            .createNotification(notificationTitle, message, NotificationType.ERROR)
            .notify(project)
    }

    private data class CliNotFoundCopy(
        val heading: String,
        val installCmd: String,
        val notificationTitle: String,
        val message: String,
    )

    private fun showFallbackContent(project: Project, toolWindow: ToolWindow, error: String) {
        val label = JLabel(
            "<html><center>" +
                "<h3>${PrismBundle.message("toolwindow.error.init")}</h3>" +
                "<p>${PrismBundle.message("toolwindow.error.label", error)}</p>" +
                "<p>${PrismBundle.message("toolwindow.error.settings")}</p>" +
                "</center></html>",
            SwingConstants.CENTER
        )
        val content = toolWindow.contentManager.factory.createContent(label, PrismBundle.message("toolwindow.tab.error"), false)
        toolWindow.contentManager.addContent(content)
    }

    private fun notifyError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Prism")
                .createNotification("Prism", message, NotificationType.ERROR)
                .notify(project)
        }
    }

    /**
     * Linux Ctrl+V handler. If the clipboard holds an image, write it to a temp
     * PNG and paste the file path; otherwise paste clipboard text ourselves
     * wrapped in bracketed-paste escapes so multi-line content doesn't auto-submit.
     */
    private fun handleSmartPaste(project: Project) {
        val clipboard = try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (e: Exception) {
            log.warn("SmartPaste: system clipboard unavailable", e)
            return
        }

        val imageFlavorAvailable = try {
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
        } catch (e: Exception) { false }

        // Image branch: save clipboard bytes to a temp PNG and paste the path.
        // Pasting a path (rather than forwarding ^V) avoids depending on the agent's
        // own clipboard reader, which can't always pick up screenshots on Linux/X11.
        if (imageFlavorAvailable) {
            val path = saveClipboardImageToTempFile(clipboard)
            if (path != null) {
                sendBracketedPaste(project, "$path ")
                return
            }
            log.warn("SmartPaste: image flavor advertised but bytes could not be read; falling back to ^V")
            AgentProcessManager.getInstance(project).sendText("\u0016")
            return
        }

        val text = try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
        } catch (e: Exception) {
            log.debug("SmartPaste: failed to read clipboard text", e)
            null
        }
        if (text.isNullOrEmpty()) return
        sendBracketedPaste(project, text)
    }

    private fun sendBracketedPaste(project: Project, payload: String) {
        // Bracketed paste mode: tells the CLI this is pasted content so newlines
        // are treated as input rather than submit, and key sequences inside the
        // text aren't interpreted as shortcuts.
        AgentProcessManager.getInstance(project).sendText("\u001b[200~$payload\u001b[201~")
    }

    private fun saveClipboardImageToTempFile(clipboard: java.awt.datatransfer.Clipboard): String? {
        val raw = try {
            clipboard.getData(DataFlavor.imageFlavor)
        } catch (e: Exception) {
            log.warn("SmartPaste: clipboard.getData(imageFlavor) failed", e)
            return null
        }
        val rendered: RenderedImage = when (raw) {
            is RenderedImage -> raw
            is Image -> toBuffered(raw) ?: return null
            else -> {
                log.warn("SmartPaste: unexpected image type ${raw?.javaClass?.name}")
                return null
            }
        }
        return try {
            val dir = Path.of(System.getProperty("java.io.tmpdir"), "prism-paste")
            Files.createDirectories(dir)
            pruneOldFiles(dir)
            val file = Files.createTempFile(dir, "paste-", ".png")
            ImageIO.write(rendered, "png", file.toFile())
            file.toAbsolutePath().toString()
        } catch (e: Exception) {
            log.warn("SmartPaste: failed to write temp PNG", e)
            null
        }
    }

    private fun toBuffered(img: Image): BufferedImage? {
        val w = img.getWidth(null)
        val h = img.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buf = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        try { g.drawImage(img, 0, 0, null) } finally { g.dispose() }
        return buf
    }

    private fun pruneOldFiles(dir: Path) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        try {
            Files.newDirectoryStream(dir, "paste-*.png").use { stream ->
                for (p in stream) {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p)
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }
}
