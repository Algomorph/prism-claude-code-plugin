package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.ContextProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class InsertFileReferenceAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: ContextProvider.getInstance(project).getActiveFile()
            ?: return

        val contextProvider = ContextProvider.getInstance(project)
        val reference = contextProvider.formatFileReference(file) + " "

        val processManager = AgentProcessManager.getInstance(project)
        processManager.sendText(reference)

        ToolWindowManager.getInstance(project)
            .getToolWindow("Prism")
            ?.activate(null)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
