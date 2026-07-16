package com.github.vgirotto.prism.chatshell

/**
 * The non-lossy transcript block model (design §6.3, R22). Unlike the legacy
 * `ConversationMessage`, this preserves every block — tool inputs, tool results (incl.
 * errors), thinking, images, ordering, and unknown/future content — and tags each with a
 * display **visibility class**. Parsing preserves everything; rendering shows only the
 * user-visible conversation.
 */

enum class Visibility {
    /** Shown in the transcript (text, tool calls/results, images). */
    VISIBLE,

    /** Shown behind a disclosure (thinking, large output, tool_reference). */
    COLLAPSED,

    /** Retained in the model but never rendered (system/control/internal records). */
    HIDDEN_INTERNAL,
}

sealed interface Block {
    val visibility: Visibility
}

data class TextBlock(
    /** Raw markdown with math delimiters intact — never HTML. */
    val markdown: String,
    override val visibility: Visibility = Visibility.VISIBLE,
) : Block

data class ThinkingBlock(
    val text: String,
    override val visibility: Visibility = Visibility.COLLAPSED,
) : Block

data class ToolUseBlock(
    val id: String,
    val name: String,
    /** Pretty-printed input JSON (rendered as text, never parsed as HTML). */
    val inputJson: String,
    /** Collapsed by default so tool calls render behind a disclosure, not inline (§6.3). */
    override val visibility: Visibility = Visibility.COLLAPSED,
) : Block

data class ToolResultBlock(
    /** Links back to the [ToolUseBlock.id] this result answers. */
    val toolUseId: String,
    val text: String,
    val isError: Boolean,
    /** True if [text] was capped for display; full content loads on demand (§6.3, R14). */
    val truncated: Boolean = false,
    /** File byte offset+length backing an on-demand "show full" — never the payload. */
    val sourceOffset: Long? = null,
    val sourceLength: Int? = null,
    /** Collapsed by default so tool output renders behind a disclosure, not inline (§6.3). */
    override val visibility: Visibility = Visibility.COLLAPSED,
) : Block

data class ImageBlock(
    /** e.g. image/png. */
    val mediaType: String,
    /** Inline base64 payload (the confirmed primary form) — resolved by the MediaResolver. */
    val base64Data: String? = null,
    /** A path/URL reference for markdown `<img>` (secondary form). */
    val sourceRef: String? = null,
    override val visibility: Visibility = Visibility.VISIBLE,
) : Block

data class ToolReferenceBlock(
    val toolName: String,
    override val visibility: Visibility = Visibility.COLLAPSED,
) : Block

/** A compaction divider produced from a `system`/`compact_boundary` record (§8.5). */
data class CompactBoundaryBlock(
    val summary: String,
    override val visibility: Visibility = Visibility.VISIBLE,
) : Block

/** Unknown/future content — preserved verbatim, never dropped (R7). */
data class UnknownBlock(
    val rawType: String,
    val rawJson: String,
    override val visibility: Visibility = Visibility.HIDDEN_INTERNAL,
) : Block

data class TranscriptMessage(
    /** Stable across re-parses — the record `uuid` (fallback: synthesized). */
    val id: String,
    /** user | assistant | system | <internal record type>. */
    val role: String,
    val timestamp: String? = null,
    val model: String? = null,
    val blocks: List<Block> = emptyList(),
) {
    /** True if any block is user-visible (VISIBLE/COLLAPSED) — i.e. it renders at all. */
    val isRenderable: Boolean get() = blocks.any { it.visibility != Visibility.HIDDEN_INTERNAL }
}
