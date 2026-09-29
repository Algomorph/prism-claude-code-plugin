package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.ResolvedCliCommand
import java.io.File
import java.nio.file.Path

/**
 * How Prism follows one agent CLI's chat sessions: what to add to its launch, how to read its
 * terminal title, and where to find the exact session behind a title.
 *
 * The terminal title is the naming channel for every agent — each CLI updates it the moment the
 * chat is renamed, resumed or titled, so the tab name is whatever the CLI shows, with no file
 * matching. Session identity comes from wherever that CLI exposes it exactly (a Claude hook, a
 * Codex id prefix completed against Codex's own ids).
 *
 * One instance per tab: implementations may keep per-tab read state. Supporting another agent
 * means adding one implementation and one line to [newSessionStrategy].
 */
interface AgentSessionStrategy {

    /**
     * The command to type for this tab: [command] (the user's, with Prism's own flags) with what
     * this strategy needs to follow the session. It may add arguments or wrap the command.
     */
    fun launchCommand(tab: TabSessionFiles, command: ResolvedCliCommand): ResolvedCliCommand

    /** Environment changes for the session's shell; a null value removes the variable. */
    fun launchEnvironment(): Map<String, String?>

    /** What [title] says about the chat, or null if it is not a title this CLI writes. */
    fun parseTitle(title: String): TitleReading?

    /** Identity changes the title does not show (Claude: hook events), or null if none. */
    fun identityEvents(tab: TabSessionFiles): IdentityEventSource?

    /** Complete a title's id hint to an exact identity, or null if it is not uniquely resolvable. */
    fun resolveIdentity(hint: IdHint): SessionIdentity?

    /**
     * The full name of [identity], from the CLI's own store, for a title that shows [shown] and may
     * have cut it off. Null unless the stored name is one the CLI would show as [shown]: the store
     * can lag the title, and [shown] may be the user's own name rather than a cut-off one.
     */
    fun fullName(identity: SessionIdentity, shown: String): String?
}

/** A stream of session switches, polled; [poll] returns the newest one since the last call. */
fun interface IdentityEventSource {
    fun poll(): SessionIdentity?
}

/** A tab's private working directory, `<IDE temp>/prism-sessions/<session id>/`. */
class TabSessionFiles(val dir: Path) {

    val claudeEvents: Path get() = dir.resolve(ClaudeSessionHook.EVENTS_FILE)

    /** The `CODEX_HOME` the launched Codex sees, as its shell recorded it. */
    val codexHome: Path get() = dir.resolve("codex-home")

    fun create(): TabSessionFiles = apply { dir.toFile().mkdirs() }

    fun delete() {
        dir.toFile().deleteRecursively()
    }

    companion object {
        fun under(tempDir: String, sessionId: String): TabSessionFiles =
            TabSessionFiles(File(File(tempDir, "prism-sessions"), sessionId).toPath())
    }
}

/** A fresh strategy for one tab of this CLI. */
fun AgentCli.newSessionStrategy(): AgentSessionStrategy = when (this) {
    AgentCli.CLAUDE -> ClaudeSessionStrategy()
    AgentCli.CODEX -> CodexSessionStrategy()
}
