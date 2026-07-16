package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

class MediaResolverTest {

    private fun pngBase64(w: Int = 4, h: Int = 4): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, 0xFF3366CC.toInt())
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `valid base64 png resolves to a data uri re-encoded as png`() {
        val r = MediaResolver.resolveBase64("image/png", pngBase64())
        assertTrue(r is MediaResolver.Resolved)
        assertTrue((r as MediaResolver.Resolved).dataUri.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `jpeg input is re-encoded to canonical png`() {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        val r = MediaResolver.resolveBase64("image/jpeg", b64)
        assertTrue(r is MediaResolver.Resolved)
        assertTrue((r as MediaResolver.Resolved).dataUri.startsWith("data:image/png;base64,"),
            "all accepted raster is normalized to PNG")
    }

    @Test
    fun `svg is blocked by media type even if data looks like an image`() {
        val r = MediaResolver.resolveBase64("image/svg+xml", pngBase64())
        assertTrue(r is MediaResolver.Blocked)
    }

    @Test
    fun `disallowed media type is blocked`() {
        assertTrue(MediaResolver.resolveBase64("text/html", pngBase64()) is MediaResolver.Blocked)
        assertTrue(MediaResolver.resolveBase64(null, pngBase64()) is MediaResolver.Blocked)
    }

    @Test
    fun `non-image bytes claiming to be png are blocked by decode`() {
        val fake = Base64.getEncoder().encodeToString("<svg onload=alert(1)>".toByteArray())
        assertTrue(MediaResolver.resolveBase64("image/png", fake) is MediaResolver.Blocked)
    }

    @Test
    fun `garbage base64 is blocked`() {
        assertTrue(MediaResolver.resolveBase64("image/png", "!!!!not base64!!!!") is MediaResolver.Blocked)
    }

    @Test
    fun `local file under an allowed root resolves`() {
        val root = Files.createTempDirectory("prism-root").toFile()
        val img = File(root, "pic.png")
        val bi = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(bi, "png", img)
        val r = MediaResolver.resolveLocalFile(img.absolutePath, listOf(root))
        assertTrue(r is MediaResolver.Resolved)
        root.deleteRecursively()
    }

    @Test
    fun `local file outside allowed roots is blocked - symlink or traversal defense`() {
        val root = Files.createTempDirectory("prism-root").toFile()
        val other = Files.createTempDirectory("prism-other").toFile()
        val img = File(other, "pic.png")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", img)
        val r = MediaResolver.resolveLocalFile(img.absolutePath, listOf(root))
        assertTrue(r is MediaResolver.Blocked)
        root.deleteRecursively(); other.deleteRecursively()
    }

    @Test
    fun `traversal path escaping the root is blocked`() {
        val root = Files.createTempDirectory("prism-root").toFile()
        val r = MediaResolver.resolveLocalFile("${root.absolutePath}/../../../etc/hosts", listOf(root))
        assertTrue(r is MediaResolver.Blocked)
        root.deleteRecursively()
    }
}
