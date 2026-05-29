package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.ContextProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) return

        val contextProvider = ContextProvider.getInstance(project)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        // Send line reference when possible, fallback to full text
        val lineRef = contextProvider.formatSelectionReference(editor, file)
        val message = if (lineRef != null) {
            lineRef + " "
        } else {
            selectedText + " "
        }
        val processManager = AgentProcessManager.getInstance(project)
        processManager.sendText(message)

        // Activate the Claude tool window
        ToolWindowManager.getInstance(project)
            .getToolWindow("Prism")
            ?.activate(null)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            e.project != null && editor?.selectionModel?.hasSelection() == true
    }
}
