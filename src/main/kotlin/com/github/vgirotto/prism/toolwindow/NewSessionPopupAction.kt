package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexValidationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * "+ New Session" entry point on the tool-window title bar.
 *
 * If both supported agent CLIs are installed, clicking opens a small
 * popup so the user can pick one. If only one is available, the action
 * skips the popup and creates a session for that CLI directly. The
 * configured [AgentSettingsState.defaultCli] is highlighted by being
 * offered first.
 */
class NewSessionPopupAction(
    private val project: Project,
    private val createSessionTab: (AgentCli) -> Unit,
) : DumbAwareAction(
    PrismBundle.message("toolwindow.new.session"),
    PrismBundle.message("toolwindow.new.session.desc"),
    AllIcons.General.Add,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val installed = installedCliS()
        when {
            installed.isEmpty() -> {
                // Let createSessionTab surface the not-installed error for the default CLI.
                createSessionTab(AgentSettingsState.getInstance().defaultCli)
            }
            installed.size == 1 -> createSessionTab(installed.first())
            else -> showPicker(e)
        }
    }

    private fun showPicker(e: AnActionEvent) {
        val component = e.inputEvent?.component as? JComponent ?: return
        val defaultCli = AgentSettingsState.getInstance().defaultCli
        val ordered = listOf(defaultCli) + (AgentCli.values().toList() - defaultCli)

        val group = DefaultActionGroup().apply {
            for (cli in ordered) {
                add(object : AnAction(cli.displayName(), cli.displayDescription(), null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) = createSessionTab(cli)
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
        }
        val popup = ActionManager.getInstance().createActionPopupMenu("NewAgentSession", group)
        popup.component.show(component, 0, component.height)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun installedCliS(): List<AgentCli> {
        val list = mutableListOf<AgentCli>()
        if (ClaudeValidationService.getInstance().isClaudeAvailable()) list.add(AgentCli.CLAUDE)
        if (CodexValidationService.getInstance().isCodexAvailable()) list.add(AgentCli.CODEX)
        return list
    }
}

private fun AgentCli.displayName(): String = when (this) {
    AgentCli.CLAUDE -> "Claude Code"
    AgentCli.CODEX -> "Codex"
}

private fun AgentCli.displayDescription(): String = when (this) {
    AgentCli.CLAUDE -> "Start a new Claude Code session"
    AgentCli.CODEX -> "Start a new Codex session"
}
