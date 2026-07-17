package com.github.vgirotto.prism.chatshell

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Parses Claude Code JSONL transcripts into the non-lossy [TranscriptMessage] model
 * (design §6.3). Unlike `ConversationHistoryService`, it does **not** drop `tool_result`
 * records, does **not** flatten assistant blocks, and preserves tool inputs, results,
 * errors, thinking, images, `tool_reference`, ordering, and unknown/future content
 * (UnknownBlock — never silently dropped, R7). Schema confirmed in the Group 0 spike.
 *
 * Agent-specific: this is the Claude implementation. Codex gets its own parser behind the
 * same seam (§11).
 */
class TranscriptParser : AgentTranscriptParser {

    private val pretty: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compact: Gson = Gson()

    /** Cap on tool-result / tool-input text kept in memory for display (R14, §6.3). */
    private val displayCap = 8000

    override fun parseFile(file: File): List<TranscriptMessage> {
        if (!file.isFile) return emptyList()
        // useLines tolerates a partial trailing line by simply yielding it; a non-JSON
        // line is skipped by parseLine. Group 5 adds byte-offset buffering for live tails.
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
                null // malformed / partial trailing line — Group 5 buffers these
            }
            if (msg != null) out.add(msg)
            index++
        }
        return out
    }

    fun parseLine(line: String, index: Int = 0): TranscriptMessage? {
        val json = JsonParser.parseString(line).asJsonObject
        val type = json.get("type")?.asString ?: return null
        val id = json.get("uuid")?.asString ?: "synthetic-$index"
        val ts = json.get("timestamp")?.asString

        // Meta records (caveats, image-paste notes) and the post-compaction continuation
        // summary ("This session is being continued…") are plumbing, not conversation — Claude
        // always writes them as `type:"user"`, so left alone they render under "You". A
        // `compact_boundary` system record already precedes every summary and renders the
        // "Conversation compacted" divider, so both are retained-but-hidden here rather than
        // shown. (Retained, not dropped, per R7.)
        val isMeta = json.get("isMeta")?.asBooleanSafe() == true
        val isCompactSummary = json.get("isCompactSummary")?.asBooleanSafe() == true
        if (type == "user" && (isMeta || isCompactSummary)) return internalMessage(id, type, ts, line)

        return when (type) {
            "user" -> TranscriptMessage(id, "user", ts, null, userBlocks(json))
            "assistant" -> {
                val message = json.getAsJsonObject("message")
                val model = message?.get("model")?.asString
                TranscriptMessage(id, "assistant", ts, model, assistantBlocks(message))
            }
            "system" -> {
                val subtype = json.get("subtype")?.asString
                if (subtype == "compact_boundary") {
                    TranscriptMessage(id, "system", ts, null,
                        listOf(CompactBoundaryBlock(summary = json.get("content")?.asStringSafe() ?: "Conversation compacted")))
                } else {
                    internalMessage(id, type, ts, line)
                }
            }
            // Enumerated internal record types (Group 0) + any future type: preserved,
            // not rendered.
            else -> internalMessage(id, type, ts, line)
        }
    }

    private fun internalMessage(id: String, type: String, ts: String?, line: String) =
        TranscriptMessage(id, type, ts, null, listOf(UnknownBlock(type, line, Visibility.HIDDEN_INTERNAL)))

    private fun userBlocks(record: JsonObject): List<Block> {
        val content = record.getAsJsonObject("message")?.get("content") ?: return emptyList()
        if (content.isJsonPrimitive) {
            return cleanUserStringContent(content.asString)
        }
        if (!content.isJsonArray) return emptyList()
        val blocks = ArrayList<Block>()
        for (el in content.asJsonArray) {
            if (!el.isJsonObject) continue
            blocks.addAll(contentBlock(el.asJsonObject))
        }
        return blocks
    }

    private fun assistantBlocks(message: JsonObject?): List<Block> {
        val content = message?.get("content") ?: return emptyList()
        if (content.isJsonPrimitive) return listOf(TextBlock(content.asString))
        if (!content.isJsonArray) return emptyList()
        val blocks = ArrayList<Block>()
        for (el in content.asJsonArray) {
            if (!el.isJsonObject) continue
            blocks.addAll(contentBlock(el.asJsonObject))
        }
        return blocks
    }

    /**
     * Cleans a plain-string user message. A slash-command invocation renders as a compact
     * command chip (`/compact`); CLI plumbing wrappers (local-command output/caveats, system
     * reminders, IDE file notices) are dropped so they never appear under "You"; anything else
     * is kept with ANSI escape sequences stripped (terminal echo like `[2m…` otherwise
     * surfaces as literal `¤[2m` garbage).
     */
    private fun cleanUserStringContent(raw: String): List<Block> {
        val text = raw.trim()
        extractTag(text, "command-name")?.let { name ->
            val args = extractTag(text, "command-args")?.trim().orEmpty()
            val label = if (args.isEmpty()) name.trim() else "${name.trim()} $args"
            return if (label.isBlank()) emptyList() else listOf(TextBlock(label))
        }
        if (PLUMBING_TAGS.any { text.startsWith("<$it") }) return emptyList()
        val cleaned = stripAnsi(raw).trim()
        return if (cleaned.isEmpty()) emptyList() else listOf(TextBlock(cleaned))
    }

    /** Turn one content-array element into zero or more blocks. */
    private fun contentBlock(obj: JsonObject): List<Block> {
        return when (obj.get("type")?.asString) {
            "text" -> listOf(TextBlock(stripAnsi(obj.get("text")?.asStringSafe() ?: "")))
            // Redacted thinking arrives as `{"thinking":"","signature":"…"}` — the reasoning
            // text is encrypted server-side and never persisted, so there is nothing to show.
            // Emit no block rather than an empty "Thinking" disclosure that expands to nothing.
            "thinking" -> (obj.get("thinking")?.asStringSafe() ?: "").let { t ->
                if (t.isBlank()) emptyList() else listOf(ThinkingBlock(t))
            }
            "tool_use" -> {
                val name = obj.get("name")?.asString ?: "tool"
                val tid = obj.get("id")?.asString ?: ""
                val input = obj.get("input")?.let { prettyJson(it) } ?: ""
                listOf(ToolUseBlock(tid, name, cap(input)))
            }
            "tool_result" -> toolResultBlocks(obj)
            "image" -> listOf(imageBlock(obj))
            "tool_reference" -> listOf(ToolReferenceBlock(obj.get("tool_name")?.asString ?: "tool"))
            else -> listOf(UnknownBlock(obj.get("type")?.asString ?: "unknown", compact.toJson(obj)))
        }
    }

    /** A tool_result whose `content` is a string, or an array of text/image/tool_reference. */
    private fun toolResultBlocks(obj: JsonObject): List<Block> {
        val toolUseId = obj.get("tool_use_id")?.asString ?: ""
        val isError = obj.get("is_error")?.asBooleanSafe() ?: false
        val content = obj.get("content")
        if (content == null || content.isJsonNull) {
            return listOf(ToolResultBlock(toolUseId, "", isError))
        }
        if (content.isJsonPrimitive) {
            val full = stripAnsi(content.asString)
            return listOf(ToolResultBlock(toolUseId, cap(full), isError, truncated = full.length > displayCap))
        }
        if (content.isJsonArray) {
            val sb = StringBuilder()
            val extra = ArrayList<Block>()
            for (el in content.asJsonArray) {
                if (!el.isJsonObject) continue
                val part = el.asJsonObject
                when (part.get("type")?.asString) {
                    "text" -> sb.appendLine(stripAnsi(part.get("text")?.asStringSafe() ?: ""))
                    "image" -> extra.add(imageBlock(part))
                    "tool_reference" -> extra.add(ToolReferenceBlock(part.get("tool_name")?.asString ?: "tool"))
                    else -> extra.add(UnknownBlock(part.get("type")?.asString ?: "unknown", compact.toJson(part)))
                }
            }
            val text = sb.toString().trim()
            val result = ToolResultBlock(toolUseId, cap(text), isError, truncated = text.length > displayCap)
            return listOf(result) + extra
        }
        return listOf(ToolResultBlock(toolUseId, cap(compact.toJson(content)), isError))
    }

    private fun imageBlock(obj: JsonObject): ImageBlock {
        val source = obj.getAsJsonObject("source")
        val mediaType = source?.get("media_type")?.asString ?: "application/octet-stream"
        val sourceType = source?.get("type")?.asString
        return if (sourceType == "base64") {
            ImageBlock(mediaType = mediaType, base64Data = source.get("data")?.asString)
        } else {
            ImageBlock(mediaType = mediaType, sourceRef = source?.get("url")?.asString ?: source?.get("path")?.asString)
        }
    }

    private fun prettyJson(el: JsonElement): String = pretty.toJson(el)

    private fun cap(s: String): String =
        if (s.length <= displayCap) s else s.substring(0, displayCap) + "\n… (truncated)"

    private fun JsonElement.asStringSafe(): String? = try { asString } catch (_: Exception) { null }
    private fun JsonElement.asBooleanSafe(): Boolean? = try { asBoolean } catch (_: Exception) { null }

    private fun extractTag(text: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)

    companion object {
        /**
         * CLI plumbing wrappers Claude persists as `type:"user"` records. They are terminal
         * echo / injected notices, not conversation, so they never render in the transcript.
         * (`command-name`/`command-args` are handled separately as a command chip.)
         */
        private val PLUMBING_TAGS = listOf(
            "local-command-stdout", "local-command-caveat", "command-message",
            "system-reminder", "ide_opened_file",
        )

        /**
         * Matches an ANSI CSI escape sequence (ECMA-48): ESC `[`, parameter bytes, intermediate
         * bytes, final byte — covers the SGR color/style codes (`[2m`, `[22m`, …)
         * that leak into `local-command-stdout` and would otherwise show as literal `¤[2m`.
         */
        private val ANSI_CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

        fun stripAnsi(s: String): String = ANSI_CSI.replace(s, "")
    }
}
