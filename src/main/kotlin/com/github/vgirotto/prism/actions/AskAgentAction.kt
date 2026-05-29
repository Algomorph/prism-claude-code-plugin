package com.github.vgirotto.prism.actions

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.services.AgentProcessManager
import com.github.vgirotto.prism.services.ContextProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Context menu actions for "Ask Claude" submenu in the editor.
 * Each subclass sends the selection with a specific prompt prefix.
 */
abstract class AskAgentAction(private val prompt: String) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        val contextProvider = ContextProvider.getInstance(project)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val message = if (!selectedText.isNullOrBlank()) {
            val lineRef = contextProvider.formatSelectionReference(editor, file)
            if (lineRef != null) {
                "$prompt $lineRef"
            } else {
                contextProvider.formatSelectionForClaude(selectedText, file, prompt)
            }
        } else if (file != null) {
            "$prompt ${contextProvider.formatFileReference(file)}"
        } else {
            return
        }

        val processManager = AgentProcessManager.getInstance(project)
        processManager.sendText(message + "\n")

        ToolWindowManager.getInstance(project)
            .getToolWindow("Prism")
            ?.activate(null)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class ExplainCodeAction : AskAgentAction(PrismBundle.message("action.explain"))
class ReviewCodeAction : AskAgentAction(PrismBundle.message("action.review"))
class FixCodeAction : AskAgentAction(PrismBundle.message("action.fix"))
class GenerateTestsAction : AskAgentAction(PrismBundle.message("action.tests"))
class RefactorCodeAction : AskAgentAction(PrismBundle.message("action.refactor"))
