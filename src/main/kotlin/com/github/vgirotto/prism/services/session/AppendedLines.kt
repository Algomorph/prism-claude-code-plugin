package com.github.vgirotto.prism.services.session

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Follows a file that another program only appends lines to (a JSON Lines log), one complete
 * line at a time, from where the previous [read] stopped. Not thread-safe: callers serialize.
 *
 * Memory stays bounded however much was appended, and however long a line is: the file is read in
 * chunks of [chunkBytes], and a line longer than [maxLineBytes] is skipped, up to and including its
 * line break, even when that break comes in a later [read]. A partial last line is left for the
 * next [read], and only up to [maxLineBytes] of it is read again then.
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

    /**
     * Passes each complete line appended since the last call to [onLine], without its line break.
     * When the file has become shorter than what was read (truncated or replaced), calls [onReset]
     * first and reads it again from the start. A file that cannot be read is left for the next call.
     */
    fun read(onReset: () -> Unit = {}, onLine: (String) -> Unit) {
        try {
            RandomAccessFile(file.toFile(), "r").use { raf -> readFrom(raf, onReset, onLine) }
        } catch (_: java.io.IOException) {
            // Not there yet, or not readable now: the next call tries again.
        }
    }

    private fun readFrom(raf: RandomAccessFile, onReset: () -> Unit, onLine: (String) -> Unit) {
        val length = raf.length()
        if (length < offset) {
            offset = 0
            skipping = false
            onReset()
        }
        if (length == offset) return

        raf.seek(offset)
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(chunkBytes)
        var position = offset
        while (position < length) {
            val count = raf.read(chunk, 0, minOf(chunkBytes.toLong(), length - position).toInt())
            if (count <= 0) break
            var start = 0
            while (start < count) {
                val end = indexOfLineBreak(chunk, start, count)
                val stop = if (end < 0) count else end
                if (!skipping) {
                    if (line.size() + (stop - start) > maxLineBytes) {
                        skipping = true
                        line.reset()
                    } else {
                        line.write(chunk, start, stop - start)
                    }
                }
                if (end < 0) break
                if (!skipping) onLine(line.toString(Charsets.UTF_8))
                line.reset()
                skipping = false
                start = end + 1
                offset = position + start
            }
            position += count
        }
        // The rest of a line being skipped is not read again.
        if (skipping) offset = position
    }

    private fun indexOfLineBreak(bytes: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (bytes[i] == LINE_BREAK) return i
        return -1
    }

    companion object {
        /** Far beyond any record Prism reads (a hook event or an index line is under 1 KiB). */
        const val DEFAULT_MAX_LINE_BYTES = 64 * 1024
        const val DEFAULT_CHUNK_BYTES = 16 * 1024
        private const val LINE_BREAK = '\n'.code.toByte()
    }
}
