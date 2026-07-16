package com.github.vgirotto.prism.chatshell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The single trust boundary for every image (design §6.8, R16). Group 0 confirmed
 * transcript images are inline base64, so the primary path decodes + validates that;
 * markdown-embedded `file:` references take the secondary path (canonicalize + root
 * allowlist + reject symlink escape). All accepted raster is **re-encoded to canonical
 * PNG** — this strips metadata and any non-pixel payload — then returned as a bounded
 * `data:` URI. Anything failing (SVG, remote, oversized, undecodable, symlink escape)
 * returns [Blocked]; the browser renders a neutral marker and never dereferences a path.
 */
object MediaResolver {

    /** Raster only; SVG is excluded entirely (it can carry script). */
    val ALLOWED_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

    const val MAX_BYTES = 5 * 1024 * 1024        // 5 MB decoded-input cap
    const val MAX_PIXELS = 4096 * 4096           // total-pixel cap (decompression-bomb guard)

    sealed interface MediaResult
    data class Resolved(val dataUri: String) : MediaResult
    data class Blocked(val reason: String) : MediaResult

    /** Primary path: an inline base64 transcript image. */
    fun resolveBase64(mediaType: String?, base64Data: String?): MediaResult {
        if (mediaType == null || mediaType.lowercase() !in ALLOWED_MEDIA_TYPES) {
            return Blocked("disallowed media type: $mediaType")
        }
        if (base64Data.isNullOrEmpty()) return Blocked("empty image data")
        val trimmed = base64Data.trim()
        // Bound BEFORE decoding: decoded size is ~3/4 of the encoded length. Reject an
        // oversized payload without ever allocating its decoded form (review #3).
        if (trimmed.length.toLong() / 4L * 3L > MAX_BYTES) return Blocked("image too large")
        val bytes = try {
            Base64.getDecoder().decode(trimmed)
        } catch (_: Exception) {
            return Blocked("undecodable base64")
        }
        return reencode(bytes)
    }

    /** Secondary path: a local file referenced by markdown, constrained to allowed roots. */
    fun resolveLocalFile(path: String, allowedRoots: List<File>): MediaResult {
        val raw = File(path)
        val canonical = try { raw.canonicalFile } catch (_: Exception) { return Blocked("uncanonicalizable path") }
        val roots = allowedRoots.mapNotNull { try { it.canonicalFile } catch (_: Exception) { null } }
        val underRoot = roots.any { root -> isUnder(canonical, root) }
        if (!underRoot) return Blocked("path escapes allowed roots (symlink or traversal)")
        if (!canonical.isFile) return Blocked("not a file")
        if (canonical.length() > MAX_BYTES) return Blocked("file too large")
        val bytes = try { canonical.readBytes() } catch (_: Exception) { return Blocked("unreadable file") }
        return reencode(bytes)
    }

    private fun isUnder(child: File, root: File): Boolean {
        var p: File? = child
        while (p != null) {
            if (p == root) return true
            p = p.parentFile
        }
        return false
    }

    /** Decode (MIME-by-decode), enforce pixel cap, re-encode to canonical PNG, inline. */
    private fun reencode(bytes: ByteArray): MediaResult {
        if (bytes.size > MAX_BYTES) return Blocked("image too large")
        // Read dimensions from the header and enforce the pixel cap BEFORE decoding the full
        // raster, so a small but huge-dimensioned file (decompression bomb) can't force a
        // multi-hundred-MB allocation inside ImageIO.read (review #3).
        if (!dimensionsWithinCap(bytes)) return Blocked("image dimensions out of bounds")
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        } ?: return Blocked("undecodable or unsupported image (not a valid raster)")
        val pixels = image.width.toLong() * image.height.toLong()
        if (pixels <= 0 || pixels > MAX_PIXELS) return Blocked("image dimensions out of bounds")
        val out = ByteArrayOutputStream()
        val ok = try { ImageIO.write(image, "png", out) } catch (_: Exception) { false }
        if (!ok || out.size() == 0) return Blocked("re-encode failed")
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return Resolved("data:image/png;base64,$b64")
    }

    /**
     * Inspect only the format header via an [javax.imageio.ImageReader] to learn the pixel
     * dimensions without decoding the pixels. Returns false if the declared dimensions
     * exceed [MAX_PIXELS]. Undecodable/unknown formats return true here and are rejected
     * later by the actual decode (this guard is purely a pre-allocation size gate).
     */
    private fun dimensionsWithinCap(bytes: ByteArray): Boolean {
        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { iis ->
                if (iis == null) return true
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) return true
                val reader = readers.next()
                try {
                    reader.input = iis
                    val w = reader.getWidth(reader.minIndex).toLong()
                    val h = reader.getHeight(reader.minIndex).toLong()
                    val pixels = w * h
                    pixels in 1..MAX_PIXELS.toLong()
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            // Header unreadable: let the full decode make the final call.
            true
        }
    }
}
