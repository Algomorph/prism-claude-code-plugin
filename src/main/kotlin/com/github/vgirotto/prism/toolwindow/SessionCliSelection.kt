package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.ResolvedCliCommand

/** Resolve off the EDT. Only automatic launches may fall back from the configured default. */
internal fun resolveSessionCli(
    requested: AgentCli?,
    defaultCli: AgentCli,
    resolve: (AgentCli) -> ResolvedCliCommand?,
): Pair<AgentCli, ResolvedCliCommand?> {
    val preferred = requested ?: defaultCli
    val command = resolve(preferred)
    if (command != null || requested != null) return preferred to command
    for (alternative in AgentCli.values().filter { it != preferred }) {
        val alternativeCommand = resolve(alternative)
        if (alternativeCommand != null) return alternative to alternativeCommand
    }
    // Preserve the preferred agent's installation/configuration error when none is available.
    return preferred to null
}
