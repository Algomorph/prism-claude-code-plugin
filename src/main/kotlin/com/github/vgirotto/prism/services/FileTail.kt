package com.github.vgirotto.prism.services

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the last bytes of a file without loading it.
 *
 * Agent session records are append-only JSONL that routinely reach tens of megabytes, while the
 * facts Prism wants from them — the newest `session_id`, the newest title record — are always
 * near the end. Every caller therefore wants the same thing: a bounded window off the tail.
 *
 * The window is cut at a byte offset, so the first line of the result is usually a partial JSON
 * fragment. That is the callers' contract to handle (they all parse line-by-line and skip what
 * does not parse), not something to paper over here.
 */
object FileTail {

    /** A tail window that comfortably spans several turns' worth of records. */
    const val DEFAULT_BYTES = 64L * 1024

    /** The last [maxBytes] of [file] decoded as UTF-8, or null if it cannot be read. */
    fun read(file: File, maxBytes: Long = DEFAULT_BYTES): String? = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return null
            val window = minOf(length, maxBytes)
            raf.seek(length - window)
            val bytes = ByteArray(window.toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}
