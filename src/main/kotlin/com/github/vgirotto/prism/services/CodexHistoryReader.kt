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
import java.util.concurrent.ConcurrentHashMap

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
 * Unlike Claude, which gets a directory per project, Codex files it by date and keeps the
 * `cwd` inside the file — so there is no directory to filter on. Reading every file in
 * full just to discard the ones belonging to other projects makes the cost of opening the
 * History panel scale with the whole disk, so two things keep it proportional instead:
 * `session_meta` is the first record, so [peekMeta] settles the `cwd` from the head of the
 * file, and [metaCache] remembers that answer until the file's size or mtime changes.
 */
class CodexHistoryReader(
    private val projectBasePath: String?,
    sessionsRoot: File? = null,
) : HistoryReader {

    private val log = Logger.getInstance(CodexHistoryReader::class.java)

    private val sessionsRoot: File =
        sessionsRoot ?: File(System.getProperty("user.home"), ".codex/sessions")

    /** Path → header, valid while the file's mtime and size are unchanged. */
    private val metaCache = ConcurrentHashMap<String, SessionMeta>()

    private data class SessionMeta(
        val mtime: Long,
        val size: Long,
        val cwd: String,
        val sessionId: String,
    )

    override fun listConversations(limit: Int): List<ConversationSummary> {
        val base = projectBasePath ?: return emptyList()
        if (!sessionsRoot.isDirectory) return emptyList()

        return projectFiles(base)
            .mapNotNull { file ->
                try {
                    parseSummary(file)
                } catch (e: Exception) {
                    log.debug("Failed to parse Codex session file: ${file.name}", e)
                    null
                }
            }
            .take(limit)
            .toList()
            .sortedByDescending { it.lastTime }
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

    override fun searchConversations(query: String, limit: Int): List<ConversationSummary> {
        if (query.isBlank()) return listConversations(limit)
        val base = projectBasePath ?: return emptyList()
        if (!sessionsRoot.isDirectory) return emptyList()
        val lowerQuery = query.lowercase()

        // Only this project's files are opened — the header filter already excluded the
        // rest, so a search no longer grep's the entire Codex history.
        return projectFiles(base)
            .filter { fileContainsQuery(it, lowerQuery) }
            .mapNotNull {
                try {
                    parseSummary(it)
                } catch (_: Exception) {
                    null
                }
            }
            .take(limit)
            .toList()
            .sortedByDescending { it.lastTime }
    }

    /**
     * Returns the rollout file for a session id, searching recursively
     * under [sessionsRoot]. Codex filenames embed the session UUID.
     */
    fun findSessionFile(sessionId: String): File? {
        if (!sessionsRoot.isDirectory) return null
        return collectJsonlFiles().firstOrNull { it.name.contains(sessionId) }
    }

    /**
     * This project's session files, newest first, without opening any of them beyond the
     * header. Codex filenames lead with the session timestamp, so sorting by name orders
     * them by recency for free — which is what makes a paged [limit] meaningful.
     */
    private fun projectFiles(base: String): Sequence<File> =
        collectJsonlFiles()
            .sortedByDescending { it.name }
            .asSequence()
            .filter { metaOf(it)?.cwd == base }

    private fun collectJsonlFiles(): List<File> =
        sessionsRoot.walkTopDown()
            // YYYY/MM/DD/file. Also caps the walk, which otherwise follows directory
            // symlinks with no cycle detection.
            .maxDepth(SESSION_TREE_DEPTH)
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .toList()

    private fun metaOf(file: File): SessionMeta? {
        val mtime = file.lastModified()
        val size = file.length()
        metaCache[file.path]?.let { if (it.mtime == mtime && it.size == size) return it }

        val meta = peekMeta(file, mtime, size) ?: return null
        metaCache[file.path] = meta
        return meta
    }

    /** Reads only the head of [file], where `session_meta` lives, to settle id and cwd. */
    private fun peekMeta(file: File, mtime: Long, size: Long): SessionMeta? {
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var scanned = 0
                while (scanned < META_SCAN_LINES) {
                    val line = reader.readLine() ?: break
                    scanned++
                    if (line.isBlank()) continue
                    val json = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (_: Exception) {
                        continue
                    }
                    if (json.get("type")?.asString != "session_meta") continue
                    val payload = json.getAsJsonObject("payload") ?: continue
                    val id = payload.get("id")?.asString ?: continue
                    return SessionMeta(mtime, size, payload.get("cwd")?.asString.orEmpty(), id)
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to read Codex session header: ${file.name}", e)
        }
        return null
    }

    private fun parseSummary(file: File): ConversationSummary? {
        val meta = metaOf(file) ?: return null

        var startTime: Instant? = null
        var lastTime: Instant? = null
        var messageCount = 0
        var firstUserMessage = ""
        var model = ""

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

        if (startTime == null || messageCount == 0) return null

        return ConversationSummary(
            sessionId = meta.sessionId,
            filePath = file.absolutePath,
            startTime = startTime!!,
            lastTime = lastTime ?: startTime!!,
            messageCount = messageCount,
            firstUserMessage = firstUserMessage.take(200),
            model = model,
            cwd = meta.cwd,
        )
    }

    private fun fileContainsQuery(file: File, lowerQuery: String): Boolean = try {
        file.useLines { lines -> lines.any { it.lowercase().contains(lowerQuery) } }
    } catch (_: Exception) {
        false
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

    private companion object {
        /** `sessions/YYYY/MM/DD/rollout-*.jsonl` */
        const val SESSION_TREE_DEPTH = 4

        /** `session_meta` is the first record; the slack absorbs a stray leading line. */
        const val META_SCAN_LINES = 5
    }
}
