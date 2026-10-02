package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexValidationService
import com.github.vgirotto.prism.services.FileSnapshotService
import com.github.vgirotto.prism.services.ResolvedCliCommand
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.BuildNumber
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
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
import java.beans.PropertyChangeListener
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.MenuSelectionManager
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {

    private val log = Logger.getInstance(AgentToolWindowFactory::class.java)

    companion object {
        val SESSION_ID_KEY = Key.create<String>("AgentSessionId")

        private val chatNamePattern = Regex("""^Chat #(\d+)$""")

        private const val TERMINAL_CONFIGURABLE_ID = "terminal"

        /** IntelliJ IDEA 2025.1.1, the first build with dedicated terminal font settings. */
        private val TERMINAL_FONT_SETTINGS_SINCE_BUILD: BuildNumber =
            requireNotNull(BuildNumber.fromString("251.25410"))

        /** An older IDE gets no entry, since its console font page errors out on open anyway. */
        internal fun supportsDedicatedTerminalFontSettings(build: BuildNumber): Boolean =
            build >= TERMINAL_FONT_SETTINGS_SINCE_BUILD

        /**
         * Names the next tab from the highest "Chat #N" currently open in this tool window,
         * rather than a static counter. `canCloseContents="true"` (plugin.xml) makes the platform
         * re-invoke [createToolWindowContent] — and previously reset a shared counter — whenever
         * this tool window's content is reinitialized, which produced duplicate tab names when
         * older tabs were still around. Scoping to the currently open tabs makes the name
         * collision-proof regardless of how many times that happens.
         */
        fun nextSessionName(toolWindow: ToolWindow): String {
            val highest = toolWindow.contentManager.contentsRecursively
                .mapNotNull { chatNamePattern.matchEntire(it.displayName.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 0
            return "Chat #${highest + 1}"
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val changesVisibleOnStartup = AgentSettingsState.getInstance().showChangesOnStartup
        val splitSupport = ToolWindowTabSplitSupport(toolWindow)
        val globalDiffHost = GlobalDiffContentHost.install(project, toolWindow, splitSupport)

        // Toggle action for the Changes panel
        val toggleChangesAction = object : ToggleAction(
            PrismBundle.message("toolwindow.toggle.changes"),
            if (changesVisibleOnStartup) PrismBundle.message("toolwindow.hide.changes") else PrismBundle.message("toolwindow.show.changes"),
            AllIcons.Actions.PreviewDetails
        ), DumbAware {
            override fun isSelected(e: AnActionEvent): Boolean {
                return globalDiffHost.isVisible()
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) {
                    globalDiffHost.show()
                } else {
                    globalDiffHost.hide()
                }
            }

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.text = if (isSelected(e)) PrismBundle.message("toolwindow.hide.changes") else PrismBundle.message("toolwindow.show.changes")
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val newSessionAction = NewSessionPopupAction(
            createSessionTab = { cli, manager ->
                createSessionTab(project, toolWindow, changesVisibleOnStartup, cli, manager)
            },
            targetManagerProvider = { resolveActionManager(project, toolWindow, it) },
        )

        val splitActions = DefaultActionGroup(
            PrismBundle.message("toolwindow.split"), true,
        ).apply {
            templatePresentation.icon = AllIcons.Actions.SplitVertically
            add(createMoveSplitAction(project, toolWindow, splitSupport, SplitDirection.RIGHT))
            add(createMoveSplitAction(project, toolWindow, splitSupport, SplitDirection.DOWN))
            add(createUnsplitAction(project, toolWindow, splitSupport))
            addSeparator()
            add(createNewSessionSplitGroup(
                project, toolWindow, changesVisibleOnStartup, splitSupport, SplitDirection.RIGHT,
            ))
            add(createNewSessionSplitGroup(
                project, toolWindow, changesVisibleOnStartup, splitSupport, SplitDirection.DOWN,
            ))
        }

        val historyAction = object : DumbAwareAction(
            PrismBundle.message("toolwindow.history"), PrismBundle.message("toolwindow.history.desc"), AllIcons.Vcs.History
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                showHistoryTab(project, toolWindow)
            }
        }

        val titleActions = mutableListOf<com.intellij.openapi.actionSystem.AnAction>(newSessionAction)
        if (splitSupport.isAvailable()) titleActions.add(splitActions)
        titleActions.add(historyAction)
        titleActions.add(toggleChangesAction)
        toolWindow.setTitleActions(titleActions)

        if (supportsDedicatedTerminalFontSettings(ApplicationInfo.getInstance().build)) {
            val fontSettingsAction = object : DumbAwareAction(
                PrismBundle.message("toolwindow.font.settings"),
                PrismBundle.message("toolwindow.font.settings.desc"),
                AllIcons.General.Settings
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    openTerminalSettings(project)
                }
            }
            toolWindow.setAdditionalGearActions(DefaultActionGroup(fontSettingsAction))
        }

        // Listen for tab selection changes. Session teardown is deliberately not wired
        // here — see the content disposer in buildSessionTab.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val sessionId = event.content.getUserData(SESSION_ID_KEY)
                if (sessionId != null) {
                    AgentProcessManager.getInstance(project).setActiveSession(sessionId)
                }
                globalDiffHost.refreshDiff()
            }
        })

        // Idle listener: compute one new diff off the UI thread, then update the global DiffPanel.
        AgentProcessManager.getInstance(project).addIdleListener { diff ->
            if (project.isDisposed) return@addIdleListener
            globalDiffHost.showDiff(diff)
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

        // Create the first session tab. The global Diff content is split from it
        // after the asynchronous CLI preflight finishes.
        if (AgentSettingsState.getInstance().autoStartOnOpen) {
            createSessionTab(project, toolWindow, changesVisibleOnStartup, globalDiffHost = globalDiffHost)
        }
    }

    /** Creates a new tab with its own terminal session. */
    internal fun createSessionTab(
        project: Project,
        toolWindow: ToolWindow,
        changesVisible: Boolean,
        cli: AgentCli = AgentSettingsState.getInstance().defaultCli,
        requestedManager: ContentManager? = null,
        splitRequest: SplitRequest? = null,
        globalDiffHost: GlobalDiffContentHost? = null,
    ) {
        // Validate the requested CLI is available before creating UI, using the
        // user-configured path so custom binary locations are honored. The check
        // stats the filesystem and reads the login-shell environment, which can
        // block while the platform loads it, and IntelliJ forbids blocking I/O on
        // the EDT, so resolve it on a pooled thread and build the tab UI back on
        // the EDT once the CLI is confirmed present.
        val settings = AgentSettingsState.getInstance()
        val effectiveGlobalDiffHost = globalDiffHost ?: GlobalDiffContentHost.get(toolWindow)
        ApplicationManager.getApplication().executeOnPooledThread {
            // Keep the resolved command, not just a yes/no: the session launches this
            // exact binary with the configured literal arguments.
            val preflightStartedAtNanos = System.nanoTime()
            val resolvedCommand = when (cli) {
                AgentCli.CLAUDE ->
                    ClaudeValidationService.getInstance().getClaudeCommand(settings.claudePath)
                AgentCli.CODEX ->
                    CodexValidationService.getInstance().getCodexCommand(settings.codexPath)
            }
            val preflightMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preflightStartedAtNanos)
            log.info(
                "timing: ${cli.name.lowercase()} preflight resolve took $preflightMs ms" +
                    " → ${resolvedCommand?.executable ?: "not found"}"
            )
            ApplicationManager.getApplication().invokeLater {
                if (resolvedCommand == null) {
                    log.warn("${cli.name.lowercase()} CLI not found at configured path or on PATH")
                    showCliNotFoundError(project, toolWindow, cli, requestedManager)
                    return@invokeLater
                }
                buildSessionTab(
                    project,
                    toolWindow,
                    changesVisible,
                    cli,
                    resolvedCommand,
                    validManager(toolWindow, requestedManager),
                    splitRequest,
                    effectiveGlobalDiffHost,
                )
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
        resolvedCommand: ResolvedCliCommand,
        targetManager: ContentManager,
        splitRequest: SplitRequest?,
        globalDiffHost: GlobalDiffContentHost?,
    ) {
        val disposable = Disposer.newDisposable("AgentSession")
        Disposer.register(toolWindow.disposable, disposable)

        try {
            val settingsProvider = JBTerminalSystemSettingsProvider()
            val terminalWidget = JBTerminalWidget(project, settingsProvider, disposable)
            val binding = SessionUiBinding(project, cli)

            // The picker takes focus so the press that closes it never reaches the terminal;
            // the gate covers the auto-repeat presses that arrive once the popup is gone.
            val escapeGate = EscapeKeyGate(terminalWidget.component, disposable)

            fun escapeIsBlocked(): Boolean =
                escapeGate.isBlockingEscape() || JBPopupFactory.getInstance().isPopupActive ||
                    MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()

            // IDE shortcut override can dispatch directly to the panel before our action.
            terminalWidget.terminalPanel.addPreKeyEventHandler { event ->
                handleTerminalEscape(event, escapeIsBlocked()) {
                    binding.sendText("\u001B")
                }
            }

            val escapeAction = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    if (escapeIsBlocked()) return

                    log.debug("Escape forwarded to the PTY")
                    binding.sendText("\u001B")
                }
            }
            escapeAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                terminalWidget.component,
                disposable
            )

            // Each key is bound for both settings of "Override IDE shortcuts" (see TerminalKeyBindings).
            // Escape is not among them: its handlers above also give way to popups and to the
            // held-key gate.
            val keys = TerminalKeyBindings(terminalWidget, disposable)

            // Shift+Enter sends CSI u escape sequence for newline without submitting
            keys.bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)) {
                binding.sendText("\u001b[13;2u")
            }

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
                keys.bind(keyStroke) { binding.sendText(sequence) }
            }

            // Ctrl+V: on Linux IntelliJ swallows the keystroke before it reaches
            // the PTY and the X11 clipboard isn't reliably readable by the child
            // process, so we paste from the JVM clipboard ourselves. On macOS and
            // Windows the native passthrough works well (Cmd+V pastes text, Ctrl+V
            // pastes images via the agent CLI), so we leave it untouched.
            keys.bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)) {
                if (SystemInfo.isLinux) {
                    handleSmartPaste(binding)
                } else {
                    binding.sendText("\u0016")
                }
            }

            val toolbar = AgentToolbar(project, binding)
            val terminalWithToolbar = JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(terminalWidget.component, BorderLayout.CENTER)
            }

            val sessionName = nextSessionName(toolWindow)
            val content = targetManager.factory.createContent(
                terminalWithToolbar, sessionName, false
            )
            content.isCloseable = true

            // The session lives and dies with the tab, and only tab *disposal* means the
            // tab is gone. Reordering tabs by dragging one removes its Content with
            // dispose = false and re-adds the same instance at the new index, so tearing
            // the session down on ContentManagerListener.contentRemoved killed the dragged
            // tab's PTY: the tab came back with its terminal painted but frozen, since
            // nothing was left on the other end of it. Every real close path (tab X, Close
            // Tab, Close All) removes with dispose = true, which runs this disposer.
            content.setDisposer {
                binding.dispose()?.let { sessionId ->
                    AgentProcessManager.getInstance(project).destroySession(sessionId)
                }
                Disposer.dispose(disposable)
            }

            targetManager.addContent(content)
            targetManager.setSelectedContent(content)

            installFocusActivation(terminalWithToolbar, disposable, binding)
            splitRequest?.let { it.support.perform(it.direction, targetManager, terminalWidget.component) }
            globalDiffHost?.sessionCreated(content, changesVisible)

            // Start agent session
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val pm = AgentProcessManager.getInstance(project)
                    val result = pm.createSession(sessionName, cli, resolvedCommand)

                    if (!binding.attach(result.sessionId)) {
                        pm.destroySession(result.sessionId)
                        return@executeOnPooledThread
                    }
                    content.putUserData(SESSION_ID_KEY, result.sessionId)

                    binding.activate()

                    ApplicationManager.getApplication().invokeLater {
                        if (!binding.isAttached(result.sessionId) || content.manager == null) {
                            return@invokeLater
                        }
                        try {
                            terminalWidget.createTerminalSession(result.connector)
                            terminalWidget.start()
                            terminalWidget.component.requestFocusInWindow()
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
            showFallbackContent(toolWindow, e.message ?: "Unknown error")
        }
    }

    private fun showHistoryTab(project: Project, toolWindow: ToolWindow) {
        for (content in toolWindow.contentManager.contentsRecursively) {
            if (content.displayName == PrismBundle.message("toolwindow.tab.history")) {
                content.manager?.setSelectedContent(content)
                // History is scoped to the active session's CLI, which may have changed
                // to another agent since this tab was built.
                (content.component as? HistoryPanel)?.loadHistory()
                return
            }
        }

        val manager = findActiveContent(project, toolWindow)?.manager ?: toolWindow.contentManager
        val historyPanel = HistoryPanel(project)
        val content = manager.factory.createContent(
            historyPanel, PrismBundle.message("toolwindow.tab.history"), false
        )
        content.isCloseable = true
        manager.addContent(content)
        manager.setSelectedContent(content)
        historyPanel.loadHistory()
    }

    private fun validManager(toolWindow: ToolWindow, requestedManager: ContentManager?): ContentManager =
        requestedManager?.takeUnless { it.isDisposed } ?: toolWindow.contentManager

    private fun findActiveContent(project: Project, toolWindow: ToolWindow): Content? =
        resolveActiveSessionContent(project, toolWindow)

    private fun createMoveSplitAction(
        project: Project,
        toolWindow: ToolWindow,
        support: ToolWindowTabSplitSupport,
        direction: SplitDirection,
    ) = object : DumbAwareAction(
        PrismBundle.message(
            if (direction == SplitDirection.RIGHT) "toolwindow.split.move.right"
            else "toolwindow.split.move.down"
        ),
        null,
        if (direction == SplitDirection.RIGHT) AllIcons.Actions.SplitVertically
        else AllIcons.Actions.SplitHorizontally,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val manager = resolveActionManager(project, toolWindow, e)
            val context = manager.selectedContent?.component ?: e.inputEvent?.component ?: return
            support.perform(direction, manager, context)
        }

        override fun update(e: AnActionEvent) {
            val manager = resolveActionManager(project, toolWindow, e)
            e.presentation.isEnabledAndVisible = support.isAvailable(direction) && manager.contentCount > 1
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun createUnsplitAction(
        project: Project,
        toolWindow: ToolWindow,
        support: ToolWindowTabSplitSupport,
    ) = object : DumbAwareAction(
        PrismBundle.message("toolwindow.split.unsplit"),
        null,
        AllIcons.Actions.Collapseall,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val manager = resolveActionManager(project, toolWindow, e)
            val context = manager.selectedContent?.component ?: e.inputEvent?.component ?: return
            support.perform(SplitDirection.UNSPLIT, manager, context)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = support.isAvailable(SplitDirection.UNSPLIT)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun createNewSessionSplitGroup(
        project: Project,
        toolWindow: ToolWindow,
        changesVisible: Boolean,
        support: ToolWindowTabSplitSupport,
        direction: SplitDirection,
    ): DefaultActionGroup {
        val isRight = direction == SplitDirection.RIGHT
        return DefaultActionGroup(
            PrismBundle.message(
                if (isRight) "toolwindow.split.new.right" else "toolwindow.split.new.down"
            ),
            true,
        ).apply {
            templatePresentation.description = PrismBundle.message(
                if (isRight) "toolwindow.split.new.right.desc" else "toolwindow.split.new.down.desc"
            )
            templatePresentation.icon =
                if (isRight) AllIcons.Actions.SplitVertically else AllIcons.Actions.SplitHorizontally

            val defaultCli = AgentSettingsState.getInstance().defaultCli
            for (cli in listOf(defaultCli) + (AgentCli.values().toList() - defaultCli)) {
                add(object : DumbAwareAction(cli.displayName()) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val manager = resolveActionManager(project, toolWindow, e)
                        createSessionTab(
                            project,
                            toolWindow,
                            changesVisible,
                            cli,
                            manager,
                            SplitRequest(direction, support),
                        )
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabledAndVisible =
                            support.isAvailable(direction) && cli in AgentCliAvailability.installed()
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
        }
    }

    private fun resolveActionManager(
        project: Project,
        toolWindow: ToolWindow,
        event: AnActionEvent,
    ): ContentManager {
        val activeManager = findActiveContent(project, toolWindow)?.manager
        val contextualManager = event.getData(PlatformDataKeys.CONTENT_MANAGER)
        return if (activeManager != null && contextualManager === activeManager) {
            contextualManager
        } else {
            activeManager ?: contextualManager?.takeUnless { it.isDisposed } ?: toolWindow.contentManager
        }
    }

    private fun installFocusActivation(
        component: java.awt.Component,
        disposable: com.intellij.openapi.Disposable,
        binding: SessionUiBinding,
    ) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event ->
            val owner = event.newValue as? java.awt.Component ?: return@PropertyChangeListener
            if (owner === component || SwingUtilities.isDescendingFrom(owner, component)) {
                binding.activate()
            }
        }
        focusManager.addPropertyChangeListener("permanentFocusOwner", listener)
        Disposer.register(disposable) {
            focusManager.removePropertyChangeListener("permanentFocusOwner", listener)
        }
    }

    private fun openTerminalSettings(project: Project) {
        // Matched on ID: the page's implementation class moves between releases, and its name is localized.
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { it is SearchableConfigurable && it.id == TERMINAL_CONFIGURABLE_ID },
            null
        )
    }

    private fun showCliNotFoundError(
        project: Project,
        toolWindow: ToolWindow,
        cli: AgentCli,
        requestedManager: ContentManager?,
    ) {
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
        val manager = validManager(toolWindow, requestedManager)
        val content = manager.factory.createContent(label, "Error", false)
        manager.addContent(content)

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

    private fun showFallbackContent(toolWindow: ToolWindow, error: String) {
        val label = JLabel(
            "<html><center>" +
                "<h3>${PrismBundle.message("toolwindow.error.init")}</h3>" +
                "<p>${PrismBundle.message("toolwindow.error.label", StringUtil.escapeXmlEntities(error))}</p>" +
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
    private fun handleSmartPaste(binding: SessionUiBinding) {
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
                sendBracketedPaste(binding, "$path ")
                return
            }
            log.warn("SmartPaste: image flavor advertised but bytes could not be read; falling back to ^V")
            binding.sendText("\u0016")
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
        sendBracketedPaste(binding, text)
    }

    private fun sendBracketedPaste(binding: SessionUiBinding, payload: String) {
        // Bracketed paste mode: tells the CLI this is pasted content so newlines
        // are treated as input rather than submit, and key sequences inside the
        // text aren't interpreted as shortcuts.
        binding.sendText("\u001b[200~$payload\u001b[201~")
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
