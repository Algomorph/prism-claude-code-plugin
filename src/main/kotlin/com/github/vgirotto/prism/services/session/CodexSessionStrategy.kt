package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.ClaudeValidationService.VersionGate
import com.github.vgirotto.prism.services.CodexValidationService
import com.github.vgirotto.prism.services.ResolvedCliCommand
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Codex: Prism asks for a title of the thread id and thread name ([CodexTitleParser]). The id is
 * cut off in the title, so it is completed against Codex's own ids ([CodexSessionStore]); a name
 * the title cut off is read in full from Codex's index.
 *
 * The store is the one under the `CODEX_HOME` the launched Codex itself sees. That is not the
 * IDE's: the tab's interactive login shell runs the user's shell setup first, which may export
 * `CODEX_HOME`, and a desktop-launched IDE never ran it. So Codex is started through `sh`, which
 * records the effective home in the tab's files and then becomes Codex ([HOME_REPORT]).
 *
 * No Codex hook: Codex blocks startup with a "Hooks need review" screen until one is approved,
 * and its `SessionStart` fires only when the next turn starts, not at `/resume` or `/new`.
 */
class CodexSessionStrategy(
    private val versionOf: (executable: String) -> String? = {
        CodexValidationService.getInstance().getCodexVersion(it)
    },
    /** A fixed store, in place of the one at the home the launched Codex reports; for tests. */
    fixedStore: CodexSessionStore? = null,
) : AgentSessionStrategy {

    @Volatile private var store: CodexSessionStore? = fixedStore
    /** Where this tab's shell records the effective `CODEX_HOME`, once launched with tracking. */
    @Volatile private var homeReport: Path? = null

    /**
     * True once this tab launched with Prism's title items. Without them the title has some other
     * layout (the user's own items, or an older Codex's default) that can look the same: with
     * `["thread-id","model"]`, the model would read as the chat name. The tab then keeps its number.
     */
    @Volatile private var titleItemsSet = false

    override fun launchCommand(tab: TabSessionFiles, command: ResolvedCliCommand): ResolvedCliCommand {
        if (setsTerminalTitle(command.arguments)) {
            log.info("Codex command already sets tui.terminal_title: the tab keeps its number")
            return command
        }
        if (!supportsTitleItems(command.executable)) {
            log.info("Codex older than $MIN_VERSION: the tab keeps its number")
            return command
        }
        homeReport = tab.codexHome
        titleItemsSet = true
        return ResolvedCliCommand(
            executable = "/bin/sh",
            arguments = listOf("-c", HOME_REPORT, "sh", tab.codexHome.toString(), command.executable) +
                command.arguments + listOf("-c", TITLE_OVERRIDE),
        )
    }

    override fun launchEnvironment(): Map<String, String?> = emptyMap()

    override fun parseTitle(title: String): TitleReading? =
        if (titleItemsSet) CodexTitleParser.parse(title) else null

    override fun identityEvents(tab: TabSessionFiles): IdentityEventSource? = null

    override fun resolveIdentity(hint: IdHint): SessionIdentity? = store()?.complete(hint)

    override fun fullName(identity: SessionIdentity, shown: String): String? =
        store()?.let { fullNameBehind(it.threadName(identity.sessionId), shown) }

    /** The store at the home the launched Codex reported; null until the shell has recorded it. */
    private fun store(): CodexSessionStore? {
        store?.let { return it }
        val report = homeReport?.toFile() ?: return null
        val text = try { report.readText() } catch (_: Exception) { return null }
        if (!text.endsWith("\n")) return null // Not written yet, or not completely.
        val reported = text.trimEnd('\n')
        val home = if (reported.isEmpty()) CodexSessionStore.userDefaultHome() else Paths.get(reported)
        return synchronized(this) { store ?: CodexSessionStore(home).also { store = it } }
    }

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

        /**
         * `sh -c` script: `$1` is the file to record the home in, the rest is the Codex command.
         * Records `CODEX_HOME` as Codex resolves it (`find_codex_home` in codex-rs/utils/home-dir:
         * the variable when not empty, relative to the working directory, else `$HOME/.codex`),
         * or an empty line when there is no `$HOME` either. Then `exec`s Codex, so Codex is the
         * process in the terminal, as without the script.
         */
        internal const val HOME_REPORT =
            "h=\${CODEX_HOME:-\${HOME:+\$HOME/.codex}}; " +
                "case \$h in /*|'') ;; *) h=\$PWD/\$h ;; esac; " +
                "printf '%s\\n' \"\$h\" > \"\$1\"; shift; exec \"\$@\""

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
