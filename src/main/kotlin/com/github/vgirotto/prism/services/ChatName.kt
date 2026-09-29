package com.github.vgirotto.prism.services

/**
 * A chat's name as its agent CLI shows it, which replaces Prism's generic `Chat #N` on the tab
 * (see [com.github.vgirotto.prism.services.session.ChatSessionTracker] for where it comes from).
 */
data class ChatName(val text: String) {

    /**
     * A tab-sized rendering. [text] is kept whole so callers can put the full name in a
     * tooltip; only the tab label is clipped, on a word boundary where one is close enough
     * to the limit to be worth it.
     */
    fun display(maxChars: Int = TAB_MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val head = if (lastSpace >= maxChars / 2) cut.take(lastSpace) else cut
        return head.trimEnd().trimEnd(*TRAILING_PUNCTUATION) + "…"
    }

    companion object {
        /** Roughly what fits in a tool-window tab without crowding out its neighbours. */
        const val TAB_MAX_CHARS = 28

        private val TRAILING_PUNCTUATION = charArrayOf(',', ';', ':', '.', '-', '—')
    }
}
