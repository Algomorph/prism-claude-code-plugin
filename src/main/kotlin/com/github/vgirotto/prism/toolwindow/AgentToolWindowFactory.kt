package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
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
            project = project,
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

        // Listen for tab selection changes
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val sessionId = event.content.getUserData(SESSION_ID_KEY)
                if (sessionId != null) {
                    AgentProcessManager.getInstance(project).setActiveSession(sessionId)
                }
                event.content.getUserData(DIFF_PANEL_KEY)?.refreshDiff()
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                val sessionId = event.content.getUserData(SESSION_ID_KEY) ?: return
                AgentProcessManager.getInstance(project).destroySession(sessionId)
            }
        })

        // Idle listener: compute NEW diff and show on ALL DiffPanels
        AgentProcessManager.getInstance(project).addIdleListener {
            // Compute once on the active tab
            val activeContent = toolWindow.contentManager.selectedContent
            val activeDp = activeContent?.getUserData(DIFF_PANEL_KEY)
            activeDp?.computeAndShowDiff()

            // Refresh other tabs to show the same latest diff
            for (i in 0 until toolWindow.contentManager.contentCount) {
                val content = toolWindow.contentManager.getContent(i) ?: continue
                val dp = content.getUserData(DIFF_PANEL_KEY) ?: continue
                if (dp != activeDp) dp.refreshDiff()
            }
        }

        // Process death listener: notify when session dies unexpectedly
        AgentProcessManager.getInstance(project).addProcessDeathListener { sessionId, sessionName ->
            log.warn("Session process died: $sessionName [$sessionId]")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Prism")
                .createNotification(
                    "Claude Code",
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
        cli: com.github.vgirotto.prism.model.AgentCli =
            com.github.vgirotto.prism.services.AgentSettingsState.getInstance().defaultCli,
    ) {
        // Validate the requested CLI is available before creating UI
        val available = when (cli) {
            com.github.vgirotto.prism.model.AgentCli.CLAUDE ->
                com.github.vgirotto.prism.services.ClaudeValidationService.getInstance().isClaudeAvailable()
            com.github.vgirotto.prism.model.AgentCli.CODEX ->
                com.github.vgirotto.prism.services.CodexValidationService.getInstance().isCodexAvailable()
        }
        if (!available) {
            log.warn("${cli.name.lowercase()} CLI not found in PATH")
            showClaudeNotFoundError(project, toolWindow)
            return
        }

        val disposable = Disposer.newDisposable("AgentSession")
        Disposer.register(toolWindow.disposable, disposable)

        try {
            val settingsProvider = JBTerminalSystemSettingsProviderBase()
            val terminalWidget = JBTerminalWidget(project, settingsProvider, disposable)

            val escapeAction = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
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

            // Register Claude Code CLI shortcut passthroughs
            // IntelliJ intercepts Ctrl+S/V/Z/O/T/G before they reach the PTY,
            // so we explicitly forward them as control characters.
            val cliShortcuts = mapOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK) to "\u0016",     // Ctrl+V (paste images)
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

            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)

            // Start agent session
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val pm = AgentProcessManager.getInstance(project)
                    val result = pm.createSession(sessionName, cli)

                    content.putUserData(SESSION_ID_KEY, result.sessionId)
                    pm.setActiveSession(result.sessionId)

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            terminalWidget.createTerminalSession(result.connector)
                            terminalWidget.start()
                            log.info("Claude session started: $sessionName [${result.sessionId}]")
                        } catch (e: Exception) {
                            log.error("Failed to connect terminal session", e)
                            notifyError(project, PrismBundle.message("toolwindow.error.terminal", e.message ?: ""))
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to create Claude process", e)
                    notifyError(project, PrismBundle.message("toolwindow.error.start", e.message ?: ""))
                }
            }
        } catch (e: Exception) {
            log.error("Failed to create Claude terminal widget", e)
            showFallbackContent(project, toolWindow, e.message ?: "Unknown error")
        }
    }

    private fun showHistoryTab(project: Project, toolWindow: ToolWindow) {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i)
            if (content?.displayName == PrismBundle.message("toolwindow.tab.history")) {
                toolWindow.contentManager.setSelectedContent(content)
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

    private fun showClaudeNotFoundError(project: Project, toolWindow: ToolWindow) {
        val validator = com.github.vgirotto.prism.services.ClaudeValidationService.getInstance()
        val message = validator.getClaudeNotFoundMessage()

        val label = JLabel(
            "<html><center>" +
                "<h3>Claude not found</h3>" +
                "<p>Install it with:</p>" +
                "<code>npm install -g @anthropic-ai/claude-code</code>" +
                "<p>Then restart the IDE</p>" +
                "</center></html>",
            SwingConstants.CENTER
        )
        val content = toolWindow.contentManager.factory.createContent(label, "Error", false)
        toolWindow.contentManager.addContent(content)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Prism")
            .createNotification("Claude Code", message, NotificationType.ERROR)
            .notify(project)
    }

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
}
