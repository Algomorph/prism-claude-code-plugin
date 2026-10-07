package com.github.vgirotto.prism.services.session

import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Codex's own record of its threads under `$CODEX_HOME` (verified against codex-cli 0.159.0):
 *
 *  - `session_index.jsonl`: one `{"id","thread_name","updated_at"}` line per naming event
 *    (generated or `/rename`), appended; the last line for an id is the thread's current name.
 *  - `sessions/YYYY/MM/DD/rollout-<local timestamp>-<thread id>.jsonl`: a thread's rollout file.
 *    `thread/revert` keeps the thread id but moves the thread to a new file, filed under the day
 *    of the revert, whose name adds `_<rollout id>` ([RolloutName]).
 *
 * Thread ids are UUIDv7, so the first 48 bits of an id are its creation time in UTC milliseconds.
 * That is what lets [complete] skip the day directories from before a thread was created.
 *
 * The index is read incrementally, from where the previous read stopped, with bounded memory (see
 * [AppendedLines]); a partial last line is left for the next read. Only the names of the
 * [MAX_NAMES] threads named most recently are kept. Thread-safe.
 */
class CodexSessionStore(private val home: Path) {

    private val index = AppendedLines(home.resolve(INDEX_FILE))

    /** Thread names by id, the most recently named last; at most [MAX_NAMES] of them. */
    private val names = LinkedHashMap<String, String>()

    /** The current name of thread [id], from the index; null if it has none. */
    @Synchronized
    fun threadName(id: String): String? {
        refreshIndex()
        return names[id]
    }

    /**
     * The one thread [hint] names, or null if none or several match. A whole id is taken as is;
     * a prefix is completed against the ids in the index and the rollout files from the time the
     * prefix encodes on.
     */
    @Synchronized
    fun complete(hint: IdHint): SessionIdentity? {
        if (!hint.isPrefix) return SessionIdentity(hint.value, rolloutFor(hint.value)?.toString())
        refreshIndex()
        val rollouts = rolloutsNear(hint.value)
        val candidates = HashSet<String>()
        names.keys.filterTo(candidates) { it.startsWith(hint.value) }
        rollouts.keys.filterTo(candidates) { it.startsWith(hint.value) }
        val id = candidates.singleOrNull() ?: return null
        return SessionIdentity(id, rollouts[id]?.toString() ?: rolloutFor(id)?.toString())
    }

    /** The current rollout file of thread [id], if one exists yet (Codex creates it with the first turn). */
    fun rolloutFor(id: String): Path? = rolloutsNear(id)[id]

    /**
     * The current rollout file of each thread whose id starts with [idPrefix], by thread id.
     *
     * The search starts at the day the prefix encodes as the creation time, less one, since the
     * directories are named by local date. Every later day directory is searched too: a reverted
     * thread's current file is filed under the day of the revert. Of a thread's files, the newest
     * one is current, as Codex picks it: the latest timestamp, then the highest rollout id.
     */
    private fun rolloutsNear(idPrefix: String): Map<String, Path> {
        val createdAt = uuidV7Millis(idPrefix) ?: return emptyMap()
        val firstDay = Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val newest = HashMap<String, Pair<RolloutName, Path>>()
        for (dir in dayDirsFrom(firstDay)) {
            val files = dir.toFile().listFiles() ?: continue
            for (file in files) {
                val name = RolloutName.parse(file.name) ?: continue
                if (!name.threadId.startsWith(idPrefix)) continue
                val current = newest[name.threadId]
                if (current == null || name > current.first) newest[name.threadId] = name to file.toPath()
            }
        }
        return newest.mapValues { it.value.second }
    }

    /** The day directories (`sessions/YYYY/MM/DD`) that exist, from [firstDay] on. */
    private fun dayDirsFrom(firstDay: LocalDate): List<Path> {
        val out = ArrayList<Path>()
        val sessions = home.resolve(SESSIONS_DIR)
        for (year in numberedDirs(sessions)) {
            if (year.first < firstDay.year) continue
            for (month in numberedDirs(year.second)) {
                for (day in numberedDirs(month.second)) {
                    val date = try {
                        LocalDate.of(year.first, month.first, day.first)
                    } catch (_: java.time.DateTimeException) {
                        continue
                    }
                    if (!date.isBefore(firstDay)) out.add(day.second)
                }
            }
        }
        return out
    }

    private fun numberedDirs(parent: Path): List<Pair<Int, Path>> =
        parent.toFile().listFiles()
            ?.mapNotNull { dir -> dir.name.toIntOrNull()?.takeIf { dir.isDirectory }?.let { it to dir.toPath() } }
            .orEmpty()

    /** Reads what was appended to the index since the last call. */
    private fun refreshIndex() {
        index.read(onReset = names::clear, onLine = ::readIndexLine)
    }

    private fun readIndexLine(line: String) {
        if (line.isBlank()) return
        try {
            val record = JsonParser.parseString(line).asJsonObject
            val id = record.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return
            val name = record.get("thread_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            // Removed first, so a renamed thread moves to the end, past the ones dropped first.
            names.remove(id)
            if (name.isNullOrEmpty()) return
            names[id] = name
            if (names.size > MAX_NAMES) names.remove(names.keys.first())
        } catch (_: Exception) {
            // A malformed line says nothing about any thread.
        }
    }

    companion object {
        const val INDEX_FILE = "session_index.jsonl"
        const val SESSIONS_DIR = "sessions"

        /**
         * How many thread names are kept. A tab needs the name of the thread it shows, which Codex
         * names (or renames) last; the index holds every thread the user ever had.
         */
        const val MAX_NAMES = 2_000

        /** `~/.codex`, Codex's home when `CODEX_HOME` and `$HOME` are both unset. */
        fun userDefaultHome(): Path = Paths.get(System.getProperty("user.home"), ".codex")

        /**
         * The creation time a UUIDv7 (or a prefix of one) encodes: its first 12 hex digits, in
         * milliseconds. Null when the prefix is too short to hold them.
         */
        internal fun uuidV7Millis(idPrefix: String): Long? {
            val hex = idPrefix.replace("-", "")
            if (hex.length < 12 || idPrefix.length < 13 || idPrefix[8] != '-') return null
            return hex.substring(0, 12).toLongOrNull(16)
        }
    }
}

/**
 * A rollout file's name, `rollout-<timestamp>-<thread id>[_<rollout id>].jsonl`
 * (`RolloutFileName` in codex-rs/rollout). An ordinary file's one id is both its thread id and its
 * rollout id; a reverted thread's file adds a rollout id of its own after the thread id.
 * Ordered as Codex picks a thread's current file: by timestamp, then by rollout id.
 */
internal data class RolloutName(
    val timestamp: String,
    val threadId: String,
    val rolloutId: String,
) : Comparable<RolloutName> {

    override fun compareTo(other: RolloutName): Int =
        compareValuesBy(this, other, RolloutName::timestamp, RolloutName::rolloutId)

    companion object {
        private val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}""")
        private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

        fun parse(fileName: String): RolloutName? {
            if (!fileName.startsWith("rollout-") || !fileName.endsWith(".jsonl")) return null
            val core = fileName.removePrefix("rollout-").removeSuffix(".jsonl")
            if (core.length < 21 || core[19] != '-') return null
            val timestamp = core.substring(0, 19)
            val ids = core.substring(20)
            val threadId = ids.substringBefore('_')
            val rolloutId = ids.substringAfter('_', threadId)
            if (!TIMESTAMP.matches(timestamp) || !UUID.matches(threadId) || !UUID.matches(rolloutId)) return null
            return RolloutName(timestamp, threadId, rolloutId)
        }
    }
}
