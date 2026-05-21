package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.ChangeStatus
import com.github.vgirotto.prism.model.FileDiffEntry
import com.github.vgirotto.prism.model.InteractionDiff
import com.github.vgirotto.prism.services.DiffViewService
import com.github.vgirotto.prism.services.FileSnapshotService
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.io.File
import javax.swing.*

class DiffPanel(private val project: Project, private val onHistoryCleared: () -> Unit = {}) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<FileDiffEntry>()
    private val fileList = JBList(listModel)
    private val statusLabel = JBLabel("")
    private val interactionLabel = JBLabel("")
    private val snapshotService = FileSnapshotService.getInstance(project)
    private val diffViewService = DiffViewService.getInstance(project)

    private var currentDiff: InteractionDiff? = null
    private val revertedFiles = mutableMapOf<Int, MutableSet<String>>()
    private var historyCleared = false

    init {
        // Actions group: revert file, revert all | refresh
        val actionsGroup = DefaultActionGroup().apply {
            add(object : AnAction(PrismBundle.message("diff.revert.file"), PrismBundle.message("diff.revert.file.desc"), AllIcons.Actions.Rollback), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = revertSelectedFile()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = isLatestInteraction() && !isSelectedFileReverted()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction(PrismBundle.message("diff.revert.all"), PrismBundle.message("diff.revert.all.desc"), AllIcons.Actions.Cancel), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = revertAll()
                override fun update(e: AnActionEvent) {
                    val allReverted = currentDiff?.let { d ->
                        (revertedFiles[d.interactionIndex]?.size ?: 0) >= d.changes.size
                    } ?: false
                    e.presentation.isEnabled = isLatestInteraction() && !allReverted &&
                        (currentDiff?.changes?.isNotEmpty() == true)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction(PrismBundle.message("diff.refresh"), PrismBundle.message("diff.refresh.desc"), AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = refreshDiff()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction(PrismBundle.message("diff.clear"), PrismBundle.message("diff.clear.desc"), AllIcons.Actions.GC), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val result = Messages.showYesNoDialog(
                        project,
                        PrismBundle.message("diff.clear.confirm"),
                        PrismBundle.message("diff.clear.title"),
                        Messages.getQuestionIcon()
                    )
                    if (result == Messages.YES) {
                        snapshotService.clearHistory()
                        onHistoryCleared()
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }

        val actionsToolbar = ActionManager.getInstance().createActionToolbar("ClaudeChangesActions", actionsGroup, true).apply {
            targetComponent = this@DiffPanel
        }

        // Nav buttons using ActionButton (native hover highlight, compact size)
        val buttonSize = Dimension(22, 22)

        val prevAction = object : AnAction(PrismBundle.message("diff.previous"), PrismBundle.message("diff.previous.desc"), AllIcons.Actions.Back), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = navigatePrev()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val nextAction = object : AnAction(PrismBundle.message("diff.next"), PrismBundle.message("diff.next.desc"), AllIcons.Actions.Forward), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = navigateNext()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

        val prevButton = ActionButton(prevAction, prevAction.templatePresentation.clone(), "ClaudeChangesPrev", buttonSize)
        val nextButton = ActionButton(nextAction, nextAction.templatePresentation.clone(), "ClaudeChangesNext", buttonSize)

        // Header: [◀] #N (Last) [▶] | [revert] [revertAll] | [refresh]   status →
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 1, 0)

            val leftHeader = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(prevButton)
                add(interactionLabel.apply {
                    border = JBUI.Borders.empty(0, 2)
                })
                add(nextButton)
                add(Box.createHorizontalStrut(4))
                add(JSeparator(SwingConstants.VERTICAL).apply {
                    maximumSize = java.awt.Dimension(2, 20)
                })
                add(actionsToolbar.component)
            }

            add(leftHeader, BorderLayout.WEST)
            add(statusLabel.apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(0, 8)
            }, BorderLayout.EAST)
        }

        // File list with file type icons
        fileList.cellRenderer = DiffEntryRenderer(revertedFiles) { currentDiff?.interactionIndex ?: 0 }
        fileList.emptyText.text = PrismBundle.message("diff.no.changes")
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                fileList.selectedValue?.let { diffViewService.showDiffForFile(it) }
            }
        }

        add(headerPanel, BorderLayout.NORTH)
        add(JBScrollPane(fileList), BorderLayout.CENTER)

        showInitialState()
    }

    fun clearAndReset() {
        historyCleared = true
        currentDiff = null
        revertedFiles.clear()
        listModel.clear()
        showInitialState()
    }

    private fun showInitialState() {
        statusLabel.text = PrismBundle.message("diff.waiting")
        interactionLabel.text = ""
    }

    /**
     * Refresh the display: show the latest diff from history.
     * Called by idle listener and manual Refresh button.
     * Only computes a new diff if there isn't one yet (first interaction).
     */
    fun refreshDiff() {
        project.basePath?.let { base ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(base)?.refresh(false, true)
            }
        }

        // Try showing the latest existing diff first
        val latest = snapshotService.getLatestDiff()
        if (latest != null && latest.changes.isNotEmpty()) {
            historyCleared = false
            showDiff(latest)
            return
        }

        // After an explicit clear, don't auto-recompute a diff — wait for the next real interaction
        if (historyCleared) return

        // No existing diff — try to compute one
        val diff = snapshotService.computeDiff()
        if (diff.changes.isNotEmpty() || currentDiff == null) showDiff(diff)
    }

    /**
     * Compute and show a NEW diff (called after interaction ends).
     */
    fun computeAndShowDiff() {
        historyCleared = false
        project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(it)?.refresh(false, true)
        }
        val diff = snapshotService.computeDiff()
        if (diff.changes.isNotEmpty()) showDiff(diff)
    }

    fun showDiff(diff: InteractionDiff) {
        currentDiff = diff
        listModel.clear()

        val latest = snapshotService.getLatestDiff()
        val isLatest = latest != null && diff.interactionIndex == latest.interactionIndex

        if (diff.changes.isEmpty()) {
            statusLabel.text = PrismBundle.message("diff.no.changes")
        } else {
            for (entry in diff.changes) listModel.addElement(entry)
            val revCount = revertedFiles[diff.interactionIndex]?.size ?: 0
            statusLabel.text = if (revCount > 0)
                PrismBundle.message("diff.files.reverted", diff.changes.size, revCount)
            else PrismBundle.message("diff.files", diff.changes.size)
        }

        interactionLabel.text = if (diff.interactionIndex > 0) {
            val sessionSuffix = if (diff.sessionName.isNotBlank()) " — ${diff.sessionName}" else ""
            val label = if (isLatest) PrismBundle.message("diff.interaction.last", diff.interactionIndex)
                        else PrismBundle.message("diff.interaction", diff.interactionIndex)
            "$label$sessionSuffix"
        } else ""
    }

    private fun isLatestInteraction(): Boolean {
        val diff = currentDiff ?: return false
        return diff.interactionIndex == (snapshotService.getLatestDiff()?.interactionIndex ?: -1)
    }

    private fun isSelectedFileReverted(): Boolean {
        val entry = fileList.selectedValue ?: return false
        return revertedFiles[currentDiff?.interactionIndex ?: -1]?.contains(entry.path) == true
    }

    private fun navigatePrev() {
        snapshotService.getDiff((currentDiff?.interactionIndex ?: 1) - 1)?.let { showDiff(it) }
    }

    private fun navigateNext() {
        snapshotService.getDiff((currentDiff?.interactionIndex ?: 0) + 1)?.let { showDiff(it) }
    }

    private fun revertAll() {
        val diff = currentDiff ?: return
        val count = snapshotService.revertInteraction(diff)
        revertedFiles.getOrPut(diff.interactionIndex) { mutableSetOf() }
            .addAll(diff.changes.map { it.path })
        notify(PrismBundle.message("diff.reverted", count, diff.interactionIndex))
        showDiff(diff)
    }

    private fun revertSelectedFile() {
        val entry = fileList.selectedValue ?: return
        val idx = currentDiff?.interactionIndex ?: return
        if (snapshotService.revertEntry(entry)) {
            revertedFiles.getOrPut(idx) { mutableSetOf() }.add(entry.path)
            notify(PrismBundle.message("diff.reverted.file", entry.path))
            fileList.repaint()
            val revCount = revertedFiles[idx]?.size ?: 0
            statusLabel.text = PrismBundle.message("diff.files.reverted", currentDiff?.changes?.size ?: 0, revCount)
        }
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Prism")
            .createNotification(PrismBundle.message("notification.title"), message, NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * Renderer with file type icon, status prefix, and reverted state.
     * Mimics the Git Changes panel style.
     */
    private class DiffEntryRenderer(
        private val revertedFiles: Map<Int, Set<String>>,
        private val currentInteractionIndex: () -> Int,
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is FileDiffEntry) {
                val fileName = File(value.path).name
                val parentPath = File(value.path).parent?.let { " $it" } ?: ""
                val idx = currentInteractionIndex()
                val isReverted = revertedFiles[idx]?.contains(value.path) == true

                // File type icon
                val fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon
                icon = fileIcon

                if (isReverted) {
                    text = "<html><s>$fileName</s> <font color='gray'>$parentPath (reverted)</font></html>"
                    if (!isSelected) foreground = JBColor.GRAY
                    toolTipText = "${value.path} — reverted"
                } else {
                    text = "<html>$fileName <font color='gray'>$parentPath</font></html>"
                    toolTipText = "Click to open diff for ${value.path}"
                    if (!isSelected) {
                        foreground = when (value.status) {
                            ChangeStatus.ADDED -> JBColor.GREEN.darker()
                            ChangeStatus.MODIFIED -> JBColor.BLUE
                            ChangeStatus.DELETED -> JBColor.RED
                        }
                    }
                }
            } else {
                toolTipText = null
            }
            return this
        }
    }
}
