package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ConversationHistoryService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Panel for browsing Claude Code conversation history using native IntelliJ components.
 * Respects IDE theme (Darcula/Light) automatically via JBColor and UIUtil.
 */
class HistoryPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val historyService = ConversationHistoryService.getInstance(project)

    /** History is scoped to the active session's CLI; fallback to the configured default. */
    private fun activeCli(): AgentCli =
        AgentProcessManager.getInstance(project).activeSession?.cli
            ?: AgentSettingsState.getInstance().defaultCli
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // List view
    private val listModel = DefaultListModel<ConversationSummary>()
    private val conversationList = JBList(listModel)
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("")

    // Detail view
    private val detailContent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val detailTitleLabel = JBLabel("")

    // Theme colors (auto-adapt to Darcula/Light)
    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val LIST_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM HH:mm")

        val USER_BG = JBColor(Color(0xDCEEFB), Color(0x2B3E50))
        val ASSISTANT_BG = JBColor(Color(0xF0F0F0), Color(0x313335))
        val META_FG = JBColor(Color(0x999999), Color(0x777777))
        val TOOL_FG = JBColor(Color(0x4A8A3A), Color(0x6A9955))
        val USER_LABEL_FG = JBColor(Color(0x1565C0), Color(0x6BADF7))
        val ASSISTANT_LABEL_FG = JBColor(Color(0x7B1FA2), Color(0xCE93D8))
        val SEPARATOR_COLOR = JBColor(Color(0xE0E0E0), Color(0x3C3F41))
    }

    init {
        buildListView()
        buildDetailView()
        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, "list")
    }

    // ── List View ──

    private fun buildListView() {
        val listPanel = JPanel(BorderLayout())

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                performSearch(searchField.text)
            }
        })

        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(searchField, BorderLayout.CENTER)
        }

        val toolbarGroup = DefaultActionGroup().apply {
            add(object : AnAction(PrismBundle.message("history.refresh"), PrismBundle.message("history.refresh.desc"), AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = loadHistory()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ClaudeHistory", toolbarGroup, true).apply {
            targetComponent = listPanel
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchPanel, BorderLayout.CENTER)
            add(statusLabel.apply {
                foreground = META_FG
                border = JBUI.Borders.empty(0, 8)
            }, BorderLayout.EAST)
        }

        conversationList.cellRenderer = ConversationListRenderer()
        conversationList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        conversationList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                conversationList.selectedValue?.let { showConversation(it) }
            }
        }

        listPanel.add(topPanel, BorderLayout.NORTH)
        listPanel.add(JBScrollPane(conversationList), BorderLayout.CENTER)
        cardPanel.add(listPanel, "list")
    }

    // ── Detail View ──

    private fun buildDetailView() {
        val detailPanel = JPanel(BorderLayout())

        val detailToolbar = DefaultActionGroup().apply {
            add(object : AnAction(PrismBundle.message("history.back"), PrismBundle.message("history.back.desc"), AllIcons.Actions.Back), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    cardLayout.show(cardPanel, "list")
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val detailToolbar2 = ActionManager.getInstance().createActionToolbar("ClaudeHistoryDetail", detailToolbar, true).apply {
            targetComponent = detailPanel
        }

        val headerPanel = JPanel(BorderLayout()).apply {
            add(detailToolbar2.component, BorderLayout.WEST)
            add(detailTitleLabel.apply {
                border = JBUI.Borders.empty(0, 8)
            }, BorderLayout.CENTER)
            border = JBUI.Borders.customLine(SEPARATOR_COLOR, 0, 0, 1, 0)
        }

        val scrollPane = JBScrollPane(detailContent).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        detailPanel.add(headerPanel, BorderLayout.NORTH)
        detailPanel.add(scrollPane, BorderLayout.CENTER)
        cardPanel.add(detailPanel, "detail")
    }

    fun loadHistory() {
        val cli = activeCli()
        ApplicationManager.getApplication().executeOnPooledThread {
            val conversations = historyService.listConversations(cli)
            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
                for (conv in conversations) listModel.addElement(conv)
                statusLabel.text = PrismBundle.message("history.conversations", conversations.size)
            }
        }
    }

    private fun performSearch(query: String) {
        val cli = activeCli()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = historyService.searchConversations(query, cli)
            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
                for (conv in results) listModel.addElement(conv)
                statusLabel.text = PrismBundle.message("history.results", results.size)
            }
        }
    }

    private fun showConversation(summary: ConversationSummary) {
        val modelShort = summary.model.replace("claude-", "").replace("-", " ")
        detailTitleLabel.text = "${formatDate(summary.startTime)} — $modelShort"

        val cli = activeCli()
        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = historyService.loadConversation(summary.sessionId, cli)
            // Collapse tool-only messages and filter empties
            val collapsed = collapseToolMessages(messages)

            ApplicationManager.getApplication().invokeLater {
                detailContent.removeAll()

                // Summary header
                detailContent.add(createSummaryHeader(summary, collapsed.size))

                // Message panels
                for (msg in collapsed) {
                    detailContent.add(createMessagePanel(msg))
                    detailContent.add(Box.createVerticalStrut(1))
                }

                // Push content to top
                detailContent.add(Box.createVerticalGlue())

                detailContent.revalidate()
                detailContent.repaint()

                SwingUtilities.invokeLater {
                    (detailContent.parent?.parent as? JScrollPane)?.verticalScrollBar?.value = 0
                }

                cardLayout.show(cardPanel, "detail")
            }
        }
    }

    /** Regex to detect system/internal tags in user messages */
    private val systemTagPattern = Regex("^\\s*<(local-command|command-name|command-message|command-args|local-command-stdout|local-command-caveat|system-reminder|ide_opened_file)")

    /**
     * Collapses consecutive tool-only assistant messages into one compact entry,
     * filters out system/internal messages, and removes empty messages.
     */
    private fun collapseToolMessages(messages: List<ConversationMessage>): List<ConversationMessage> {
        val result = mutableListOf<ConversationMessage>()
        var pendingTools = mutableListOf<String>()
        var pendingTimestamp = messages.firstOrNull()?.timestamp

        for (msg in messages) {
            // Skip system/internal messages (CLI tags like <local-command-caveat>, <command-name>, etc.)
            if (msg.content.isNotBlank() && systemTagPattern.containsMatchIn(msg.content)) {
                continue
            }

            val isToolOnly = msg.role == "assistant" &&
                msg.toolUses.isNotEmpty() &&
                msg.content.isBlank()

            if (isToolOnly) {
                pendingTools.addAll(msg.toolUses.map { it.name })
                if (pendingTimestamp == null) pendingTimestamp = msg.timestamp
                continue
            }

            // Flush pending tools as a single compact message
            if (pendingTools.isNotEmpty()) {
                result.add(ConversationMessage(
                    uuid = "", timestamp = pendingTimestamp ?: msg.timestamp,
                    type = "assistant", role = "assistant",
                    content = "", model = null,
                    toolUses = pendingTools.map { com.github.vgirotto.prism.model.ToolUseEntry(it, "") },
                ))
                pendingTools = mutableListOf()
                pendingTimestamp = null
            }

            // Skip empty messages
            if (msg.content.isBlank() && msg.toolUses.isEmpty()) continue

            result.add(msg)
        }

        // Flush remaining tools
        if (pendingTools.isNotEmpty() && pendingTimestamp != null) {
            result.add(ConversationMessage(
                uuid = "", timestamp = pendingTimestamp,
                type = "assistant", role = "assistant",
                content = "", model = null,
                toolUses = pendingTools.map { com.github.vgirotto.prism.model.ToolUseEntry(it, "") },
            ))
        }

        return result
    }

    private fun createSummaryHeader(summary: ConversationSummary, msgCount: Int): JPanel {
        val panel = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            override fun getMaximumSize(): Dimension {
                val pref = preferredSize
                return Dimension(Int.MAX_VALUE, pref.height)
            }
        }.apply {
            border = JBUI.Borders.empty(8, 8, 6, 8)
            isOpaque = false
        }

        val title = JBLabel(summary.firstUserMessage.take(120).replace("\n", " ")).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val modelShort = summary.model.replace("claude-", "").replace("-", " ")
        val meta = JBLabel("${formatDate(summary.startTime)} · ${PrismBundle.message("history.messages", msgCount)} · $modelShort").apply {
            foreground = META_FG
            font = font.deriveFont(font.size - 1f)
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = Component.CENTER_ALIGNMENT
        }

        panel.add(title)
        panel.add(Box.createVerticalStrut(2))
        panel.add(meta)
        return panel
    }

    private fun createMessagePanel(msg: ConversationMessage): JPanel {
        val isUser = msg.role == "user"
        val isToolOnly = msg.content.isBlank() && msg.toolUses.isNotEmpty()

        // Tool-only messages: compact single line
        if (isToolOnly) {
            return createToolCompactPanel(msg)
        }

        val bg = if (isUser) USER_BG else ASSISTANT_BG

        val panel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension {
                val pref = preferredSize
                return Dimension(Int.MAX_VALUE, pref.height)
            }
        }.apply {
            background = bg
            border = JBUI.Borders.empty(6, 8, 6, 8)
        }

        // Header: role + timestamp
        val header = SimpleColoredComponent().apply {
            isOpaque = false
            val roleFg = if (isUser) USER_LABEL_FG else ASSISTANT_LABEL_FG
            val roleLabel = if (isUser) PrismBundle.message("history.role.you") else PrismBundle.message("history.role.claude")
            val time = TIME_FORMATTER.format(msg.timestamp.atZone(ZoneId.systemDefault()))
            append(roleLabel, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, roleFg))
            append("  $time", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, META_FG))
        }

        // Content text
        val text = if (!isUser && msg.content.length > 3000) {
            msg.content.take(3000) + "\n... (truncated)"
        } else {
            msg.content
        }

        val textArea = JTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(2, 0, 0, 0)
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(textArea, BorderLayout.CENTER)

        // Inline tools if present alongside text
        if (msg.toolUses.isNotEmpty()) {
            val tools = msg.toolUses.joinToString(" · ") { it.name }
            val toolLabel = JBLabel(tools).apply {
                foreground = TOOL_FG
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(2, 0, 0, 0)
            }
            panel.add(toolLabel, BorderLayout.SOUTH)
        }

        return panel
    }

    /**
     * Compact panel for tool-only messages: a single gray line showing tools used.
     */
    private fun createToolCompactPanel(msg: ConversationMessage): JPanel {
        val tools = msg.toolUses.map { it.name }
        // Dedupe consecutive identical tools and show counts
        val grouped = tools.groupingBy { it }.eachCount()
        val label = grouped.entries.joinToString(" · ") { (name, count) ->
            if (count > 1) "$name ($count)" else name
        }

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8)
            maximumSize = Dimension(Int.MAX_VALUE, 24)
        }

        val comp = SimpleColoredComponent().apply {
            isOpaque = false
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(label, SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC or SimpleTextAttributes.STYLE_SMALLER, TOOL_FG))
        }

        panel.add(comp, BorderLayout.WEST)
        return panel
    }

    private fun formatDate(instant: java.time.Instant): String =
        DATE_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))

    // ── List Renderer ──

    private class ConversationListRenderer : ListCellRenderer<ConversationSummary> {
        override fun getListCellRendererComponent(
            list: JList<out ConversationSummary>,
            value: ConversationSummary?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) return JBLabel("")

            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(5, 8, 5, 8)
                isOpaque = true
                background = if (isSelected) {
                    UIUtil.getListSelectionBackground(cellHasFocus)
                } else {
                    UIUtil.getListBackground()
                }
            }

            val date = LIST_DATE_FORMATTER.format(value.startTime.atZone(ZoneId.systemDefault()))
            val modelShort = value.model.replace("claude-", "").replace("-", " ")
            val preview = value.firstUserMessage.take(100).replace("\n", " ")

            val fg = if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else UIUtil.getLabelForeground()

            val headerLine = SimpleColoredComponent().apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                append(date, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
                append("  ${value.messageCount} msgs", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (isSelected) fg else META_FG))
                if (modelShort.isNotBlank()) {
                    append("  $modelShort", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_ITALIC, if (isSelected) fg else META_FG))
                }
                icon = AllIcons.Actions.IntentionBulb
            }

            val previewLabel = JBLabel(preview).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else META_FG
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)
                border = JBUI.Borders.empty(2, 20, 0, 0) // indent to align with text after icon
            }

            panel.add(headerLine)
            panel.add(previewLabel)
            return panel
        }
    }
}
