package com.github.vgirotto.prism.services.session

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32

/**
 * Follows a file that another program only appends lines to (a JSON Lines log), one complete
 * line at a time, from where the previous [read] stopped. Not thread-safe: callers serialize.
 *
 * Memory stays bounded however much was appended, and however long a line is: the file is read in
 * chunks of [chunkBytes], and a line longer than [maxLineBytes] is skipped, up to and including its
 * line break, even when that break comes in a later [read]. A partial last line is left for the
 * next [read], and only up to [maxLineBytes] of it is read again then.
 *
 * A file replaced since the last [read] is read again from the start, whatever its new size: one
 * moved over it is another file (a new file key, on systems that have them), and one rewritten in
 * place no longer starts with the bytes already read (their checksum). The checksum is compared
 * whenever the file's size or modification time changed, and while that time is too recent to
 * change with a write that keeps the size ([MTIME_RESOLUTION]).
 */
internal class AppendedLines(
    private val file: Path,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    /** Where the first line not yet passed to a caller starts. */
    private var offset = 0L

    /** True while the line at [offset] is too long, and its remaining bytes are being skipped. */
    private var skipping = false

    /** The file key of the file read so far (null where the file system has none). */
    private var fileKey: Any? = null

    /** The checksum of the bytes before [offset], as they were read. */
    private var consumed = CRC32()

    private var lastSize = 0L
    private var lastModified: FileTime? = null

    /** The file was modified so shortly before the last read that a rewrite may keep its time. */
    private var modifiedRecently = false

    /**
     * Passes each complete line appended since the last call to [onLine], without its line break.
     * When the file was truncated or replaced, calls [onReset] first and reads it again from the
     * start. A file that cannot be read is left for the next call.
     */
    fun read(onReset: () -> Unit = {}, onLine: (String) -> Unit) {
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            RandomAccessFile(file.toFile(), "r").use { raf -> readFrom(raf, attributes, onReset, onLine) }
        } catch (_: java.io.IOException) {
            // Not there yet, or not readable now: the next call tries again.
        }
    }

    private fun readFrom(
        raf: RandomAccessFile,
        attributes: BasicFileAttributes,
        onReset: () -> Unit,
        onLine: (String) -> Unit,
    ) {
        val length = raf.length()
        val modified = attributes.lastModifiedTime()
        if (offset > 0 && isReplaced(raf, length, attributes.fileKey(), modified)) {
            offset = 0
            skipping = false
            consumed = CRC32()
            onReset()
        }
        fileKey = attributes.fileKey()
        lastSize = length
        lastModified = modified
        modifiedRecently = Duration.between(modified.toInstant(), Instant.now()) < MTIME_RESOLUTION
        if (length == offset) return

        val start = offset
        raf.seek(offset)
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(chunkBytes)
        var position = offset
        while (position < length) {
            val count = raf.read(chunk, 0, minOf(chunkBytes.toLong(), length - position).toInt())
            if (count <= 0) break
            var from = 0
            while (from < count) {
                val end = indexOfLineBreak(chunk, from, count)
                val stop = if (end < 0) count else end
                if (!skipping) {
                    if (line.size() + (stop - from) > maxLineBytes) {
                        skipping = true
                        line.reset()
                    } else {
                        line.write(chunk, from, stop - from)
                    }
                }
                if (end < 0) break
                if (!skipping) onLine(line.toString(Charsets.UTF_8))
                line.reset()
                skipping = false
                from = end + 1
                offset = position + from
            }
            position += count
        }
        // The rest of a line being skipped is not read again.
        if (skipping) offset = position
        checksum(raf, start, offset, consumed)
    }

    private fun isReplaced(raf: RandomAccessFile, length: Long, key: Any?, modified: FileTime): Boolean {
        if (length < offset || key != fileKey) return true
        val maybeRewritten = length != lastSize || modified != lastModified || modifiedRecently
        return maybeRewritten && checksum(raf, 0, offset, CRC32()).value != consumed.value
    }

    /** Adds the bytes of [raf] from [from] to [to] to [crc], and returns it. */
    private fun checksum(raf: RandomAccessFile, from: Long, to: Long, crc: CRC32): CRC32 {
        raf.seek(from)
        val chunk = ByteArray(chunkBytes)
        var position = from
        while (position < to) {
            val count = raf.read(chunk, 0, minOf(chunkBytes.toLong(), to - position).toInt())
            if (count <= 0) break
            crc.update(chunk, 0, count)
            position += count
        }
        return crc
    }

    private fun indexOfLineBreak(bytes: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (bytes[i] == LINE_BREAK) return i
        return -1
    }

    companion object {
        /** Far beyond any record Prism reads (a hook event or an index line is under 1 KiB). */
        const val DEFAULT_MAX_LINE_BYTES = 64 * 1024
        const val DEFAULT_CHUNK_BYTES = 16 * 1024

        /** Coarser than any file system's modification time: FAT's 2 s, HFS+'s 1 s, Linux's clock tick. */
        val MTIME_RESOLUTION: Duration = Duration.ofSeconds(2)
        private const val LINE_BREAK = '\n'.code.toByte()
    }
}
