package com.github.vgirotto.prism.chatshell

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Parses OpenAI Codex CLI rollout transcripts into the shared, non-lossy [TranscriptMessage]
 * model — the Codex implementation behind the same parser seam as the Claude [TranscriptParser]
 * (design §11). The render/tail/controller stack stays agent-agnostic; only this class knows
 * Codex's on-disk schema.
 *
 * Codex writes one JSON record per line to
 * `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`. Each record has a top-level `type`
 * and a `payload`. In file order the transcript renders:
 *   - `event_msg`/`user_message`                 → a user turn (the clean typed text; the
 *                                                   `response_item` copy is skipped because it
 *                                                   also carries injected AGENTS.md context)
 *   - `response_item`/`message` (role=assistant) → an assistant text turn (`output_text`)
 *   - `response_item`/`custom_tool_call`         → a tool call (name + input); also `function_call`
 *   - `response_item`/`custom_tool_call_output`  → a tool result; also `function_call_output`
 *   - `response_item`/`reasoning`                → a thinking block iff a plaintext `summary`
 *                                                   exists; the `encrypted_content` form is opaque
 *                                                   and yields no block
 *   - `compacted`                                → a compaction divider
 * Everything else (`turn_context`, `token_count`, `task_*`, `world_state`, `session_meta`, and
 * developer/user `response_item` messages) is retained-but-hidden (R7), never dropped.
 *
 * Like the Claude parser, both entry points tolerate a partial trailing line (live tailing) by
 * skipping it, and a single malformed record never aborts the parse.
 */
class CodexTranscriptParser : AgentTranscriptParser {

    private val pretty: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compact: Gson = Gson()

    /** Cap on tool-result / tool-input text kept in memory for display (R14, §6.3). */
    private val displayCap = 8000

    override fun parseFile(file: File): List<TranscriptMessage> {
        if (!file.isFile) return emptyList()
        return file.useLines { seq -> parseLines(seq) }
    }

    override fun parseLines(lines: Sequence<String>): List<TranscriptMessage> {
        val out = ArrayList<TranscriptMessage>()
        var index = 0
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val msg = try {
                parseLine(line, index)
            } catch (_: Exception) {
                null // malformed / partial trailing line
            }
            if (msg != null) out.add(msg)
            index++
        }
        return out
    }

    fun parseLine(line: String, index: Int = 0): TranscriptMessage? {
        val json = JsonParser.parseString(line).asJsonObject
        val type = json.get("type")?.asString ?: return null
        val ts = json.get("timestamp")?.asStringSafe()
        val payload = json.getAsJsonObject("payload")
        val id = idFor(payload, type, ts, index)

        return when (type) {
            "event_msg" -> when (payload?.get("type")?.asStringSafe()) {
                "user_message" -> {
                    val text = stripAnsi((payload.get("message") ?: payload.get("text"))?.asStringSafe() ?: "")
                    if (text.isBlank()) hidden(id, type, line)
                    else TranscriptMessage(id, "user", ts, null, listOf(TextBlock(text)))
                }
                // agent_message duplicates response_item/message[assistant]; all other events
                // (token_count, task_*, patch_apply_*, web_search_*, …) are non-conversational.
                else -> hidden(id, type, line)
            }
            "response_item" -> responseItem(id, ts, payload, type, line)
            "compacted" -> TranscriptMessage(
                id, "system", ts, null, listOf(CompactBoundaryBlock(summary = "Conversation compacted"))
            )
            else -> hidden(id, type, line)
        }
    }

    private fun responseItem(id: String, ts: String?, payload: JsonObject?, type: String, line: String): TranscriptMessage {
        if (payload == null) return hidden(id, type, line)
        return when (payload.get("type")?.asStringSafe()) {
            "message" -> {
                // Only the assistant's own turns render here; role=developer is the system
                // preamble and role=user duplicates event_msg/user_message (with AGENTS.md noise).
                if (payload.get("role")?.asStringSafe() != "assistant") return hidden(id, type, line)
                val text = extractContentText(payload.get("content"))
                if (text.isBlank()) hidden(id, type, line)
                else TranscriptMessage(id, "assistant", ts, null, listOf(TextBlock(text)))
            }
            "custom_tool_call", "function_call", "local_shell_call" -> {
                val name = payload.get("name")?.asStringSafe() ?: "tool"
                val callId = payload.get("call_id")?.asStringSafe() ?: id
                val inputEl = payload.get("input") ?: payload.get("arguments")
                val input = when {
                    inputEl == null || inputEl.isJsonNull -> ""
                    inputEl.isJsonPrimitive -> inputEl.asString
                    else -> pretty.toJson(inputEl)
                }
                TranscriptMessage(id, "assistant", ts, null, listOf(ToolUseBlock(callId, name, cap(stripAnsi(input)))))
            }
            "custom_tool_call_output", "function_call_output", "local_shell_call_output" -> {
                val callId = payload.get("call_id")?.asStringSafe() ?: id
                val text = extractContentText(payload.get("output"))
                TranscriptMessage(
                    id, "user", ts, null,
                    listOf(ToolResultBlock(callId, cap(text), isError = false, truncated = text.length > displayCap))
                )
            }
            "reasoning" -> {
                // encrypted_content is opaque; only a plaintext summary (rare) is renderable.
                val summary = extractContentText(payload.get("summary"))
                if (summary.isBlank()) hidden(id, type, line)
                else TranscriptMessage(id, "assistant", ts, null, listOf(ThinkingBlock(summary)))
            }
            else -> hidden(id, type, line)
        }
    }

    /**
     * Codex text lives in a content array of `{type: input_text|output_text|…, text}` objects
     * (also used for tool `output`). Concatenate the `text` fields (chunks already carry their
     * own newlines), strip ANSI, and trim. Tolerates a bare string too.
     */
    private fun extractContentText(el: JsonElement?): String {
        if (el == null || el.isJsonNull) return ""
        if (el.isJsonPrimitive) return stripAnsi(el.asString).trim()
        if (el.isJsonArray) {
            val sb = StringBuilder()
            for (e in el.asJsonArray) {
                when {
                    e.isJsonObject -> e.asJsonObject.get("text")?.asStringSafe()?.let { sb.append(it) }
                    e.isJsonPrimitive -> sb.append(e.asString)
                }
            }
            return stripAnsi(sb.toString()).trim()
        }
        return ""
    }

    /**
     * A stable per-record id so live-tail upserts merge correctly. Codex response items carry
     * an `id` (`rs_…`, `ctc_…`); tool outputs carry `call_id`; events fall back to the record
     * timestamp (unique per record). The batch-local [index] is the last resort.
     */
    private fun idFor(payload: JsonObject?, type: String, ts: String?, index: Int): String =
        payload?.get("id")?.asStringSafe()
            ?: payload?.get("call_id")?.asStringSafe()
            ?: ts?.let { "codex-$it" }
            ?: "codex-$type-$index"

    private fun hidden(id: String, type: String, line: String) =
        TranscriptMessage(id, type, null, null, listOf(UnknownBlock(type, line, Visibility.HIDDEN_INTERNAL)))

    private fun cap(s: String): String =
        if (s.length <= displayCap) s else s.substring(0, displayCap) + "\n… (truncated)"

    private fun stripAnsi(s: String): String = TranscriptParser.stripAnsi(s)

    private fun JsonElement.asStringSafe(): String? = try { asString } catch (_: Exception) { null }
}
