package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary

/**
 * Reads conversation history persisted on disk by a specific agent CLI.
 * Each CLI stores sessions in its own format and directory layout; one
 * [HistoryReader] implementation per CLI keeps that parsing isolated.
 *
 * The listing methods take a [limit] so the panel can page. Codex keeps every project's
 * sessions in one date-ordered tree, and a long-lived install accumulates enough of them
 * that building the whole list up front is visible work.
 */
interface HistoryReader {
    fun listConversations(limit: Int = Int.MAX_VALUE): List<ConversationSummary>
    fun loadConversation(sessionId: String): List<ConversationMessage>
    fun searchConversations(query: String, limit: Int = Int.MAX_VALUE): List<ConversationSummary>
}
