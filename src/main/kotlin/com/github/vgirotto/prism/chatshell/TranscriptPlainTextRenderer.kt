package com.github.vgirotto.prism.chatshell

/**
 * Renders [TranscriptMessage]s as plain text for the no-JCEF fallback surface (R3).
 *
 * Some IDEs ship no embedded browser — Android Studio bundles the JCEF *bindings* but not the
 * native library, so `JBCefApp.isSupported()` is false and [TranscriptView] falls back to a
 * read-only text area. Deltas are meaningless there (they patch a DOM), so the controller
 * re-renders the whole visible transcript through this renderer instead.
 *
 * Non-lossy in the same sense as [TranscriptPayloadBuilder]: every block that is not
 * [Visibility.HIDDEN_INTERNAL] appears. Collapsed blocks can't collapse in a text area, so they
 * are rendered inline behind a labelled, indented section header.
 *
 * Labels are injected (rather than read from `PrismBundle`) so this stays a pure function unit
 * tests can exercise without an IDE Application.
 */
object TranscriptPlainTextRenderer {

    /** Section labels the caller resolves through the i18n bundle. Defaults are English. */
    data class Labels(
        val thinking: String = "Thinking",
        val output: String = "Output",
        val image: String = "image",
        val blockedImage: String = "[blocked image]",
        val unsupported: String = "[unsupported content]",
    )

    fun render(
        messages: List<TranscriptMessage>,
        labels: Labels = Labels(),
        roleLabel: (String) -> String = { TranscriptPayloadBuilder.defaultRoleLabel(it) },
    ): String {
        val out = StringBuilder()
        for (message in messages) {
            if (!message.isRenderable) continue
            val blocks = message.blocks.filter { it.visibility != Visibility.HIDDEN_INTERNAL }
            if (blocks.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(HEADER_MARK).append(' ').append(roleLabel(message.role))
            message.model?.let { out.append("  (").append(it).append(')') }
            out.append('\n')
            for (block in blocks) out.append(renderBlock(block, labels))
        }
        return out.toString()
    }

    private fun renderBlock(block: Block, labels: Labels): String = when (block) {
        // Markdown is left as-is: it is the author's own text, and a text area has no better
        // rendering for it than the source.
        is TextBlock -> body(block.markdown)

        is ThinkingBlock -> section(labels.thinking, block.text)

        is ToolUseBlock -> section(block.name, block.inputJson)

        is ToolResultBlock -> section(
            if (block.isError) "${labels.output} — error" else labels.output,
            if (block.truncated) block.text + "\n…" else block.text,
        )

        is ImageBlock -> {
            val hasSource = block.base64Data != null || block.sourceRef != null
            body(if (hasSource) "[${labels.image}: ${block.mediaType}]" else labels.blockedImage)
        }

        is ToolReferenceBlock -> body("↳ ${block.toolName}")

        is CompactBoundaryBlock -> body(DIVIDER + "\n" + block.summary)

        // Only reachable if a parser marks unknown content visible; never dropped silently.
        is UnknownBlock -> body(labels.unsupported)
    }

    private fun body(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.isEmpty()) "" else "$trimmed\n"
    }

    /** A labelled, indented stand-in for a disclosure the text area cannot collapse. */
    private fun section(label: String, text: String): String {
        val trimmed = text.trimEnd()
        val head = "$SECTION_MARK $label\n"
        if (trimmed.isEmpty()) return head
        return head + trimmed.lineSequence().joinToString("\n") { INDENT + it } + "\n"
    }

    private const val HEADER_MARK = "▌"
    private const val SECTION_MARK = "  ▸"
    private const val INDENT = "    "
    private const val DIVIDER = "────────────────────────────"
}
