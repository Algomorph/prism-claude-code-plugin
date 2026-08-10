package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli

/**
 * Identifies a toolbar action that can be enabled or hidden per CLI.
 *
 * The mapping in [toolbarItemsFor] is the single source of truth for
 * which toolbar entries make sense for each agent. An item is exposed for
 * a CLI once its action knows how to drive that CLI — either because the
 * underlying slash command is identical (e.g. /resume, /compact, /clear)
 * or because the action branches to a CLI-specific flow (e.g. the Model,
 * Effort and Cost buttons drive Codex's interactive pickers). Items are
 * omitted for a CLI only until such an equivalent is wired up.
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
    // Codex exposes /resume with the same "pick a saved conversation" semantics
    // as Claude, so the button maps to the identical command.
    ToolbarItem.RESUME,
    // /compact ("Summarize the visible conversation to free tokens") behaves the
    // same on Codex, so the shared CompactAction command works unchanged.
    ToolbarItem.COMPACT,
    // /clear ("clear the terminal and start a new chat") is the Codex equivalent
    // of Claude's /clear, so the shared ClearAction command works unchanged.
    ToolbarItem.CLEAR,
    // Codex changes model via its interactive /model picker (see CodexModelPicker);
    // the Model button drives that picker rather than sending "/model <name>".
    ToolbarItem.MODEL,
    // Codex has no /effort command — reasoning level is chosen on the second step
    // of the /model picker, so the Effort button drives that same picker.
    ToolbarItem.EFFORT,
    // Codex has no /cost; the Cost button opens /usage and selects "Show usage",
    // which reports recent account token usage.
    ToolbarItem.COST,
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
