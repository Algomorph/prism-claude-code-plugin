package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.PromptTemplate
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.services.ContextProvider
import com.github.vgirotto.prism.services.PromptTemplateService
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

        val group = DefaultActionGroup().apply {
            for (template in templates) {
                add(object : AnAction(template.name, template.prompt, null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) = executeTemplate(template)
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

    private fun executeTemplate(template: PromptTemplate) {
        val contextProvider = ContextProvider.getInstance(project)
        val selection = contextProvider.getSelectedText()
        val filePath = contextProvider.getActiveFile()?.let { contextProvider.relativePath(it) }
        val resolved = PromptTemplateService.getInstance().resolveTemplate(
            template, selection = selection, filePath = filePath,
        )
        AgentProcessManager.getInstance(project).sendText("$resolved\n")
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

private class ModelAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.model"), PrismBundle.message("toolbar.model.desc"), AllIcons.Nodes.Models
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component as? JComponent ?: return
        val group = DefaultActionGroup().apply {
            for (model in listOf(
                "opus" to PrismBundle.message("toolbar.model.opus"),
                "sonnet" to PrismBundle.message("toolbar.model.sonnet"),
                "haiku" to PrismBundle.message("toolbar.model.haiku"),
            )) {
                add(object : AnAction(model.first, model.second, null), DumbAware {
                    override fun actionPerformed(e: AnActionEvent) {
                        AgentProcessManager.getInstance(project).sendText("/model ${model.first}\r")
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.model.picker"), PrismBundle.message("toolbar.model.picker.desc"), null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    AgentProcessManager.getInstance(project).sendText("/model\r")
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("ClaudeModel", group)
        popup.component.show(component, 0, component.height)
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isToolbarItemAvailable(activeAgentCli(project), ToolbarItem.MODEL)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class EffortAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.effort"), PrismBundle.message("toolbar.effort.desc"), AllIcons.Actions.ProfileCPU
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component as? JComponent ?: return
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
                        AgentProcessManager.getInstance(project).sendText("/effort ${level.first}\r")
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            addSeparator()
            add(object : AnAction(PrismBundle.message("toolbar.effort.picker"), PrismBundle.message("toolbar.effort.picker.desc"), null), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    AgentProcessManager.getInstance(project).sendText("/effort\r")
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("ClaudeEffort", group)
        popup.component.show(component, 0, component.height)
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isToolbarItemAvailable(activeAgentCli(project), ToolbarItem.EFFORT)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CostAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.cost"), PrismBundle.message("toolbar.cost.desc"), AllIcons.Actions.Profile
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        AgentProcessManager.getInstance(project).sendText("/cost\r")
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class ResumeAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.resume"), PrismBundle.message("toolbar.resume.desc"), AllIcons.Actions.Resume
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        AgentProcessManager.getInstance(project).sendText("/resume\r")
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isToolbarItemAvailable(activeAgentCli(project), ToolbarItem.RESUME)
    }
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
            AgentProcessManager.getInstance(project).sendText("/compact\r")
        }
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isToolbarItemAvailable(activeAgentCli(project), ToolbarItem.COMPACT)
    }
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
            AgentProcessManager.getInstance(project).sendText("/clear\r")
        }
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isToolbarItemAvailable(activeAgentCli(project), ToolbarItem.CLEAR)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class SettingsAction(private val project: Project) : AnAction(
    PrismBundle.message("toolbar.settings"), PrismBundle.message("toolbar.settings.desc"), AllIcons.General.Settings
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project, "Prism \u2014 Claude Code"
        )
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
