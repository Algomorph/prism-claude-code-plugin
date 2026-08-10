package com.github.vgirotto.prism.model

import java.time.Instant

/**
 * Represents a single conversation session from an agent's history.
 */
data class ConversationSummary(
    val sessionId: String,
    val filePath: String,
    val startTime: Instant,
    val lastTime: Instant,
    val messageCount: Int,
    val firstUserMessage: String,
    val model: String,
    val cwd: String,
)

/**
 * Represents a single message in a conversation.
 */
data class ConversationMessage(
    val uuid: String,
    val timestamp: Instant,
    val type: String, // "user", "assistant", "progress", "file-history-snapshot"
    val role: String, // "user" or "assistant"
    val content: String,
    val model: String?,
    val toolUses: List<ToolUseEntry>,
)

data class ToolUseEntry(
    val name: String,
    val id: String,
)
