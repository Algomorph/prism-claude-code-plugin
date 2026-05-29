package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli

/**
 * Identifies a toolbar action that can be enabled or hidden per CLI.
 *
 * The mapping in [toolbarItemsFor] is the single source of truth for
 * which toolbar entries make sense for each agent — actions whose
 * underlying CLI command is Claude-specific (e.g. /resume, /compact,
 * /model, /effort) are simply omitted for other agents until a
 * suitable equivalent is wired up.
 */
enum class ToolbarItem {
    RESUME,
    COMPACT,
    CLEAR,
    MODEL,
    EFFORT,
    COST,
    TEMPLATES,
    SETTINGS;
}

private val CLAUDE_ITEMS: Set<ToolbarItem> = ToolbarItem.values().toSet()

private val CODEX_ITEMS: Set<ToolbarItem> = setOf(
    ToolbarItem.TEMPLATES,
    ToolbarItem.SETTINGS,
)

/** Returns the ordered list of toolbar items relevant to [cli]. */
fun toolbarItemsFor(cli: AgentCli): List<ToolbarItem> {
    val allowed = when (cli) {
        AgentCli.CLAUDE -> CLAUDE_ITEMS
        AgentCli.CODEX -> CODEX_ITEMS
    }
    return ToolbarItem.values().filter { it in allowed }
}

/** True if [item] should be shown/enabled for sessions of [cli]. */
fun isToolbarItemAvailable(cli: AgentCli, item: ToolbarItem): Boolean =
    item in toolbarItemsFor(cli)
