package com.github.vgirotto.prism.model

/**
 * The CLI backing an agent session.
 *
 * Prism supports multiple agent CLIs as embedded PTY processes; each session
 * is bound to one of these at creation time.
 */
enum class AgentCli {
    CLAUDE,
    CODEX;

    companion object {
        val DEFAULT: AgentCli = CLAUDE
    }
}
