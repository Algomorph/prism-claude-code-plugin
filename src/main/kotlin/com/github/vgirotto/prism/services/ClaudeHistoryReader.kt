package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ConversationMessage
import com.github.vgirotto.prism.model.ConversationSummary
import com.github.vgirotto.prism.model.ToolUseEntry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.time.Instant

/**
 * Reads Claude Code conversation history from
 * `~/.claude/projects/<escaped-project-path>/` *.jsonl files.
 *
 * Each JSONL file is one session: one JSON object per line containing
 * messages, tool uses, progress events, and file snapshots.
 */
class ClaudeHistoryReader(private val projectBasePath: String?) : HistoryReader {

    private val log = Logger.getInstance(ClaudeHistoryReader::class.java)

    /**
     * Returns the Claude projects directory for the current project.
     *
     * Claude Code replaces both '/' and '_' with '-'. We try multiple
     * escaping strategies with a fuzzy fallback to handle any variations.
     */
    fun getProjectHistoryDir(): File? {
        val basePath = projectBasePath ?: return null
        val claudeProjectsDir = File(System.getProperty("user.home"), ".claude/projects")
        if (!claudeProjectsDir.isDirectory) return null

        val primaryDir = File(claudeProjectsDir, basePath.replace("/", "-").replace("_", "-"))
        if (primaryDir.isDirectory) return primaryDir

        val fallbackDir = File(claudeProjectsDir, basePath.replace("/", "-"))
        if (fallbackDir.isDirectory) return fallbackDir

        val normalize = { s: String -> s.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase() }
        val normalizedPath = normalize(basePath)
        return claudeProjectsDir.listFiles { f -> f.isDirectory }
            ?.firstOrNull { normalize(it.name) == normalizedPath }
    }

    override fun listConversations(): List<ConversationSummary> {
        val dir = getProjectHistoryDir() ?: return emptyList()
        val jsonlFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
        return jsonlFiles.mapNotNull { file ->
            try {
                parseSummary(file)
            } catch (e: Exception) {
                log.debug("Failed to parse session file: ${file.name}", e)
                null
            }
        }.sortedByDescending { it.lastTime }
    }

    override fun loadConversation(sessionId: String): List<ConversationMessage> {
        val dir = getProjectHistoryDir() ?: return emptyList()
        val file = File(dir, "$sessionId.jsonl")
        if (!file.isFile) return emptyList()

        val messages = mutableListOf<ConversationMessage>()
        BufferedReader(FileReader(file)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val json = JsonParser.parseString(line).asJsonObject
                    val type = json.get("type")?.asString ?: return@forEachLine

                    if (type == "user") {
                        val content = json.getAsJsonObject("message")?.get("content")
                        val isToolResult = content != null && content.isJsonArray &&
                            content.asJsonArray.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result" }
                        if (!isToolResult) {
                            parseMessage(json, type)?.let { messages.add(it) }
                        }
                    } else if (type == "assistant") {
                        parseMessage(json, type)?.let { messages.add(it) }
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

        val dir = getProjectHistoryDir() ?: return emptyList()
        val jsonlFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()

        return jsonlFiles.mapNotNull { file ->
            try {
                if (fileContainsQuery(file, lowerQuery)) parseSummary(file) else null
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.lastTime }
    }

    private fun parseSummary(file: File): ConversationSummary? {
        val sessionId = file.nameWithoutExtension
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
                    val timestamp = json.get("timestamp")?.asString?.let {
                        try { Instant.parse(it) } catch (_: Exception) { null }
                    }

                    if (timestamp != null) {
                        if (startTime == null) startTime = timestamp
                        lastTime = timestamp
                    }

                    if (cwd.isEmpty()) {
                        json.get("cwd")?.asString?.let { cwd = it }
                    }

                    if (type == "user") {
                        messageCount++
                        if (firstUserMessage.isEmpty()) {
                            firstUserMessage = extractTextContent(json)
                        }
                    } else if (type == "assistant") {
                        messageCount++
                        if (model.isEmpty()) {
                            json.getAsJsonObject("message")?.get("model")?.asString?.let { model = it }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (startTime == null || messageCount == 0) return null

        if (firstUserMessage.startsWith("<local-command") ||
            firstUserMessage.startsWith("<command-name") ||
            firstUserMessage.startsWith("<system-reminder")) {
            return null
        }

        if (messageCount < 2 && firstUserMessage.isBlank()) return null

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

    private fun parseMessage(json: JsonObject, type: String): ConversationMessage? {
        val uuid = json.get("uuid")?.asString ?: return null
        val timestamp = json.get("timestamp")?.asString?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        } ?: return null

        val role = if (type == "user") "user" else "assistant"
        val content: String
        val model: String?
        val toolUses = mutableListOf<ToolUseEntry>()

        if (type == "user") {
            content = extractTextContent(json)
            model = null
        } else {
            val msg = json.getAsJsonObject("message")
            model = msg?.get("model")?.asString
            val contentArray = msg?.get("content")

            if (contentArray != null && contentArray.isJsonArray) {
                val sb = StringBuilder()
                for (element in contentArray.asJsonArray) {
                    if (!element.isJsonObject) continue
                    val obj = element.asJsonObject
                    val contentType = obj.get("type")?.asString
                    when (contentType) {
                        "text" -> sb.appendLine(obj.get("text")?.asString ?: "")
                        "tool_use" -> {
                            val toolName = obj.get("name")?.asString ?: "unknown"
                            val toolId = obj.get("id")?.asString ?: ""
                            toolUses.add(ToolUseEntry(toolName, toolId))
                        }
                    }
                }
                content = sb.toString().trim()
            } else {
                content = contentArray?.asString ?: ""
            }
        }

        return ConversationMessage(
            uuid = uuid,
            timestamp = timestamp,
            type = type,
            role = role,
            content = content,
            model = model,
            toolUses = toolUses,
        )
    }

    private fun extractTextContent(json: JsonObject): String {
        val message = json.getAsJsonObject("message") ?: return ""
        val content = message.get("content") ?: return ""

        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> {
                content.asJsonArray
                    .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                    .joinToString("\n") { it.asJsonObject.get("text")?.asString ?: "" }
            }
            else -> ""
        }
    }

    private fun fileContainsQuery(file: File, lowerQuery: String): Boolean {
        return file.useLines { lines ->
            lines.any { it.lowercase().contains(lowerQuery) }
        }
    }
}
