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
class TranscriptParser {

    private val pretty: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compact: Gson = Gson()

    /** Cap on tool-result / tool-input text kept in memory for display (R14, §6.3). */
    private val displayCap = 8000

    fun parseFile(file: File): List<TranscriptMessage> {
        if (!file.isFile) return emptyList()
        // useLines tolerates a partial trailing line by simply yielding it; a non-JSON
        // line is skipped by parseLine. Group 5 adds byte-offset buffering for live tails.
        return file.useLines { seq -> parseLines(seq) }
    }

    fun parseLines(lines: Sequence<String>): List<TranscriptMessage> {
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
            return listOf(TextBlock(content.asString))
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

    /** Turn one content-array element into zero or more blocks. */
    private fun contentBlock(obj: JsonObject): List<Block> {
        return when (obj.get("type")?.asString) {
            "text" -> listOf(TextBlock(obj.get("text")?.asStringSafe() ?: ""))
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
            val full = content.asString
            return listOf(ToolResultBlock(toolUseId, cap(full), isError, truncated = full.length > displayCap))
        }
        if (content.isJsonArray) {
            val sb = StringBuilder()
            val extra = ArrayList<Block>()
            for (el in content.asJsonArray) {
                if (!el.isJsonObject) continue
                val part = el.asJsonObject
                when (part.get("type")?.asString) {
                    "text" -> sb.appendLine(part.get("text")?.asStringSafe() ?: "")
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
}
