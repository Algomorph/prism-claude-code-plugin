package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.toolwindow.AgentToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager

class NewSessionAction : AnAction() {

    private val log = Logger.getInstance(NewSessionAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Prism") ?: return

        toolWindow.show {
            val factory = AgentToolWindowFactory()
            factory.createSessionTab(project, toolWindow, true)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
