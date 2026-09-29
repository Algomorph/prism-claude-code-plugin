package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.ClaudeValidationService.VersionGate
import com.github.vgirotto.prism.services.CodexValidationService
import com.github.vgirotto.prism.services.ResolvedCliCommand
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Codex: Prism asks for a title of the thread id and thread name ([CodexTitleParser]). The id is
 * cut off in the title, so it is completed against Codex's own ids ([CodexSessionStore]); a name
 * the title cut off is read in full from Codex's index.
 *
 * No Codex hook: Codex blocks startup with a "Hooks need review" screen until one is approved,
 * and its `SessionStart` fires only when the next turn starts, not at `/resume` or `/new`.
 */
class CodexSessionStrategy(
    private val store: CodexSessionStore,
    private val versionOf: (executable: String) -> String? = {
        CodexValidationService.getInstance().getCodexVersion(it)
    },
) : AgentSessionStrategy {

    override fun launchArguments(tab: TabSessionFiles, command: ResolvedCliCommand): List<String> {
        if (setsTerminalTitle(command.arguments)) {
            log.info("Codex command already sets tui.terminal_title: the tab keeps its number")
            return emptyList()
        }
        if (!supportsTitleItems(command.executable)) {
            log.info("Codex older than $MIN_VERSION: the tab keeps its number")
            return emptyList()
        }
        return listOf("-c", TITLE_OVERRIDE)
    }

    override fun launchEnvironment(): Map<String, String?> = emptyMap()

    override fun parseTitle(title: String): TitleReading? = CodexTitleParser.parse(title)

    override fun identityEvents(tab: TabSessionFiles): IdentityEventSource? = null

    override fun resolveIdentity(hint: IdHint): SessionIdentity? = store.complete(hint)

    override fun fullName(identity: SessionIdentity, shown: String): String? =
        fullNameBehind(store.threadName(identity.sessionId), shown)

    private fun supportsTitleItems(executable: String): Boolean =
        supportByExecutable.getOrPut(executable) {
            val version = try { versionOf(executable) } catch (_: Exception) { null }
            version != null && VersionGate.compareVersions(version, MIN_VERSION) >= 0
        }

    companion object {
        private val log = Logger.getInstance(CodexSessionStrategy::class.java)

        /** The first release verified to render `thread-id` and `thread-name` as parsed here. */
        const val MIN_VERSION = "0.159.0"

        const val TITLE_OVERRIDE = """tui.terminal_title=["thread-id","thread-name"]"""

        /** Title-item support per resolved executable, so a changed CLI path is probed again. */
        private val supportByExecutable = ConcurrentHashMap<String, Boolean>()

        /** True when the user's own arguments already choose the title items. */
        internal fun setsTerminalTitle(arguments: List<String>): Boolean {
            fun overridesTitle(value: String) = value.trimStart().let {
                it.startsWith("tui.terminal_title") || it.startsWith("tui=")
            }
            return arguments.withIndex().any { (i, arg) ->
                when {
                    arg == "-c" || arg == "--config" -> arguments.getOrNull(i + 1)?.let(::overridesTitle) == true
                    arg.startsWith("--config=") -> overridesTitle(arg.removePrefix("--config="))
                    arg.startsWith("-c") && arg.length > 2 -> overridesTitle(arg.substring(2).removePrefix("="))
                    else -> false
                }
            }
        }

        /**
         * [stored], sanitized as Codex's title prints text, if Codex would show it as [shown]; else
         * null (an older name the index still holds, or no name).
         */
        internal fun fullNameBehind(stored: String?, shown: String): String? {
            if (stored == null || CodexTitleParser.titleText(stored) != shown) return null
            return CodexTitleParser.normalize(stored).takeIf { it.isNotEmpty() }
        }

        internal fun clearCacheForTests() = supportByExecutable.clear()
    }
}
