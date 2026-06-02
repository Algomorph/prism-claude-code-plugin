package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexValidationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import javax.swing.JComponent

/**
 * "+ New Session" entry point on the tool-window title bar.
 *
 * If both supported agent CLIs are installed, clicking opens a small
 * popup so the user can pick one. If no picker is shown, the configured
 * [AgentSettingsState.defaultCli] is used so a missing default agent
 * surfaces its own installation/configuration error instead of silently
 * launching a different CLI.
 */
class NewSessionPopupAction(
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
            installed.size == 1 -> createSessionTab(AgentSettingsState.getInstance().defaultCli)
            else -> showPicker(e, installed)
        }
    }

    private fun showPicker(e: AnActionEvent, installed: List<AgentCli>) {
        val defaultCli = AgentSettingsState.getInstance().defaultCli
        val ordered = listOf(defaultCli).filter { it in installed } + (installed - defaultCli)

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<AgentCli>("New Agent Session", ordered) {
                override fun getTextFor(value: AgentCli): String = value.displayName()

                override fun onChosen(selectedValue: AgentCli, finalChoice: Boolean): PopupStep<*>? =
                    doFinalStep { createSessionTab(selectedValue) }
            }
        )

        val component = e.inputEvent?.component as? JComponent
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showInBestPositionFor(e.dataContext)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun installedCliS(): List<AgentCli> {
        val settings = AgentSettingsState.getInstance()
        val list = mutableListOf<AgentCli>()
        if (ClaudeValidationService.getInstance().isClaudeAvailable(settings.claudePath)) list.add(AgentCli.CLAUDE)
        if (CodexValidationService.getInstance().isCodexAvailable(settings.codexPath)) list.add(AgentCli.CODEX)
        return list
    }
}

private fun AgentCli.displayName(): String = when (this) {
    AgentCli.CLAUDE -> "Claude Code"
    AgentCli.CODEX -> "Codex"
}
