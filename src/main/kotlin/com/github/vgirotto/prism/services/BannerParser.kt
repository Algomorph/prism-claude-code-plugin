package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli

/**
 * Parses an agent CLI's startup banner to extract metadata like model
 * and effort/reasoning level. One implementation per supported CLI.
 */
interface BannerParser {
    /**
     * Returns the parsed (model, effort) if the banner contains enough
     * recognizable text, or null if the buffer hasn't received enough yet.
     */
    fun parse(banner: String): Pair<String, String>?

    companion object {
        fun forCli(cli: AgentCli): BannerParser = when (cli) {
            AgentCli.CLAUDE -> ClaudeBannerParser
            AgentCli.CODEX -> CodexBannerParser
        }

        // Escape sequences are written as textual \u escapes rather than literal
        // control bytes so the source stays valid UTF-8 text (literal NUL/ESC bytes
        // make Git treat the file as binary and hide it from diffs).
        private val CSI = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
        private val OSC = Regex("\u001B\\][^\u0007\u001B]*[\u0007]")
        private val OTHER_ESC = Regex("\u001B[^\\[\\]].")
        private val CTRL = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]")

        /** Strip ANSI escapes and control characters so regexes see plain text. */
        fun stripAnsi(raw: String): String =
            raw.replace(CSI, "").replace(OSC, "").replace(OTHER_ESC, "").replace(CTRL, "")
    }
}

/**
 * Banner format example:
 *   "Welcome to Claude Code · Sonnet 4.6 with medium effort"
 */
object ClaudeBannerParser : BannerParser {
    private val modelRegex = Regex("(Opus|Sonnet|Haiku)\\s+[\\d.]+", RegexOption.IGNORE_CASE)
    private val effortRegex = Regex("with\\s+(\\w+)\\s+effort", RegexOption.IGNORE_CASE)

    override fun parse(banner: String): Pair<String, String>? {
        val text = BannerParser.stripAnsi(banner)
        val model = modelRegex.find(text)
            ?.value
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
            .orEmpty()
        val effort = effortRegex.find(text)?.groupValues?.get(1)?.lowercase().orEmpty()
        return if (model.isNotEmpty() || effort.isNotEmpty()) model to effort else null
    }
}

/**
 * Codex's welcome box carries a `model: <id> [<effort>]` line. Older versions
 * printed the reasoning level on a separate `reasoning effort: <level>` line
 * instead, so both forms are accepted.
 *
 * The box is first painted with `model: loading` and repainted in place once the
 * real value is known, so a buffer holding the whole startup burst contains both.
 * The placeholder is skipped, and returning null while it is the only candidate
 * leaves the caller's buffer open until the repaint arrives.
 */
object CodexBannerParser : BannerParser {
    // Tabs/spaces rather than \s so the effort group can't reach onto the next line.
    // The trailing lookahead requires a delimiter, so a buffer that ends mid-token
    // (reads are chunked, not line-aligned) yields no match instead of a truncated id.
    private val modelRegex =
        Regex("model:[ \\t]*([\\w.\\-]+)(?:[ \\t]+(\\w+))?(?=[^\\w.\\-])", RegexOption.IGNORE_CASE)
    private val effortRegex = Regex("reasoning[ \\t]+effort:[ \\t]*(\\w+)", RegexOption.IGNORE_CASE)
    private const val PLACEHOLDER_MODEL = "loading"

    override fun parse(banner: String): Pair<String, String>? {
        val text = BannerParser.stripAnsi(banner)
        // Last usable match, not the first: the first is usually the placeholder.
        val match = modelRegex.findAll(text).lastOrNull { !it.isPlaceholder() } ?: return null
        val effort = effortRegex.find(text)?.groupValues?.get(1) ?: match.groupValues[2]
        return match.groupValues[1].lowercase() to effort.lowercase()
    }

    // Rendered with or without a trailing ellipsis depending on the version.
    private fun MatchResult.isPlaceholder(): Boolean =
        groupValues[1].trimEnd('.').equals(PLACEHOLDER_MODEL, ignoreCase = true)
}
