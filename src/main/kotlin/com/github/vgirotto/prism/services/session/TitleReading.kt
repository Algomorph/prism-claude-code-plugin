package com.github.vgirotto.prism.services.session

/**
 * What one terminal title says about the chat, as an agent's [AgentSessionStrategy.parseTitle]
 * reads it. A title the strategy does not recognize (the login shell's own `user@host: dir`, a
 * transient frame) produces no reading at all — null — which callers must ignore rather than
 * treat as "unnamed".
 */
sealed interface TitleReading {
    val idHint: IdHint?

    /** A recognized agent title that carries no chat name yet. */
    data class Unnamed(override val idHint: IdHint?) : TitleReading

    /**
     * A recognized agent title with a chat name. [truncated]: the CLI cut the name off to fit its
     * title, so [name] ends in the CLI's own ellipsis and the full name has to come from its store.
     */
    data class Named(
        val name: String,
        val truncated: Boolean,
        override val idHint: IdHint?,
    ) : TitleReading
}

/** A session id as the title shows it: whole, or only a prefix when the CLI cut it off. */
data class IdHint(val value: String, val isPrefix: Boolean) {

    /** True when [sessionId] is the session this hint names (or, for a prefix, could be). */
    fun matches(sessionId: String): Boolean =
        if (isPrefix) sessionId.startsWith(value) else sessionId == value
}

/** The exact session a tab is showing, when known. */
data class SessionIdentity(val sessionId: String, val transcriptPath: String?)
