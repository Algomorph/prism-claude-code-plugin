package com.github.vgirotto.prism.services

/**
 * A chat's display name as the agent CLI itself understands it, replacing Prism's generic
 * `Chat #N` once the CLI has recorded something better.
 *
 * Neither CLI hands a name to its caller, so both are read back out of the session record on
 * disk — the same JSONL files [ClaudeHistoryReader] and [CodexHistoryReader] browse. What is
 * available differs sharply between them:
 *
 *  - **Claude Code** writes real titles into the conversation file: `custom-title` records
 *    when the user names the chat, and `ai-title` records it generates and refines itself.
 *  - **Codex** (verified against 0.146.0) records no title at all. Its own `/resume` picker
 *    labels threads by their first user message, and that is what it stores as a thread
 *    title internally, so Prism derives the same thing from the rollout file.
 *
 * Hence [Origin]: a name carries where it came from, and [ChatNameWatcher] refuses to replace
 * a more authoritative name with a less authoritative one.
 */
data class ChatName(val text: String, val origin: Origin) {

    /**
     * Where a name came from, in ascending order of authority — declaration order *is* the
     * ranking ([Comparable] on the ordinal), so keep the weakest first.
     */
    enum class Origin {
        /** Derived from the first message the user typed. All Codex names are this. */
        FIRST_MESSAGE,

        /** The agent's own generated title (Claude `ai-title`). */
        AGENT_TITLE,

        /** A title the user set explicitly (Claude `custom-title`). Never overridden. */
        USER_TITLE,
    }

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
        private val WHITESPACE_RUN = Regex("\\s+")

        /**
         * Normalize a raw title or message into one line of tab-able text, or null if nothing
         * usable is left.
         *
         * Text starting with `<` is rejected outright: both CLIs log machine-composed
         * envelopes as ordinary messages (`<local-command…>`, `<system-reminder>`,
         * `<user_instructions>`), and naming a chat after one of those is worse than leaving
         * it as `Chat #N`.
         */
        fun clean(raw: String?): String? {
            val flat = raw?.replace(WHITESPACE_RUN, " ")?.trim() ?: return null
            if (flat.isEmpty() || flat.startsWith("<")) return null
            return flat
        }

        /** [clean] the text and pair it with its [origin], or null if nothing usable remains. */
        fun of(raw: String?, origin: Origin): ChatName? =
            clean(raw)?.let { ChatName(it, origin) }
    }
}
