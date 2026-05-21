package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.time.Instant

/**
 * Reads OpenAI Codex CLI conversation history from
 * `~/.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl`.
 *
 * Each JSONL line is a record with a top-level `type` and a `payload`.
 * The relevant kinds for browsing history:
 *   - `session_meta`         — session id, cwd, originator timestamp
 *   - `turn_context`         — model, effort, cwd for a turn
 *   - `event_msg`/user_message  — the user's typed message
 *   - `event_msg`/agent_message — the agent's reply
 *
 * Sessions are filtered to the current IDE project by matching the
 * session's `cwd` against [projectBasePath].
 */
class CodexHistoryReader(
    private val projectBasePath: String?,
    sessionsRoot: File? = null,
) : HistoryReader {

    private val log = Logger.getInstance(CodexHistoryReader::class.java)

    private val sessionsRoot: File =
        sessionsRoot ?: File(System.getProperty("user.home"), ".codex/sessions")

    override fun listConversations(): List<ConversationSummary> {
        val base = projectBasePath ?: return emptyList()
        if (!sessionsRoot.isDirectory) return emptyList()

        return collectJsonlFiles().mapNotNull { file ->
            try {
                parseSummary(file)?.takeIf { it.cwd == base }
            } catch (e: Exception) {
                log.debug("Failed to parse Codex session file: ${file.name}", e)
                null
            }
        }.toList().sortedByDescending { it.lastTime }
    }

    override fun loadConversation(sessionId: String): List<ConversationMessage> {
        val file = findSessionFile(sessionId) ?: return emptyList()

        val messages = mutableListOf<ConversationMessage>()
        BufferedReader(FileReader(file)).use { reader ->
            var index = 0
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val msg = parseMessage(json, index)
                    if (msg != null) {
                        messages.add(msg)
                        index++
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }
        return messages
    }

    override fun searchConversations(query: String): List<ConversationSummary> {
        if (query.isBlank()) return listConversations()
        val lowerQuery = query.lowercase()

        return listConversations().filter { summary ->
            try {
                File(summary.filePath).useLines { lines ->
                    lines.any { it.lowercase().contains(lowerQuery) }
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Returns the rollout file for a session id, searching recursively
     * under [sessionsRoot]. Codex filenames embed the session UUID.
     */
    fun findSessionFile(sessionId: String): File? {
        if (!sessionsRoot.isDirectory) return null
        return collectJsonlFiles().firstOrNull { it.name.contains(sessionId) }
    }

    private fun collectJsonlFiles(): Sequence<File> = sequence {
        sessionsRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .forEach { yield(it) }
    }

    private fun parseSummary(file: File): ConversationSummary? {
        var sessionId = ""
        var startTime: Instant? = null
        var lastTime: Instant? = null
        var messageCount = 0
        var firstUserMessage = ""
        var model = ""
        var cwd = ""

        BufferedReader(FileReader(file)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val type = json.get("type")?.asString ?: return@forEachLine
                    val payload = json.getAsJsonObject("payload")

                    val timestamp = json.get("timestamp")?.asString?.let {
                        try { Instant.parse(it) } catch (_: Exception) { null }
                    }
                    if (timestamp != null) {
                        if (startTime == null) startTime = timestamp
                        lastTime = timestamp
                    }

                    when (type) {
                        "session_meta" -> {
                            if (payload != null) {
                                if (sessionId.isEmpty()) {
                                    payload.get("id")?.asString?.let { sessionId = it }
                                }
                                if (cwd.isEmpty()) {
                                    payload.get("cwd")?.asString?.let { cwd = it }
                                }
                            }
                        }
                        "turn_context" -> {
                            if (payload != null && model.isEmpty()) {
                                payload.get("model")?.asString?.let { model = it }
                            }
                        }
                        "event_msg" -> {
                            val pType = payload?.get("type")?.asString ?: return@forEachLine
                            if (pType == "user_message") {
                                messageCount++
                                if (firstUserMessage.isEmpty()) {
                                    firstUserMessage = (payload.get("message") ?: payload.get("text"))
                                        ?.asString.orEmpty()
                                }
                            } else if (pType == "agent_message") {
                                messageCount++
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (sessionId.isEmpty() || startTime == null || messageCount == 0) return null

        return ConversationSummary(
            sessionId = sessionId,
            filePath = file.absolutePath,
            startTime = startTime!!,
            lastTime = lastTime ?: startTime!!,
            messageCount = messageCount,
            firstUserMessage = firstUserMessage.take(200),
            model = model,
            cwd = cwd,
        )
    }

    private fun parseMessage(json: JsonObject, index: Int): ConversationMessage? {
        val type = json.get("type")?.asString ?: return null
        if (type != "event_msg") return null

        val payload = json.getAsJsonObject("payload") ?: return null
        val pType = payload.get("type")?.asString ?: return null
        if (pType != "user_message" && pType != "agent_message") return null

        val timestamp = json.get("timestamp")?.asString?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        } ?: return null

        val text = (payload.get("message") ?: payload.get("text"))?.asString.orEmpty()
        val role = if (pType == "user_message") "user" else "assistant"

        return ConversationMessage(
            uuid = "$index",
            timestamp = timestamp,
            type = if (role == "user") "user" else "assistant",
            role = role,
            content = text,
            model = null,
            toolUses = mutableListOf(),
        )
    }
}
