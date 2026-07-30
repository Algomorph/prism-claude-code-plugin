package com.github.vgirotto.prism.services

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Pure JSONL readers for [ChatName], one per CLI schema. Kept free of platform and file-system
// types so the schemas can be unit-tested against literal records.
//
// Both parsers are lenient by construction: they are handed *fragments* of session files (a
// byte-bounded tail, or a bounded number of head lines), so a truncated or malformed line is an
// expected input, not an error.

/**
 * Claude Code conversation files (`~/.claude/projects/<escaped>/<conversationId>.jsonl`).
 *
 * Titles arrive as their own single-purpose records, rewritten on most turns rather than
 * patched, so the *last* one in the file is the current one:
 *
 * ```
 * {"type":"ai-title","aiTitle":"Add quick action buttons","sessionId":"<conv>"}
 * {"type":"custom-title","customTitle":"font-settings-menu","sessionId":"<conv>"}
 * ```
 *
 * Because they are rewritten so often, a bounded tail read is enough to find them — no need to
 * scan conversation files that routinely reach tens of megabytes.
 */
object ClaudeChatNameParser {

    /**
     * The chat's current title from a tail fragment, or null if the tail carries none.
     *
     * A user-set title wins over a generated one outright rather than by recency: Claude keeps
     * emitting `ai-title` records after a rename, so "newest record wins" would flip the tab
     * back to the generated title on the next turn.
     */
    fun titleIn(tail: String): ChatName? {
        var custom: String? = null
        var generated: String? = null
        for (line in tail.lineSequence()) {
            val json = parseLine(line) ?: continue
            when (json.get("type")?.asStringOrNull()) {
                "custom-title" -> json.get("customTitle")?.asStringOrNull()?.let { custom = it }
                "ai-title" -> json.get("aiTitle")?.asStringOrNull()?.let { generated = it }
            }
        }
        return ChatName.of(custom, ChatName.Origin.USER_TITLE)
            ?: ChatName.of(generated, ChatName.Origin.AGENT_TITLE)
    }

    /**
     * The snake-case `session_id` of the most recent line that carries one — the marker that
     * says which conversation file a chat is *currently* writing to, including after a
     * `/resume` moved it to a different file. Mirrors
     * [com.github.vgirotto.prism.chatshell.SiblingTranscriptWatcher], which follows the same
     * signal for the transcript pane.
     */
    fun latestSessionId(tail: String): String? =
        SESSION_ID_RE.findAll(tail).lastOrNull()?.groupValues?.get(1)

    /**
     * The first message the user actually typed, from the head of a conversation file — the
     * stand-in title for the one turn before Claude generates its own.
     *
     * Skips Claude's bookkeeping `user` records: `isMeta` entries and tool results, which carry
     * the user role but were never typed by anyone.
     */
    fun firstUserMessage(lines: Sequence<String>): String? {
        for (line in lines) {
            val json = parseLine(line) ?: continue
            if (json.get("type")?.asStringOrNull() != "user") continue
            if (json.get("isMeta")?.asBooleanOrNull() == true) continue
            val content = json.objectOrNull("message")?.get("content") ?: continue
            val text = when {
                content.isJsonPrimitive -> content.asStringOrNull()
                content.isJsonArray -> {
                    val parts = content.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }
                    // A tool result wears the user role; it is not a message the user wrote.
                    if (parts.any { it.get("type")?.asStringOrNull() == "tool_result" }) continue
                    parts.filter { it.get("type")?.asStringOrNull() == "text" }
                        .joinToString(" ") { it.get("text")?.asStringOrNull().orEmpty() }
                }
                else -> continue
            }
            ChatName.clean(text)?.let { return it }
        }
        return null
    }

    /** Matches `"session_id":"<value>"`; a `null` value simply doesn't match. */
    private val SESSION_ID_RE = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
}

/**
 * Codex rollout files (`~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`).
 *
 * Codex records no title, so the name comes from the first message the user typed:
 *
 * ```
 * {"type":"event_msg","payload":{"type":"user_message","message":"Review this branch"}}
 * ```
 *
 * This is the same thing Codex shows in its own `/resume` picker, so a Prism tab and the CLI
 * agree on what a thread is called.
 */
object CodexChatNameParser {

    /**
     * True when this rollout belongs to a sub-agent Codex spawned rather than to a thread the
     * user opened — `session_meta.thread_source` is `"user"` for the latter and `"subagent"` for
     * spawned workers, reviewers and guardians.
     *
     * Worth checking because a sub-agent's "first user message" is a system-composed prompt
     * ("The following is the Codex agent history whose request action you are assessing…"), which
     * makes a nonsensical tab label. Absence is treated as a user thread, so a Codex build that
     * predates the field is not penalised.
     */
    fun isSubagentThread(lines: Sequence<String>): Boolean {
        for (line in lines) {
            val json = parseLine(line) ?: continue
            if (json.get("type")?.asStringOrNull() != "session_meta") continue
            val payload = json.objectOrNull("payload") ?: return false
            return payload.get("thread_source")?.asStringOrNull() == "subagent"
        }
        return false
    }

    /** The first `user_message` in the given head lines, or null if none is present yet. */
    fun firstUserMessage(lines: Sequence<String>): String? {
        for (line in lines) {
            val json = parseLine(line) ?: continue
            if (json.get("type")?.asStringOrNull() != "event_msg") continue
            val payload = json.objectOrNull("payload") ?: continue
            if (payload.get("type")?.asStringOrNull() != "user_message") continue
            val text = (payload.get("message") ?: payload.get("text"))?.asStringOrNull()
            ChatName.clean(text)?.let { return it }
        }
        return null
    }
}

private fun parseLine(line: String): JsonObject? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        JsonParser.parseString(trimmed).asJsonObject
    } catch (_: Exception) {
        null
    }
}

/** The named member as an object, or null if absent or another JSON type. */
private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

/** Null instead of an exception for a JSON null or a non-string. */
private fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

private fun JsonElement.asBooleanOrNull(): Boolean? =
    if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null
