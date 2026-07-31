package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.icons.PrismIcons
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ChatName
import com.github.vgirotto.prism.services.ChatNameSource
import com.github.vgirotto.prism.services.ChatNameWatcher
import com.github.vgirotto.prism.services.ClaudeChatNameSource
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexChatNameSource
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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.Content
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

        /** transcript-in-editor mode: the tab's transcript editor file and its chat panel,
         *  so the toggle button and the editor tab's × can stay in sync. */
        val TRANSCRIPT_FILE_KEY =
            Key.create<com.github.vgirotto.prism.chatshell.TranscriptVirtualFile>("PrismTranscriptFile")
        val CHAT_PANEL_KEY =
            Key.create<com.github.vgirotto.prism.chatshell.ChatShellPanel>("PrismChatShellPanel")

        /** Per-tab hook to migrate its transcript hosting live when the setting flips; the arg is
         *  the new "transcript in editor" value. Set once the session has started. */
        val REHOST_KEY = Key.create<(Boolean) -> Unit>("PrismTranscriptRehost")

        /** The chat's resolved display name, once its CLI has recorded one. Read when building
         *  transcript hosting so a re-home after a rename does not revert to `Chat #N`. */
        val CHAT_NAME_KEY = Key.create<String>("PrismChatDisplayName")

        /** Marks the single Conversation History tab. Chat tabs take their titles from the agent
         *  now, so one could legitimately be *called* "History" — identifying it by display name
         *  would then reveal a chat instead of opening history. */
        val HISTORY_TAB_KEY = Key.create<Boolean>("PrismHistoryTab")

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

        // Editor-hosted transcript: when the user closes a transcript tab with its ×, flip the
        // matching chat's toggle back to "Show Transcript" (× == hide transcript, per session).
        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file !is com.github.vgirotto.prism.chatshell.TranscriptVirtualFile) return
                    findContentBySessionId(toolWindow, file.sessionId)
                        ?.getUserData(CHAT_PANEL_KEY)
                        ?.setTranscriptVisibleExternally(false)
                }
            }
        )

        // Live-migrate every open chat when the "transcript in editor" setting flips, in either
        // direction, so the checkbox dynamically re-homes what is displayed instead of only
        // affecting chats opened afterward.
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable).subscribe(
            com.github.vgirotto.prism.settings.TranscriptHostingListener.TOPIC,
            com.github.vgirotto.prism.settings.TranscriptHostingListener { inEditor ->
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    for (i in 0 until toolWindow.contentManager.contentCount) {
                        toolWindow.contentManager.getContent(i)
                            ?.getUserData(REHOST_KEY)?.invoke(inEditor)
                    }
                }
            }
        )

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
            val settingsProvider = JBTerminalSystemSettingsProviderBase()
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
            // The toolbar now lives in the chat header (ChatShellPanel), not atop the
            // terminal — so this strip is the terminal alone. Wrapper kept for the
            // splitter's min-height guard.
            val terminalPanel = JPanel(BorderLayout()).apply {
                add(terminalWidget.component, BorderLayout.CENTER)
            }

            // Transcript hosting. Default (transcript-in-editor): the transcript is a separate
            // IDE editor tab whose FileEditor owns its own view/controller, so here we build only
            // the header+terminal and let the toggle open/close that tab. Alternative (split):
            // the transcript renders in this pane above the terminal, as before. The browser is
            // created lazily on first tab-select (R20).
            //
            // Editor hosting keys the tab's virtual file on the Prism session id (both CLIs have
            // one). The editor's live tail is CLI-aware: Claude reads <convId>.jsonl, Codex
            // resolves its rollout by cwd + recency (design §11). Both honor this setting.
            val inEditor = AgentSettingsState.getInstance().showTranscriptInEditor
            val contentHolder = arrayOfNulls<Content>(1)

            // The transcript rendering stack is built (and can be rebuilt) in the session-start
            // callback below, so start with no transcript. The panel's terminal and toolbar are
            // never reparented, so flipping the hosting setting later can swap only the transcript
            // slot without disturbing the running session.
            val chatShellPanel = com.github.vgirotto.prism.chatshell.ChatShellPanel(
                project, toolbar, null, terminalPanel, editorMode = inEditor,
                onEditorToggle = { toggleTranscriptEditor(project, contentHolder[0]) }
            )

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
                firstComponent = chatShellPanel
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

            // `Chat #N` is the placeholder, not the name: neither CLI has recorded a title yet
            // (Claude generates one after the first turn, Codex has nothing to derive one from
            // until the user types), so the tab opens numbered and is renamed by the
            // ChatNameWatcher installed once the session starts.
            val sessionName = nextSessionName()
            val content = toolWindow.contentManager.factory.createContent(
                splitter, sessionName, false
            )
            content.isCloseable = true
            // Which agent is behind this tab, at a glance. Tool-window tabs hide content icons
            // unless asked to show them.
            content.icon = PrismIcons.forCli(cli)
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            content.description = cli.displayName()
            content.putUserData(DIFF_PANEL_KEY, diffPanel)
            content.putUserData(CHAT_PANEL_KEY, chatShellPanel)
            contentHolder[0] = content

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
                // Editor-hosted transcript: close its tab when the session tab goes away, so no
                // orphaned transcript editor outlives the chat that fed it. Tied to disposal for
                // the same reason as the session — on a drag-reorder the transcript editor has to
                // stay open, since the chat feeding it is only moving, not closing.
                content.getUserData(TRANSCRIPT_FILE_KEY)?.let { file ->
                    val fem = FileEditorManager.getInstance(project)
                    if (fem.isFileOpen(file)) fem.closeFile(file)
                }
                // Releases the tab's own resources (JCEF browser, transcript poller, controller,
                // selection listener) instead of leaking them until the whole tool window closes.
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

                    // Resolve runtime support off the EDT (the capability probe can block for
                    // seconds) so the transcript can render an explicit "unavailable" state
                    // rather than a permanent empty pane when this CLI lacks --session-id (#4).
                    // Only meaningful for Claude — Codex resolves its rollout a different way, so
                    // don't spawn the claude probe for a Codex session.
                    val deterministicSupported = cli == AgentCli.CLAUDE &&
                        try { pm.isDeterministicSessionSupported() } catch (_: Exception) { true }

                    ApplicationManager.getApplication().invokeLater {
                        try {
                            terminalWidget.createTerminalSession(result.connector)
                            terminalWidget.start()
                            log.info("Agent session started: $sessionName [${result.sessionId}]")

                            // The conversation id equals the session id when --session-id is
                            // supported (Claude); otherwise there is no deterministic transcript
                            // file (a Claude version without --session-id). Codex ignores convId and
                            // resolves its rollout by cwd + recency instead.
                            val convId = result.sessionId
                            // A transcript can be rendered when we have a source to tail: Codex
                            // always resolves one from the project; Claude needs --session-id.
                            val canRenderTranscript = cli == AgentCli.CODEX || deterministicSupported

                            // Rename the tab from `Chat #N` to whatever the CLI calls this chat,
                            // as soon as it has recorded a name (see [ChatNameWatcher]).
                            installChatNameWatcher(
                                project, disposable, content, cli,
                                launchSessionId = if (deterministicSupported) convId else null,
                            )

                            // Currently-active split transcript stack (view + controller + selection
                            // listener), so a live hosting switch can dispose exactly this. Null in
                            // editor mode. `hostingInEditor` tracks which hosting is live.
                            var splitStack: com.intellij.openapi.Disposable? = null
                            var hostingInEditor = inEditor

                            fun teardownSplitStack() {
                                splitStack?.let { Disposer.dispose(it) }
                                splitStack = null
                            }

                            // Build the in-pane (split) transcript: lazily initialize the browser +
                            // attach the live tail when this tab is (or becomes) selected, pausing
                            // when another tab is (R20). [show] reveals the pane after the build.
                            fun buildSplitStack(show: Boolean) {
                                val stackDisposable = Disposer.newDisposable(disposable, "SplitTranscript")
                                splitStack = stackDisposable
                                val view = com.github.vgirotto.prism.chatshell.TranscriptView(stackDisposable)
                                view.onOpenLink = { href ->
                                    try { com.intellij.ide.BrowserUtil.browse(href) } catch (_: Exception) {}
                                }
                                val controller = com.github.vgirotto.prism.chatshell.TranscriptController(project, view)
                                Disposer.register(stackDisposable, controller)
                                var attached = false
                                val ensureAttached = {
                                    view.initialize(
                                        com.github.vgirotto.prism.chatshell.ChatShellTheme.currentVars()
                                    )
                                    when {
                                        // Codex has no caller-supplied session id; the controller
                                        // resolves its rollout file by cwd + recency and tails it
                                        // with the Codex parser (design §11).
                                        cli == AgentCli.CODEX -> {
                                            if (!attached) { attached = true; controller.attachLiveCodex() }
                                            controller.resume()
                                        }
                                        // Claude with --session-id: the transcript file is <id>.jsonl.
                                        deterministicSupported -> {
                                            if (!attached) { attached = true; controller.attachLive(convId) }
                                            controller.resume()
                                        }
                                        // Claude without --session-id: no deterministic file to tail.
                                        else -> view.setState(
                                            com.github.vgirotto.prism.chatshell.TranscriptView.State.UNAVAILABLE
                                        )
                                    }
                                }
                                chatShellPanel.setHosting(
                                    view.component, editorMode = false, onEditorToggle = null,
                                    showTranscript = show,
                                )
                                if (toolWindow.contentManager.selectedContent === content) {
                                    ensureAttached()
                                }
                                val selectionListener = object : ContentManagerListener {
                                    override fun selectionChanged(event: ContentManagerEvent) {
                                        if (event.content !== content) return
                                        if (event.operation == ContentManagerEvent.ContentOperation.add) ensureAttached()
                                        else controller.pause()
                                    }
                                }
                                toolWindow.contentManager.addContentManagerListener(selectionListener)
                                Disposer.register(stackDisposable, com.intellij.openapi.Disposable {
                                    toolWindow.contentManager.removeContentManagerListener(selectionListener)
                                })
                            }

                            // Build transcript-in-editor: register the tab's virtual file; the
                            // FileEditor owns its own browser + tail and renders nothing until shown
                            // (R20). When [show], open that tab now so a switch keeps it displayed.
                            fun buildEditorHosting(show: Boolean) {
                                content.putUserData(
                                    TRANSCRIPT_FILE_KEY,
                                    if (canRenderTranscript)
                                        com.github.vgirotto.prism.chatshell.TranscriptVirtualFile(
                                            result.sessionId, convId,
                                            // Already renamed? Re-homing must not revert the title.
                                            content.getUserData(CHAT_NAME_KEY) ?: sessionName,
                                            cli
                                        )
                                    else null
                                )
                                chatShellPanel.setHosting(
                                    null, editorMode = true,
                                    onEditorToggle = { toggleTranscriptEditor(project, content) },
                                    showTranscript = false,
                                )
                                if (!canRenderTranscript) {
                                    // No transcript source: disable the toggle rather than open an
                                    // empty tab.
                                    chatShellPanel.setToggleEnabled(
                                        false, PrismBundle.message("chatshell.unavailable")
                                    )
                                } else if (show) {
                                    content.getUserData(TRANSCRIPT_FILE_KEY)?.let { file ->
                                        FileEditorManager.getInstance(project).openFile(file, false)
                                        chatShellPanel.setTranscriptVisibleExternally(true)
                                    }
                                }
                            }

                            // Migrate this tab's hosting when the setting flips, preserving whether
                            // the transcript is currently displayed. The terminal/toolbar stay put,
                            // so the running session is undisturbed.
                            val rehost = fun(toEditor: Boolean) {
                                if (toEditor == hostingInEditor) return
                                val wasShown = chatShellPanel.isTranscriptVisible()
                                if (toEditor) {
                                    // split -> editor: detach the pane (removes the view from the UI)
                                    // before disposing its browser, then re-home to the editor tab.
                                    buildEditorHosting(show = wasShown)
                                    teardownSplitStack()
                                } else {
                                    // editor -> split: close the editor tab, then render in the pane.
                                    content.getUserData(TRANSCRIPT_FILE_KEY)?.let { file ->
                                        val fem = FileEditorManager.getInstance(project)
                                        if (fem.isFileOpen(file)) fem.closeFile(file)
                                    }
                                    content.putUserData(TRANSCRIPT_FILE_KEY, null)
                                    buildSplitStack(show = wasShown)
                                }
                                hostingInEditor = toEditor
                            }

                            if (inEditor) buildEditorHosting(show = false)
                            else buildSplitStack(show = chatShellPanel.isTranscriptVisible())
                            content.putUserData(REHOST_KEY, rehost)
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

    /**
     * Watch for the name the CLI gives this chat and rename the tab when it appears, replacing
     * the `Chat #N` placeholder (see [ChatNameWatcher] for the resolution rules).
     *
     * [launchSessionId] is the `--session-id` a Claude chat was launched with — the marker that
     * says which conversation file is ours. Null means Prism has no per-chat identity to attribute
     * a title to (a Claude build without `--session-id`), so the tab keeps its number rather than
     * risk showing another chat's title. Codex needs no id: its rollout is resolved from the
     * project, with the same cwd + recency heuristic the transcript uses.
     */
    private fun installChatNameWatcher(
        project: Project,
        parentDisposable: com.intellij.openapi.Disposable,
        content: Content,
        cli: AgentCli,
        launchSessionId: String?,
    ) {
        val source: ChatNameSource = when (cli) {
            AgentCli.CLAUDE -> {
                val id = launchSessionId ?: return
                val resolver = com.github.vgirotto.prism.chatshell.SessionResolver(project.basePath)
                ClaudeChatNameSource({ resolver.projectDir()?.takeIf { it.isDirectory } }, id)
            }
            AgentCli.CODEX -> {
                val resolver =
                    com.github.vgirotto.prism.chatshell.CodexSessionResolver(project.basePath)
                CodexChatNameSource { resolver.newestForProject() }
            }
        }
        val watcher = ChatNameWatcher(source)
        Disposer.register(parentDisposable, watcher)
        watcher.start { name -> applyChatName(project, content, cli, name) }
    }

    /**
     * Put [name] on the chat tab: the clipped form as the label, the full text plus the agent it
     * belongs to as the tooltip (a truncated title is only useful if the whole one is reachable).
     *
     * The transcript editor tab carries the same name, so it is renamed in step.
     */
    private fun applyChatName(project: Project, content: Content, cli: AgentCli, name: ChatName) {
        val label = name.display()
        content.displayName = label
        content.description = PrismBundle.message("toolwindow.tab.tooltip", cli.displayName(), name.text)
        content.putUserData(CHAT_NAME_KEY, label)
        content.getUserData(TRANSCRIPT_FILE_KEY)?.let { file ->
            file.chatName = label
            // Renaming the file is only half of it — the open tab caches the old title.
            com.github.vgirotto.prism.chatshell.TranscriptTabTitle.refresh(project, file)
        }
    }

    /**
     * transcript-in-editor toggle: open the chat's transcript editor tab if closed, or close it
     * if already open (closing routes through [FileEditorManagerListener.fileClosed], which flips
     * the toggle label). Opens without stealing focus so the terminal keeps the caret.
     */
    private fun toggleTranscriptEditor(project: Project, content: Content?) {
        val file = content?.getUserData(TRANSCRIPT_FILE_KEY) ?: return
        val fem = FileEditorManager.getInstance(project)
        if (fem.isFileOpen(file)) {
            fem.closeFile(file)
        } else {
            fem.openFile(file, false)
            content.getUserData(CHAT_PANEL_KEY)?.setTranscriptVisibleExternally(true)
        }
    }

    private fun findContentBySessionId(toolWindow: ToolWindow, sessionId: String): Content? {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val c = toolWindow.contentManager.getContent(i) ?: continue
            if (c.getUserData(SESSION_ID_KEY) == sessionId) return c
        }
        return null
    }

    private fun showHistoryTab(project: Project, toolWindow: ToolWindow) {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i)
            if (content?.getUserData(HISTORY_TAB_KEY) == true) {
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
        content.putUserData(HISTORY_TAB_KEY, true)
        content.icon = AllIcons.Vcs.History
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
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
