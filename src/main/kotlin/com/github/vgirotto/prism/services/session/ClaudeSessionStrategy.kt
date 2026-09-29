package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.ResolvedCliCommand
import com.intellij.openapi.diagnostic.Logger

/**
 * Claude Code: the title names the chat ([ClaudeTitleParser]); a `SessionStart` hook reports every
 * session switch ([ClaudeSessionHook]), including `/clear`, which starts a new session but keeps
 * the title.
 */
class ClaudeSessionStrategy : AgentSessionStrategy {

    override fun launchArguments(tab: TabSessionFiles, command: ResolvedCliCommand): List<String> {
        if (passesSettings(command.arguments)) {
            // Claude keeps only the last --settings, so adding ours would drop the user's.
            log.info("Claude command already passes --settings: the tab follows the title only")
            return emptyList()
        }
        return listOf("--settings", ClaudeSessionHook.settingsJson(tab.claudeEvents))
    }

    override fun launchEnvironment(): Map<String, String?> = mapOf(
        // Claude writes no title with this set, and the title is the tab's name.
        "CLAUDE_CODE_DISABLE_TERMINAL_TITLE" to null,
        // Markers of a Claude session nested in another, inherited when the IDE itself was
        // started from one. A tab is a top-level session: nested ones do not persist their
        // conversation, so the hook's transcript path would point at nothing.
        "CLAUDECODE" to null,
        "CLAUDE_CODE_ENTRYPOINT" to null,
        "CLAUDE_CODE_CHILD_SESSION" to null,
    )

    override fun parseTitle(title: String): TitleReading? = ClaudeTitleParser.parse(title)

    override fun identityEvents(tab: TabSessionFiles): IdentityEventSource =
        ClaudeHookEventReader(tab.claudeEvents)

    /** Claude's title carries no session id. */
    override fun resolveIdentity(hint: IdHint): SessionIdentity? = null

    /** Claude never cuts its title off. */
    override fun fullName(identity: SessionIdentity): String? = null

    companion object {
        private val log = Logger.getInstance(ClaudeSessionStrategy::class.java)

        internal fun passesSettings(arguments: List<String>): Boolean =
            arguments.any { it == "--settings" || it.startsWith("--settings=") }
    }
}
