package com.github.vgirotto.prism.services.session

/**
 * Reads Claude Code's terminal title (verified against 2.1.284).
 *
 * Claude always writes `<glyph> <title>`: `✳` when idle, `◐`/`◑` alternating while it works.
 * Before the chat has a title, the text is the literal `Claude Code`. The title is written whole —
 * Claude never cuts it off — and changes at once on `/rename`, on `/resume`, and when Claude
 * generates a title during the first turn.
 *
 * Only that exact shape is accepted. The session runs inside the user's login shell, which sets
 * titles of its own (`user@host: ~/dir`) before and after Claude runs, and those must produce no
 * reading rather than a tab named after a directory.
 */
object ClaudeTitleParser {

    /** The status glyphs Claude prefixes its title with (`PY` and `RY` in the 2.1.284 bundle). */
    val STATUS_GLYPHS: Set<Int> = setOf(0x2733, 0x25D0, 0x25D1)

    /** What Claude shows before the chat has a title. */
    const val UNTITLED = "Claude Code"

    fun parse(title: String): TitleReading? {
        if (title.isEmpty()) return null
        val glyph = title.codePointAt(0)
        if (glyph !in STATUS_GLYPHS) return null
        val glyphEnd = Character.charCount(glyph)
        if (title.length <= glyphEnd + 1 || title[glyphEnd] != ' ') return null
        val rest = title.substring(glyphEnd + 1).trim()
        if (rest.isEmpty()) return null
        if (rest == UNTITLED) return TitleReading.Unnamed(idHint = null)
        return TitleReading.Named(rest, mayBeCutOff = false, idHint = null)
    }
}
