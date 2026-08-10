package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ClaudeValidationService
import com.github.vgirotto.prism.services.CodexValidationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.SimpleListCellRenderer
import java.awt.KeyboardFocusManager
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

/**
 * "+ New Session" entry point on the tool-window title bar.
 *
 * If both supported agent CLIs are installed, clicking opens a small
 * popup so the user can pick one. If exactly one is installed, that CLI is
 * launched directly — a Codex-only (or Claude-only) user should not hit a
 * "not found" error for the other agent just because it is the configured
 * [AgentSettingsState.defaultCli]. If none is installed, the default CLI is
 * used so its own installation/configuration error surfaces.
 */
class NewSessionPopupAction(
    private val createSessionTab: (AgentCli) -> Unit,
) : DumbAwareAction(
    PrismBundle.message("toolwindow.new.session"),
    PrismBundle.message("toolwindow.new.session.desc"),
    AllIcons.General.Add,
) {

    private val log = Logger.getInstance(NewSessionPopupAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        // Availability checks stat the filesystem and read the login-shell environment,
        // which can block while the platform loads it, and IntelliJ forbids blocking I/O
        // on the EDT. Resolve the installed CLIs on a pooled thread, then marshal the
        // popup/session UI back to the EDT.
        val clickedAtNanos = System.nanoTime()
        val anchor = e.inputEvent?.component as? JComponent
        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = installedCliS()
            val resolvedAtNanos = System.nanoTime()
            ApplicationManager.getApplication().invokeLater {
                // The click-to-popup phase has no other trace in the log, and it is the
                // phase users perceive as "New Session is slow".
                val availabilityMs = TimeUnit.NANOSECONDS.toMillis(resolvedAtNanos - clickedAtNanos)
                val uiMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clickedAtNanos)
                log.info(
                    "timing: new session click → availability $availabilityMs ms → ui $uiMs ms" +
                        " (installed=${installed.joinToString(",") { it.name.lowercase() }.ifEmpty { "none" }})"
                )
                when {
                    // Let createSessionTab surface the not-installed error for the default CLI.
                    installed.isEmpty() -> createSessionTab(AgentSettingsState.getInstance().defaultCli)
                    // Exactly one installed: launch it, not the (possibly absent) default.
                    installed.size == 1 -> createSessionTab(installed.first())
                    else -> showPicker(anchor, installed)
                }
            }
        }
    }

    private fun showPicker(anchor: JComponent?, installed: List<AgentCli>) {
        val defaultCli = AgentSettingsState.getInstance().defaultCli
        val ordered = listOf(defaultCli).filter { it in installed } + (installed - defaultCli)

        // Without focus the terminal stays the key target, and Escape reaches the running
        // agent instead of closing the picker.
        val previousFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(ordered)
            .setTitle("New Agent Session")
            // The (nullValue, Function) overload is scheduled for removal; the customizer
            // form has to blank the label itself for a null value.
            .setRenderer(
                SimpleListCellRenderer.create<AgentCli> { label, value, _ ->
                    label.text = value?.displayName().orEmpty()
                }
            )
            .setRequestFocus(true)
            .setItemChosenCallback { createSessionTab(it) }
            .createPopup()

        // Give focus back on cancel; a chosen item is left alone, since the new tab takes it.
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (event.isOk) return
                ApplicationManager.getApplication().invokeLater {
                    previousFocusOwner?.requestFocusInWindow()
                }
            }
        })

        if (anchor != null) {
            popup.showUnderneathOf(anchor)
        } else {
            popup.showInFocusCenter()
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
