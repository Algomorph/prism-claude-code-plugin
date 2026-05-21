package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.i18n.ClaudeBundle
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.ContextProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class MentionFolderAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val contextProvider = ContextProvider.getInstance(project)
        val ref = "@${contextProvider.relativePath(file)} "

        AgentProcessManager.getInstance(project).sendText(ref)
        ToolWindowManager.getInstance(project).getToolWindow("Prism")?.activate(null)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = ClaudeBundle.message("action.mention.folder")
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }
}
