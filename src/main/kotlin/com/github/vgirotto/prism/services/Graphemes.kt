package com.github.vgirotto.prism.services

import java.text.BreakIterator

/**
 * [text] split into its grapheme clusters: what a reader takes for one character, such as an
 * emoji with its modifiers and joiners, a flag, or a letter with its combining marks. Text cut
 * between two of them never splits a character.
 */
internal fun graphemes(text: String): List<String> {
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(text)
    val out = ArrayList<String>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = boundaries.next()
    }
    return out
}
