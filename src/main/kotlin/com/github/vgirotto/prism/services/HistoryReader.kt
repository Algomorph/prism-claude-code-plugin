package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary

/**
 * Reads conversation history persisted on disk by a specific agent CLI.
 * Each CLI stores sessions in its own format and directory layout; one
 * [HistoryReader] implementation per CLI keeps that parsing isolated.
 */
interface HistoryReader {
    fun listConversations(): List<ConversationSummary>
    fun loadConversation(sessionId: String): List<ConversationMessage>
    fun searchConversations(query: String): List<ConversationSummary>
}
