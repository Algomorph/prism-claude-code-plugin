package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class TailReaderTest {

    private fun tempFile(): File = Files.createTempFile("prism-tail", ".jsonl").toFile().apply { deleteOnExit() }

    @Test
    fun `reads only newly appended complete lines`() {
        val f = tempFile()
        f.writeText("line1\nline2\n")
        val reader = TailReader(f)
        val first = reader.poll()
        assertEquals(listOf("line1", "line2"), first.completeLines)
        assertFalse(first.rotated)

        // No change -> nothing new.
        assertTrue(reader.poll().completeLines.isEmpty())

        // Append.
        f.appendText("line3\n")
        assertEquals(listOf("line3"), reader.poll().completeLines)
    }

    @Test
    fun `buffers a partial trailing line until its newline arrives`() {
        val f = tempFile()
        f.writeText("comp\npar")
        val reader = TailReader(f)
        assertEquals(listOf("comp"), reader.poll().completeLines) // "par" buffered
        f.appendText("tial\n")
        assertEquals(listOf("partial"), reader.poll().completeLines) // reassembled
    }

    @Test
    fun `detects truncation and rotation`() {
        val f = tempFile()
        f.writeText("a\nb\n")
        val reader = TailReader(f)
        reader.poll()
        // Truncate/recreate (e.g. /clear).
        f.writeText("x\n")
        val res = reader.poll()
        assertTrue(res.rotated)
        assertEquals(listOf("x"), res.completeLines)
    }

    @Test
    fun `missing file yields nothing`() {
        val reader = TailReader(File("/no/such/file.jsonl"))
        assertTrue(reader.poll().completeLines.isEmpty())
    }
}
