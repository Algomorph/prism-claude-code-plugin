package com.github.vgirotto.prism.services.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

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

    private fun AppendedLines.readCountingResets(): Pair<Int, List<String>> {
        var resets = 0
        val read = mutableListOf<String>()
        read(onReset = { resets++ }) { read += it }
        return resets to read
    }

    @Test
    fun `appending is not taken for a replacement`() {
        val lines = AppendedLines(path)
        file.writeText("one\n")
        lines.lines()
        file.appendText("two\n")
        assertEquals(0 to listOf("two"), lines.readCountingResets())
    }

    @Test
    fun `a file rewritten in place at the same size is read again from the start`() {
        val lines = AppendedLines(path)
        file.writeText("{\"id\":\"a\",\"thread_name\":\"Old\"}\n")
        lines.lines()
        file.writeText("{\"id\":\"a\",\"thread_name\":\"New\"}\n")
        assertEquals(1 to listOf("{\"id\":\"a\",\"thread_name\":\"New\"}"), lines.readCountingResets())
    }

    @Test
    fun `a file rewritten in place larger is read again from the start`() {
        val lines = AppendedLines(path)
        file.writeText("old\n")
        lines.lines()
        file.writeText("new\nand more\n")
        assertEquals(1 to listOf("new", "and more"), lines.readCountingResets())
    }

    @Test
    fun `a rewrite of an earlier line that keeps the last line is read again from the start`() {
        val lines = AppendedLines(path)
        val last = "a last line, longer than any check of the bytes just before the read position\n"
        file.writeText("Before\n$last")
        lines.lines()
        file.writeText("After!\n$last")
        assertEquals(1 to listOf("After!", last.trim()), lines.readCountingResets())
        file.writeText("After!\n${last}appended\n")
        assertEquals(0 to listOf("appended"), lines.readCountingResets())
        file.writeText("Later, longer\n${last}appended\nand more\n")
        assertEquals(1 to listOf("Later, longer", last.trim(), "appended", "and more"), lines.readCountingResets())
    }

    @Test
    fun `a same-size rewrite long after the last change is noticed by its modification time`() {
        val lines = AppendedLines(path)
        val anHourAgo = FileTime.from(Instant.now().minus(Duration.ofHours(1)))
        file.writeText("one\ntwo\n")
        Files.setLastModifiedTime(path, anHourAgo)
        lines.lines()
        assertEquals(0 to emptyList<String>(), lines.readCountingResets())
        file.writeText("ONE\ntwo\n")
        Files.setLastModifiedTime(path, FileTime.from(anHourAgo.toInstant().plusSeconds(1)))
        assertEquals(1 to listOf("ONE", "two"), lines.readCountingResets())
    }

    @Test
    fun `a file moved over the old one is read again from the start, even when it only adds lines`() {
        val lines = AppendedLines(path)
        file.writeText("one\n")
        lines.lines()
        val replacement = dir.resolve("log.jsonl.tmp")
        replacement.toFile().writeText("one\ntwo\n")
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        assumeTrue(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() != null) {
            "this file system has no file keys"
        }
        assertEquals(1 to listOf("one", "two"), lines.readCountingResets())
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
