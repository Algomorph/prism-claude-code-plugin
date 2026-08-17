package com.github.vgirotto.prism.services

import java.io.File

/**
 * Resolves the current [ChatName] for one chat by reading the agent CLI's own session record.
 *
 * Implementations are polled by [ChatNameWatcher] on a background thread and must be
 * self-contained: locating the right file is part of the job, because neither CLI tells its
 * caller which file it writes to. They take the *directory* or *file* to look at as a supplier
 * so the caller owns path resolution (and so tests can point them at a temp dir).
 */
interface ChatNameSource {
    /** The best name available right now, or null if the CLI has not recorded one yet. */
    fun poll(): ChatName?
}

/**
 * Claude Code names, read from the conversation file this chat is currently writing to.
 *
 * A chat is launched with `--session-id <launchSessionId>` and every content line Claude writes
 * carries that id in a snake-case `session_id` field — even after a `/resume` moved the chat to
 * a different conversation file. So "our file" is the most recently touched `.jsonl` whose
 * newest `session_id` is ours, which is the same signal
 * [com.github.vgirotto.prism.chatshell.SiblingTranscriptWatcher] follows for the transcript.
 *
 * One tail read per candidate serves both purposes — identifying the file *and* extracting its
 * title — and the scan stops at the first match, so an active chat costs a single read per poll.
 */
class ClaudeChatNameSource(
    private val conversationDir: () -> File?,
    private val launchSessionId: String,
) : ChatNameSource {

    override fun poll(): ChatName? {
        val dir = conversationDir() ?: return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return null
        val candidates = files.sortedByDescending { it.lastModified() }.take(CANDIDATE_LIMIT)

        for (file in candidates) {
            val tail = FileTail.read(file) ?: continue
            if (ClaudeChatNameParser.latestSessionId(tail) != launchSessionId) continue
            // Ours. A generated title normally lands after the first assistant turn; until then
            // stand in with what the user typed, so the tab is informative from turn one.
            return ClaudeChatNameParser.titleIn(tail)
                ?: ChatName.of(
                    ClaudeChatNameParser.firstUserMessage(readHead(file)),
                    ChatName.Origin.FIRST_MESSAGE,
                )
        }
        return null
    }

    private companion object {
        /**
         * How many of the newest conversation files to tail-scan looking for ours.
         *
         * An active chat is by definition among the most recently written, so this only bites
         * for a chat that has been idle while many others were used — and an idle chat's title
         * cannot have changed, so [ChatNameWatcher] simply keeps the name it already resolved.
         * The cap is what keeps a project with hundreds of past conversations from re-reading
         * all of them on every poll.
         */
        const val CANDIDATE_LIMIT = 25
    }
}

/**
 * Codex names, derived from the first user message in the project's rollout file.
 *
 * Codex 0.146 records no title of any kind, so this is the only name available — and it is the
 * same one Codex itself shows in `/resume`. The rollout is resolved by the caller (cwd +
 * recency, see [com.github.vgirotto.prism.chatshell.CodexSessionResolver]), inheriting that
 * resolver's documented heuristic: Codex takes no caller-supplied session id, so a second Codex
 * session for the same project from another window can race.
 *
 * That heuristic can also land on a rollout Codex wrote for a sub-agent it spawned in the same
 * directory, whose first "user" message is a system-composed prompt. Those are declined rather
 * than turned into a tab label — an unnamed tab beats a misleading one.
 */
class CodexChatNameSource(
    private val rolloutFile: () -> File?,
) : ChatNameSource {

    override fun poll(): ChatName? {
        val file = rolloutFile() ?: return null
        val head = readHead(file).toList()
        if (CodexChatNameParser.isSubagentThread(head.asSequence())) return null
        return ChatName.of(
            CodexChatNameParser.firstUserMessage(head.asSequence()),
            ChatName.Origin.FIRST_MESSAGE,
        )
    }
}

/**
 * The first lines of a file, bounded by both line count and total characters.
 *
 * The first user message sits within the first handful of records in both schemas, but a single
 * record can be enormous (Codex embeds its base instructions, and sub-agent prompts run to tens
 * of kilobytes), so a line budget alone is not a bound on the work done.
 */
private fun readHead(
    file: File,
    maxLines: Int = HEAD_MAX_LINES,
    maxChars: Int = HEAD_MAX_CHARS,
): Sequence<String> {
    val lines = ArrayList<String>(maxLines.coerceAtMost(64))
    try {
        file.bufferedReader().use { reader ->
            var budget = maxChars
            while (lines.size < maxLines && budget > 0) {
                val line = reader.readLine() ?: break
                budget -= line.length
                lines.add(line)
            }
        }
    } catch (_: Exception) {
        // A partially written or vanished file is normal at session start; return what we got.
    }
    return lines.asSequence()
}

private const val HEAD_MAX_LINES = 60
private const val HEAD_MAX_CHARS = 256 * 1024
