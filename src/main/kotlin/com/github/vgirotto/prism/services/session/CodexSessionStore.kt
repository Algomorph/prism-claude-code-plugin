package com.github.vgirotto.prism.services.session

import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Codex's own record of its threads under `$CODEX_HOME` (verified against codex-cli 0.159.0):
 *
 *  - `session_index.jsonl`: one `{"id","thread_name","updated_at"}` line per naming event
 *    (generated or `/rename`), appended; the last line for an id is the thread's current name.
 *  - `sessions/YYYY/MM/DD/rollout-<local timestamp>-<id>.jsonl`: one file per thread.
 *
 * Thread ids are UUIDv7, so the first 48 bits of an id are its creation time in UTC milliseconds.
 * That is what lets [complete] look for a thread in three day directories instead of the tree.
 *
 * The index is read incrementally, from where the previous read stopped; a partial last line is
 * left for the next read. Thread-safe.
 */
class CodexSessionStore(private val home: Path) {

    private val indexFile: File get() = home.resolve(INDEX_FILE).toFile()

    private var indexOffset = 0L
    private val names = HashMap<String, String>()

    /** The current name of thread [id], from the index; null if it has none. */
    @Synchronized
    fun threadName(id: String): String? {
        refreshIndex()
        return names[id]
    }

    /**
     * The one thread [hint] names, or null if none or several match. A whole id is taken as is;
     * a prefix is completed against the ids in the index and the rollout files created around
     * the time the prefix encodes.
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

    /** The rollout file of thread [id], if one exists yet (Codex creates it with the first turn). */
    fun rolloutFor(id: String): Path? = rolloutsNear(id)[id]

    /** Rollout files by thread id, from the day directories around the creation time in [idPrefix]. */
    private fun rolloutsNear(idPrefix: String): Map<String, Path> {
        val createdAt = uuidV7Millis(idPrefix) ?: return emptyMap()
        val day = Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC).toLocalDate()
        val found = HashMap<String, Path>()
        // Directories are named by local date, so the UTC date can be a day off either way.
        for (offset in -1L..1L) {
            val dir = dayDir(day.plusDays(offset))
            val files = dir.toFile().listFiles() ?: continue
            for (file in files) {
                val name = file.name
                if (!name.startsWith(ROLLOUT_PREFIX) || !name.endsWith(ROLLOUT_SUFFIX)) continue
                val id = name.removeSuffix(ROLLOUT_SUFFIX).takeLast(ID_LENGTH)
                if (id.startsWith(idPrefix)) found[id] = file.toPath()
            }
        }
        return found
    }

    private fun dayDir(date: LocalDate): Path =
        home.resolve(SESSIONS_DIR).resolve(date.format(DAY_PATH).replace('/', File.separatorChar))

    /** Reads what was appended to the index since the last call. */
    private fun refreshIndex() {
        val file = indexFile
        val length = file.length()
        if (length < indexOffset) {
            // Replaced or truncated: start over.
            indexOffset = 0
            names.clear()
        }
        if (length == indexOffset) return
        val bytes = try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(indexOffset)
                ByteArray((raf.length() - indexOffset).toInt().coerceAtLeast(0)).also { raf.readFully(it) }
            }
        } catch (_: Exception) {
            return
        }
        val complete = bytes.lastIndexOf('\n'.code.toByte())
        if (complete < 0) return
        indexOffset += complete + 1
        String(bytes, 0, complete + 1, Charsets.UTF_8).lineSequence().forEach(::readIndexLine)
    }

    private fun readIndexLine(line: String) {
        if (line.isBlank()) return
        try {
            val record = JsonParser.parseString(line).asJsonObject
            val id = record.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return
            val name = record.get("thread_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            if (name.isNullOrEmpty()) names.remove(id) else names[id] = name
        } catch (_: Exception) {
            // A malformed line says nothing about any thread.
        }
    }

    companion object {
        const val INDEX_FILE = "session_index.jsonl"
        const val SESSIONS_DIR = "sessions"
        private const val ROLLOUT_PREFIX = "rollout-"
        private const val ROLLOUT_SUFFIX = ".jsonl"
        private const val ID_LENGTH = 36
        private val DAY_PATH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

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
