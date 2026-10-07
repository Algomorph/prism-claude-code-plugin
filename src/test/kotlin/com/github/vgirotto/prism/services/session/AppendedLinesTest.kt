package com.github.vgirotto.prism.services.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AppendedLinesTest {

    @TempDir lateinit var dir: Path

    private val path get() = dir.resolve("log.jsonl")
    private val file get() = path.toFile()

    private fun AppendedLines.lines(): List<String> = mutableListOf<String>().also { out -> read { out += it } }

    @Test
    fun `lines are read once, across chunk boundaries`() {
        val lines = AppendedLines(path, maxLineBytes = 64, chunkBytes = 3)
        file.writeText("first\nsecond line\n")
        assertEquals(listOf("first", "second line"), lines.lines())
        assertEquals(emptyList<String>(), lines.lines())
        file.appendText("third\n")
        assertEquals(listOf("third"), lines.lines())
    }

    @Test
    fun `a partial last line waits until it is complete`() {
        val lines = AppendedLines(path, maxLineBytes = 64, chunkBytes = 4)
        file.writeText("done\npart")
        assertEquals(listOf("done"), lines.lines())
        file.appendText("ial\n")
        assertEquals(listOf("partial"), lines.lines())
    }

    @Test
    fun `a line longer than the limit is skipped, and the lines after it are read`() {
        val lines = AppendedLines(path, maxLineBytes = 8, chunkBytes = 4)
        file.writeText("ok\n" + "x".repeat(20) + "\nafter\n")
        assertEquals(listOf("ok", "after"), lines.lines())
    }

    @Test
    fun `an over-long partial line is skipped up to the break a later write adds`() {
        val lines = AppendedLines(path, maxLineBytes = 8, chunkBytes = 4)
        file.writeText("ok\n" + "x".repeat(20))
        assertEquals(listOf("ok"), lines.lines())
        file.appendText("y".repeat(20))
        assertEquals(emptyList<String>(), lines.lines())
        // The tail of the long line is short enough on its own, and must still not read as a line.
        file.appendText("zz\nafter\n")
        assertEquals(listOf("after"), lines.lines())
    }

    @Test
    fun `a line exactly at the limit is read`() {
        val lines = AppendedLines(path, maxLineBytes = 8, chunkBytes = 3)
        file.writeText("12345678\n123456789\nend\n")
        assertEquals(listOf("12345678", "end"), lines.lines())
    }

    @Test
    fun `a shorter file is read again from the start`() {
        val lines = AppendedLines(path, maxLineBytes = 64, chunkBytes = 4)
        file.writeText("a long first line\n")
        assertEquals(listOf("a long first line"), lines.lines())
        var resets = 0
        file.writeText("b\n")
        val read = mutableListOf<String>()
        lines.read(onReset = { resets++ }) { read += it }
        assertEquals(1, resets)
        assertEquals(listOf("b"), read)
    }

    @Test
    fun `a missing file reads nothing until it appears`() {
        val lines = AppendedLines(path)
        assertEquals(emptyList<String>(), lines.lines())
        file.writeText("there\n")
        assertEquals(listOf("there"), lines.lines())
    }

    @Test
    fun `multi-byte characters split across chunks are decoded whole`() {
        val lines = AppendedLines(path, maxLineBytes = 64, chunkBytes = 3)
        file.writeText("naïve — 名前 ✳\n")
        assertEquals(listOf("naïve — 名前 ✳"), lines.lines())
    }
}
