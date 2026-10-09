package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.graphemes

/**
 * Reads the Codex terminal title Prism asks for with
 * `-c tui.terminal_title=["thread-id","thread-name"]` (verified against codex-cli 0.159.0 and its
 * tagged source, `codex-rs/tui/src/chatwidget/status_surfaces.rs`).
 *
 * Codex renders that selection as:
 * ```
 * 01a0edbb-4501-7591-82b7-36c4c...               unnamed (new thread, or after /new)
 * 01a0edbb-4501-7591-82b7-36c4c... ⠋ | ⠋         a name is being generated
 * 01a0edbb-4501-7591-82b7-36c4c... | Reply ok    named (generated, /rename, or /resume)
 * ```
 *  - Each item is cut to a grapheme budget, the last three of which become `...`: the thread id
 *    to [ID_MAX_GRAPHEMES], the name to [NAME_MAX_GRAPHEMES]. Items are joined with ` | `.
 *  - While a name is being generated, a spinner frame is appended to each item after a space
 *    (or stands alone for an item that has no value yet). The thread id never ends in one, so a
 *    frame after the id is what tells the frame after the name apart from a name's own text.
 *  - When Codex waits for approval, it swaps in `[ ! ] Action Required | …`, which is not this
 *    shape and so produces no reading: the tab keeps its name until the normal title returns.
 *
 * Only that shape is accepted: the session runs inside the user's login shell, which sets titles
 * of its own before and after Codex runs.
 */
object CodexTitleParser {

    const val ID_MAX_GRAPHEMES = 32
    const val NAME_MAX_GRAPHEMES = 48
    const val ELLIPSIS = "..."
    private const val SEPARATOR = " | "
    private const val FULL_ID_LENGTH = 36

    /** `TERMINAL_TITLE_SPINNER_FRAMES` in the 0.159 source. */
    val SPINNER_FRAMES: Set<String> = setOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    private val ID_ITEM = Regex("^[0-9a-f]{8}-[0-9a-f-]{1,28}(\\.\\.\\.)?$")

    fun parse(title: String): TitleReading? {
        val separator = title.indexOf(SEPARATOR)
        val decoratedIdItem = (if (separator < 0) title else title.substring(0, separator)).trim()
        val idItem = withoutSpinner(decoratedIdItem)
        val generatingName = idItem != decoratedIdItem
        if (!ID_ITEM.matches(idItem)) return null
        val idValue = idItem.removeSuffix(ELLIPSIS)
        val idHint = IdHint(
            idValue,
            isPrefix = idItem.endsWith(ELLIPSIS) || idValue.length < FULL_ID_LENGTH,
        )
        if (separator < 0) return TitleReading.Unnamed(idHint)

        val decoratedName = title.substring(separator + SEPARATOR.length).trim()
        val name = if (generatingName) withoutSpinner(decoratedName) else decoratedName
        // `<id> |` alone, or `<id> ⠋ | ⠋`: the name is on its way, not absent.
        if (name.isEmpty()) return null
        return TitleReading.Named(name, mayBeCutOff = name.endsWith(ELLIPSIS), idHint = idHint)
    }

    /**
     * Thread name [name] as the title shows it. Codex trims the name, cuts it to
     * [NAME_MAX_GRAPHEMES] grapheme clusters (the last three becoming `...`), and only then
     * sanitizes the whole title ([normalize]). So a cut-off name can show fewer than
     * [NAME_MAX_GRAPHEMES] graphemes, when the sanitizer collapsed whitespace or removed invisible
     * characters from the part that was kept.
     */
    fun titleText(name: String): String {
        val trimmed = name.trim()
        val graphemes = graphemes(trimmed)
        val kept = if (graphemes.size <= NAME_MAX_GRAPHEMES) trimmed
            else graphemes.take(NAME_MAX_GRAPHEMES - ELLIPSIS.length).joinToString("") + ELLIPSIS
        return normalize(kept)
    }

    /**
     * [text] as Codex's title sanitizer would print it: control and invisible formatting
     * characters removed, whitespace runs collapsed to one space, trimmed. Used to compare a full
     * name from Codex's store with the cut-off text the title showed.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (isDisallowed(cp)) continue
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /** [item] without one trailing spinner frame, if it ends in one. */
    private fun withoutSpinner(item: String): String {
        if (item in SPINNER_FRAMES) return ""
        val frame = SPINNER_FRAMES.firstOrNull { item.endsWith(" $it") } ?: return item
        return item.removeSuffix(" $frame").trimEnd()
    }

    /** `is_disallowed_terminal_title_char` in codex-rs/tui/src/terminal_title.rs. */
    private fun isDisallowed(cp: Int): Boolean =
        Character.isISOControl(cp) ||
            cp == 0x00AD || cp == 0x034F || cp == 0x061C || cp == 0x180E ||
            cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x206F ||
            cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp in 0xFFF9..0xFFFB ||
            cp in 0x1BCA0..0x1BCA3 || cp in 0xE0100..0xE01EF
}
