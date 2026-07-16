package com.github.vgirotto.prism.chatshell

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Byte-offset incremental reader for a growing JSONL file (design §6.7). Tracks a byte
 * offset, reads only appended bytes, buffers a **partial trailing line** until its newline
 * arrives, and detects **truncation/rotation** (file shorter than the offset, e.g. after a
 * `/clear` recreates the file) by resetting and signalling. Pure and synchronous so the
 * tailing logic is unit-testable without threads or a running IDE.
 */
class TailReader(private val file: File) {

    @Volatile var offset: Long = 0L
        private set
    private var partial = StringBuilder()

    data class Result(val completeLines: List<String>, val rotated: Boolean)

    @Synchronized
    fun reset() {
        offset = 0L
        partial = StringBuilder()
    }

    /** Read newly-appended complete lines since the last poll. */
    @Synchronized
    fun poll(): Result {
        if (!file.isFile) return Result(emptyList(), false)
        val len = file.length()
        var rotated = false
        if (len < offset) {
            // Truncation or rotation: start over.
            offset = 0L
            partial = StringBuilder()
            rotated = true
        }
        if (len == offset) return Result(emptyList(), rotated)

        val chunk = ByteArray((len - offset).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(chunk)
        }
        offset = len

        val text = partial.toString() + String(chunk, StandardCharsets.UTF_8)
        val parts = text.split("\n")
        // The last element is the remainder after the final newline ("" if text ended with
        // a newline) — hold it as the partial line until more bytes arrive.
        partial = StringBuilder(parts.last())
        val complete = parts.subList(0, parts.size - 1).toList()
        return Result(complete, rotated)
    }
}
