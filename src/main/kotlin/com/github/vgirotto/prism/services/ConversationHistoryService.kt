package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * CLI-aware façade for reading agent conversation history.
 *
 * Delegates to a per-CLI [HistoryReader] (see [ClaudeHistoryReader] and
 * [CodexHistoryReader]). The no-argument convenience methods preserve
 * the pre-Codex behavior by defaulting to Claude history.
 */
@Service(Service.Level.PROJECT)
class ConversationHistoryService(private val project: Project) {

    fun reader(cli: AgentCli): HistoryReader = when (cli) {
        AgentCli.CLAUDE -> ClaudeHistoryReader(project.basePath)
        AgentCli.CODEX -> CodexHistoryReader(project.basePath)
    }

    fun listConversations(cli: AgentCli = AgentCli.CLAUDE): List<ConversationSummary> =
        reader(cli).listConversations()

    fun loadConversation(sessionId: String, cli: AgentCli = AgentCli.CLAUDE): List<ConversationMessage> =
        reader(cli).loadConversation(sessionId)

    fun searchConversations(query: String, cli: AgentCli = AgentCli.CLAUDE): List<ConversationSummary> =
        reader(cli).searchConversations(query)

    /** Back-compat: returns the Claude project history directory. */
    fun getProjectHistoryDir(): File? =
        (reader(AgentCli.CLAUDE) as ClaudeHistoryReader).getProjectHistoryDir()

    companion object {
        fun getInstance(project: Project): ConversationHistoryService =
            project.getService(ConversationHistoryService::class.java)
    }
}
