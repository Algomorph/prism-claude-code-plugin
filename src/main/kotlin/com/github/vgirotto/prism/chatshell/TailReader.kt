package com.github.vgirotto.prism.chatshell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Byte-offset incremental reader for a growing JSONL file (design §6.7). Tracks a byte
 * offset, reads only appended bytes, buffers a **partial trailing line** until its newline
 * arrives, and detects **truncation/rotation**. Pure and synchronous so the tailing logic
 * is unit-testable without threads or a running IDE.
 *
 * Two correctness properties (review #8):
 *   - The partial buffer holds raw **bytes**, not a decoded string, so a multibyte UTF-8
 *     character split across two polls is reassembled instead of corrupted.
 *   - Rotation is detected by file **identity** (inode/fileKey, else creation time), not
 *     only by a shorter length — so a `/clear` that recreates the file at the same or a
 *     larger size is still noticed and its beginning is not skipped.
 *
 * Each poll reads at most [maxChunkBytes] so the first poll on a large existing transcript
 * cannot force one enormous allocation (review #3); the remainder drains on later polls.
 */
class TailReader(
    private val file: File,
    private val maxChunkBytes: Int = 1 shl 20, // 1 MiB per poll
) {

    @Volatile var offset: Long = 0L
        private set
    private var partial = ByteArrayOutputStream()
    private var identity: String? = null

    data class Result(val completeLines: List<String>, val rotated: Boolean)

    @Synchronized
    fun reset() {
        offset = 0L
        partial = ByteArrayOutputStream()
        identity = null
    }

    /** Read newly-appended complete lines since the last poll. */
    @Synchronized
    fun poll(): Result {
        if (!file.isFile) return Result(emptyList(), false)

        val currentIdentity = fileIdentity()
        val len = file.length()
        var rotated = false
        // Rotation: a different underlying file, or the file shrank below our offset.
        if ((identity != null && currentIdentity != null && currentIdentity != identity) || len < offset) {
            offset = 0L
            partial = ByteArrayOutputStream()
            rotated = true
        }
        identity = currentIdentity

        if (len <= offset) return Result(emptyList(), rotated)

        val toRead = minOf((len - offset), maxChunkBytes.toLong()).toInt()
        val chunk = ByteArray(toRead)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(chunk)
        }
        offset += toRead
        partial.write(chunk)

        // Split the accumulated bytes on newline; decode only complete lines. Trailing
        // bytes after the last newline stay buffered (may be a partial line OR a partial
        // multibyte character) until more bytes arrive.
        val buf = partial.toByteArray()
        val complete = ArrayList<String>()
        var lineStart = 0
        for (i in buf.indices) {
            if (buf[i] == '\n'.code.toByte()) {
                complete.add(String(buf, lineStart, i - lineStart, StandardCharsets.UTF_8))
                lineStart = i + 1
            }
        }
        partial = ByteArrayOutputStream()
        if (lineStart < buf.size) partial.write(buf, lineStart, buf.size - lineStart)

        return Result(complete, rotated)
    }

    /** Inode/fileKey when the filesystem exposes one, else creation time; null if unknown. */
    private fun fileIdentity(): String? = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        (attrs.fileKey()?.toString()) ?: attrs.creationTime().toString()
    } catch (_: Exception) {
        null
    }
}
