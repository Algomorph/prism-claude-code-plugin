package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.services.FileSnapshotService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class ShowDiffAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            FileSnapshotService.getInstance(project).computeDiff()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                ToolWindowManager.getInstance(project)
                    .getToolWindow("Prism")
                    ?.activate(null)
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
