package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.model.PromptTemplate
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ContextProvider
import com.github.vgirotto.prism.services.PromptTemplateService
import com.github.vgirotto.prism.settings.AgentSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*

internal fun activeAgentCli(project: Project): AgentCli =
    AgentProcessManager.getInstance(project).activeSession?.cli
        ?: AgentSettingsState.getInstance().defaultCli

/**
 * Whether a toolbar-initiated write may go out to [session] right now.
 *
 * False while a staged keystroke sequence is still being delivered: the next write would
 * queue up behind it and land inside the interactive screen that sequence just opened, so
 * two Resume clicks would submit `/resume` twice instead of once.
 */
internal fun acceptsToolbarInput(session: AgentSession?): Boolean = session?.sequenceInFlight != true

/**
 * Visibility follows the active CLI; enablement additionally drops while a staged
 * keystroke sequence is still going out. Enablement is set apart from visibility on
 * purpose — a button that greys out reads as "busy", one that vanishes reads as broken.
 */
private fun AnActionEvent.gateToolbarItem(project: Project, item: ToolbarItem) {
    val visible = isToolbarItemAvailable(activeAgentCli(project), item)
    presentation.isVisible = visible
    presentation.isEnabled =
        visible && acceptsToolbarInput(AgentProcessManager.getInstance(project).activeSession)
}

/**
 * Runs [send] only if the active session is not mid-sequence, making the extra click a
 * silent no-op.
 *
 * The greyed-out presentation above cannot carry this on its own: `update()` runs on
 * IntelliJ's action timer, so the button stays live for up to half a second after the
 * first click and a double-click slips through before it repaints. Editor-initiated
 * prompts deliberately keep using plain `sendText` — those queue rather than drop,
 * because losing a message the user typed is worse than delivering it a moment late.
 */
private fun sendIfAccepted(project: Project, send: AgentProcessManager.() -> Unit) {
    val manager = AgentProcessManager.getInstance(project)
    if (!acceptsToolbarInput(manager.activeSession)) return
    manager.send()
}

class AgentToolbar(private val project: Project) : JPanel(BorderLayout()) {

    init {
        val mainGroup = DefaultActionGroup().apply {
            add(ResumeAction(project))
            add(CompactAction(project))
            add(ClearAction(project))
            addSeparator()
            add(ModelAction(project))
            add(EffortAction(project))
            add(CostAction(project))
            addSeparator()
            add(TemplatesAction(project))
        }

        val mainToolbar = ActionManager.getInstance().createActionToolbar("AgentToolbar", mainGroup, true).apply {
            targetComponent = this@AgentToolbar
        }

        val rightGroup = DefaultActionGroup().apply {
            add(SettingsAction(project))
        }

        val rightToolbar = ActionManager.getInstance().createActionToolbar("AgentToolbarRight", rightGroup, true).apply {
            targetComponent = this@AgentToolbar
        }

        add(mainToolbar.component, BorderLayout.WEST)
        add(rightToolbar.component, BorderLayout.EAST)
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
    }
}

private class TemplatesAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.templates"), PrismBundle.message("toolbar.templates.desc"), AllIcons.Actions.ListFiles
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component as? JComponent ?: return
        showTemplatesMenu(component)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun showTemplatesMenu(anchor: JComponent) {
        val templateService = PromptTemplateService.getInstance()
        val templates = templateService.getTemplates()

        // Capture now — by the time the user picks a template, focus has shifted to the
        // popup and selectedEditor returns null, making {file} impossible to resolve.
        val capturedFilePath = ContextProvider.getInstance(project).getActiveFile()
            ?.let { ContextProvider.getInstance(project).relativePath(it) }

        val group = DefaultActionGroup().apply {
            for (template in templates) {
                add(object : AnAction(template.name, template.prompt, null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) = executeTemplate(template, capturedFilePath)
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            if (templates.isNotEmpty()) addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.templates.create"), PrismBundle.message("toolbar.templates.create.desc"), AllIcons.General.Add), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val dialog = TemplateDialog(project, null)
                    if (dialog.showAndGet()) {
                        dialog.getTemplate()?.let { templateService.addTemplate(it) }
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction(PrismBundle.message("toolbar.templates.edit"), PrismBundle.message("toolbar.templates.edit.desc"), AllIcons.Actions.Edit), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = showEditDialog(templateService)
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val popupMenu = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("ClaudeTemplates", group)
        popupMenu.component.show(anchor, 0, anchor.height)
    }

    private fun executeTemplate(template: PromptTemplate, capturedFilePath: String? = null) {
        val contextProvider = ContextProvider.getInstance(project)
        val selection = contextProvider.getSelectedText()
        val resolved = PromptTemplateService.getInstance().resolveTemplate(
            template, selection = selection, filePath = capturedFilePath,
        )
        // No trailing \n: keeps the resolved prompt in the composer without submitting,
        // matching SendSelectionAction and the user's expectation for both CLIs.
        AgentProcessManager.getInstance(project).sendText(resolved)
        ToolWindowManager.getInstance(project).getToolWindow("Prism")?.activate(null)
    }

    private fun showEditDialog(templateService: PromptTemplateService) {
        val templates = templateService.getTemplates()
        val names = templates.map { it.name }.toTypedArray()
        if (names.isEmpty()) {
            Messages.showInfoMessage(project, PrismBundle.message("toolbar.templates.none"), PrismBundle.message("toolbar.templates.title"))
            return
        }
        val selected = Messages.showEditableChooseDialog(
            PrismBundle.message("toolbar.templates.select"), PrismBundle.message("toolbar.templates.edit.title"),
            Messages.getQuestionIcon(), names, names[0], null
        ) ?: return
        val template = templates.find { it.name == selected } ?: return
        val choice = Messages.showYesNoCancelDialog(
            project, "Template: ${template.name}\n\n${template.prompt}",
            PrismBundle.message("toolbar.templates.edit.dialog"), PrismBundle.message("toolbar.templates.edit.button"), PrismBundle.message("toolbar.templates.delete.button"), PrismBundle.message("toolbar.cancel"), Messages.getQuestionIcon()
        )
        when (choice) {
            Messages.YES -> {
                val dialog = TemplateDialog(project, template)
                if (dialog.showAndGet()) {
                    dialog.getTemplate()?.let {
                        templateService.removeTemplate(template.name)
                        templateService.addTemplate(it)
                    }
                }
            }
            Messages.NO -> templateService.removeTemplate(template.name)
        }
    }
}

/**
 * Drives Codex's interactive `/model` picker via keystrokes.
 *
 * Codex has no argument form for `/model` and no separate effort command:
 * `/model` opens a two-step popup — first "Select Model and Effort", then
 * "Select Reasoning Level". Both steps are stable, 1-based numbered lists,
 * and pressing a digit selects that row and advances/confirms in one press.
 * Pressing Enter on the model step keeps the current model (its row is
 * pre-highlighted) and advances to the effort step.
 *
 * Prism does NOT map models to fixed digits: the model list is account- and
 * CLI-version driven and its order shifts between versions (e.g. new gpt-5.6
 * rows pushed the older models down), so a hardcoded digit could silently
 * select the wrong model. The Model button therefore opens the native picker
 * ([OPEN_MODEL]) so the user chooses from the live list. The reasoning-level
 * step is a stable enumeration reached by keeping the current model, so effort
 * still uses digit shortcuts. Verified against Codex CLI 0.146.x.
 */
internal object CodexModelPicker {
    /** Effort key -> 1-based row in the "Select Reasoning Level" step. */
    val EFFORTS: List<Triple<String, Int, String>> = listOf(
        Triple("low", 1, "toolbar.effort.codex.low"),
        Triple("medium", 2, "toolbar.effort.codex.medium"),
        Triple("high", 3, "toolbar.effort.codex.high"),
        Triple("xhigh", 4, "toolbar.effort.codex.xhigh"),
    )

    /** Keystrokes to switch to effort row [effortDigit], keeping the current model. */
    fun selectEffort(effortDigit: Int): List<String> =
        listOf("/model", "\r", "\r", effortDigit.toString())

    /** Open the model step for manual selection. */
    val OPEN_MODEL: List<String> = listOf("/model", "\r")

    /** Open the picker and advance to the effort step (keeping the current model). */
    val OPEN_EFFORT: List<String> = listOf("/model", "\r", "\r")
}

/**
 * Codex has no `/cost`. Its `/usage` command accepts an optional view argument
 * — `daily`, `weekly`, or `cumulative` — each jumping straight to that
 * token-activity view; bare `/usage` opens a menu instead. Verified against
 * Codex 0.143.0.
 */
internal object CodexUsage {
    /** View argument -> bundle key for its menu-item description. */
    val VIEWS: List<Pair<String, String>> = listOf(
        "daily" to "toolbar.cost.codex.daily",
        "weekly" to "toolbar.cost.codex.weekly",
        "cumulative" to "toolbar.cost.codex.cumulative",
    )

    /** The submitting `/usage <view>` command for the given view. */
    fun command(view: String): String = "/usage $view\r"
}

private class ModelAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.model"), PrismBundle.message("toolbar.model.desc"), AllIcons.Nodes.Models
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        if (activeAgentCli(project) == AgentCli.CODEX) {
            // No safe fixed digit->model mapping (see CodexModelPicker): open the
            // native picker so the user selects from the account's live model list.
            sendIfAccepted(project) { sendSequence(CodexModelPicker.OPEN_MODEL) }
            return
        }
        val component = e.inputEvent?.component as? JComponent ?: return
        showClaudeModelMenu(component)
    }

    private fun showClaudeModelMenu(component: JComponent) {
        val group = DefaultActionGroup().apply {
            for (model in listOf(
                "opus" to PrismBundle.message("toolbar.model.opus"),
                "sonnet" to PrismBundle.message("toolbar.model.sonnet"),
                "haiku" to PrismBundle.message("toolbar.model.haiku"),
            )) {
                add(object : AnAction(model.first, model.second, null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) {
                        sendIfAccepted(project) { sendText("/model ${model.first}\r") }
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.model.picker"), PrismBundle.message("toolbar.model.picker.desc"), null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    sendIfAccepted(project) { sendText("/model\r") }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("ClaudeModel", group)
        popup.component.show(component, 0, component.height)
    }

    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.MODEL)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class EffortAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.effort"), PrismBundle.message("toolbar.effort.desc"), AllIcons.Actions.ProfileCPU
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component as? JComponent ?: return
        if (activeAgentCli(project) == AgentCli.CODEX) showCodexEffortMenu(component)
        else showClaudeEffortMenu(component)
    }

    private fun showClaudeEffortMenu(component: JComponent) {
        val group = DefaultActionGroup().apply {
            for (level in listOf(
                "auto" to PrismBundle.message("toolbar.effort.auto"),
                "low" to PrismBundle.message("toolbar.effort.low"),
                "medium" to PrismBundle.message("toolbar.effort.medium"),
                "high" to PrismBundle.message("toolbar.effort.high"),
                "xhigh" to PrismBundle.message("toolbar.effort.xhigh"),
                "max" to PrismBundle.message("toolbar.effort.max"),
            )) {
                add(object : AnAction(level.first, level.second, null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) {
                        sendIfAccepted(project) { sendText("/effort ${level.first}\r") }
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.effort.picker"), PrismBundle.message("toolbar.effort.picker.desc"), null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    sendIfAccepted(project) { sendText("/effort\r") }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("ClaudeEffort", group)
        popup.component.show(component, 0, component.height)
    }

    private fun showCodexEffortMenu(component: JComponent) {
        val mgr = AgentProcessManager.getInstance(project)
        val group = DefaultActionGroup().apply {
            for ((key, digit, labelKey) in CodexModelPicker.EFFORTS) {
                add(object : AnAction(key, PrismBundle.message(labelKey), null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (!acceptsToolbarInput(mgr.activeSession)) return
                        mgr.sendSequence(CodexModelPicker.selectEffort(digit))
                        mgr.setSessionEffort(key)
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.effort.picker"), PrismBundle.message("toolbar.effort.picker.desc"), null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    sendIfAccepted(project) { sendSequence(CodexModelPicker.OPEN_EFFORT) }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("CodexEffort", group)
        popup.component.show(component, 0, component.height)
    }

    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.EFFORT)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CostAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.cost"), PrismBundle.message("toolbar.cost.desc"), AllIcons.Actions.Profile
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        if (activeAgentCli(project) == AgentCli.CODEX) {
            val component = e.inputEvent?.component as? JComponent ?: return
            showCodexUsageMenu(component)
        } else {
            sendIfAccepted(project) { sendText("/cost\r") }
        }
    }

    // Codex has no /cost. Its /usage command accepts a view argument
    // (daily/weekly/cumulative), so the button becomes a dropdown that opens the
    // chosen token-activity view directly instead of the default daily view.
    private fun showCodexUsageMenu(component: JComponent) {
        val mgr = AgentProcessManager.getInstance(project)
        val group = DefaultActionGroup().apply {
            for ((view, labelKey) in CodexUsage.VIEWS) {
                add(object : AnAction(view, PrismBundle.message(labelKey), null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) {
                        sendIfAccepted(project) { sendText(CodexUsage.command(view)) }
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("CodexUsage", group)
        popup.component.show(component, 0, component.height)
    }

    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.COST)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class ResumeAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.resume"), PrismBundle.message("toolbar.resume.desc"), AllIcons.Actions.Resume
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        sendIfAccepted(project) { sendText("/resume\r") }
    }
    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.RESUME)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CompactAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.compact"), PrismBundle.message("toolbar.compact.desc"), AllIcons.Actions.Collapseall
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val result = Messages.showOkCancelDialog(
            project,
            PrismBundle.message("toolbar.compact.message"),
            PrismBundle.message("toolbar.compact.title"),
            PrismBundle.message("toolbar.compact.button"),
            PrismBundle.message("toolbar.cancel"),
            AllIcons.Actions.Collapseall
        )
        if (result == Messages.OK) {
            sendIfAccepted(project) { sendText("/compact\r") }
        }
    }
    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.COMPACT)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class ClearAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.clear"), PrismBundle.message("toolbar.clear.desc"), AllIcons.Actions.GC
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val result = Messages.showOkCancelDialog(
            project,
            PrismBundle.message("toolbar.clear.message"),
            PrismBundle.message("toolbar.clear.title"),
            PrismBundle.message("toolbar.clear.button"),
            PrismBundle.message("toolbar.cancel"),
            AllIcons.Actions.GC
        )
        if (result == Messages.OK) {
            sendIfAccepted(project) { sendText("/clear\r") }
        }
    }
    override fun update(e: AnActionEvent) = e.gateToolbarItem(project, ToolbarItem.CLEAR)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class SettingsAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.settings"), PrismBundle.message("toolbar.settings.desc"), AllIcons.General.Settings
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentSettingsConfigurable::class.java)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class TemplateDialog(
    project: Project,
    private val existing: PromptTemplate?,
) : DialogWrapper(project) {
    private val nameField = JBTextField(existing?.name ?: "")
    private val promptArea = JTextArea(existing?.prompt ?: "", 5, 40).apply {
        lineWrap = true; wrapStyleWord = true
    }
    private val includeSelectionCheck = JCheckBox(PrismBundle.message("toolbar.templates.include.selection"), existing?.includeSelection ?: true)
    private val includeFileRefCheck = JCheckBox(PrismBundle.message("toolbar.templates.include.file"), existing?.includeFileRef ?: true)

    init {
        title = if (existing != null) PrismBundle.message("toolbar.templates.edit.dialog") else PrismBundle.message("toolbar.templates.new.dialog")
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(PrismBundle.message("toolbar.templates.name"), nameField)
        .addLabeledComponent(PrismBundle.message("toolbar.templates.prompt"), JScrollPane(promptArea))
        .addComponent(includeSelectionCheck)
        .addComponent(includeFileRefCheck)
        .addSeparator()
        .addComponent(JBLabel("<html><small><b>${PrismBundle.message("toolbar.templates.variables")}</b></small></html>"))
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun getTemplate(): PromptTemplate? {
        val name = nameField.text.trim()
        val prompt = promptArea.text.trim()
        if (name.isEmpty() || prompt.isEmpty()) return null
        return PromptTemplate(name, prompt, includeSelectionCheck.isSelected, includeFileRefCheck.isSelected)
    }
}
