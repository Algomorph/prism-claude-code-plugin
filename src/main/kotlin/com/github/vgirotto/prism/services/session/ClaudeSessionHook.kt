package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.shellQuote
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * The `SessionStart` hook Prism gives each Claude tab, so the tab knows exactly which conversation
 * it shows (verified against Claude Code 2.1.284).
 *
 * The hook comes in through `--settings '<json>'`: it runs with no trust prompt, alongside the
 * user's own hooks, and fires at once on startup, `/resume`, `/clear` and compaction. Claude pipes
 * it `{"session_id","transcript_path","cwd","source",…}`; the command appends that, plus a line
 * break, to the tab's own events file, which [ClaudeHookEventReader] follows.
 *
 * Claude keeps only the last `--settings` it is given (checked: two flags are not merged), so a
 * user command that already passes one gets no hook, and its session stays unknown; see
 * [ClaudeSessionStrategy].
 */
object ClaudeSessionHook {

    const val EVENTS_FILE = "claude-session-events.jsonl"

    /** The shell command Claude runs for each event. */
    fun command(eventsFile: Path): String = "{ cat; echo; } >> " + shellQuote(eventsFile.toString())

    /**
     * The `--settings` value. Also turns `showStatusInTerminalTab` off: with it on, Claude drops
     * the status glyph from its title, which is how [ClaudeTitleParser] tells Claude's titles from
     * the shell's. The setting only feeds a host terminal's tab status, which Prism does not show.
     */
    fun settingsJson(eventsFile: Path): String {
        val hook = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", command(eventsFile))
        }
        val matcher = JsonObject().apply { add("hooks", JsonArray().apply { add(hook) }) }
        val hooks = JsonObject().apply { add("SessionStart", JsonArray().apply { add(matcher) }) }
        return JsonObject().apply {
            add("hooks", hooks)
            addProperty("showStatusInTerminalTab", false)
        }.toString()
    }
}

/**
 * Follows a tab's Claude hook events file. Each complete line is one session switch; the newest
 * one is the session the tab shows now. A partial last line is left for the next [poll], and the
 * file is read with bounded memory (see [AppendedLines]).
 */
class ClaudeHookEventReader(file: Path) : IdentityEventSource {

    private val lines = AppendedLines(file)

    @Synchronized
    override fun poll(): SessionIdentity? {
        var latest: SessionIdentity? = null
        lines.read { line -> identityIn(line)?.let { latest = it } }
        return latest
    }

    private fun identityIn(line: String): SessionIdentity? {
        if (line.isBlank()) return null
        return try {
            val event = JsonParser.parseString(line).asJsonObject
            val id = event.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() } ?: return null
            val transcript = event.get("transcript_path")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }
            SessionIdentity(id, transcript)
        } catch (_: Exception) {
            null
        }
    }
}
