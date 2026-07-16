package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class TranscriptSourceTest {

    private fun writeFixtureToTemp(name: String): File {
        val text = javaClass.getResourceAsStream("/transcripts/$name")!!.bufferedReader().readText()
        val tmp = Files.createTempFile("prism-", ".jsonl").toFile()
        tmp.writeText(text)
        tmp.deleteOnExit()
        return tmp
    }

    @Test
    fun `ready delivers a bounded window not the whole history`() {
        val f = writeFixtureToTemp("large-session.jsonl")
        val states = mutableListOf<TranscriptState>()
        StaticTranscriptSource(f, windowSize = 50).subscribe { states.add(it) }

        assertTrue(states.first() is TranscriptState.Loading)
        val ready = states.filterIsInstance<TranscriptState.Ready>().single()
        assertEquals(50, ready.page.messages.size, "window is bounded to 50, not 816")
        assertTrue(ready.page.beforeCursor != null, "a cursor is offered for paging older")
    }

    @Test
    fun `loadOlder pages backwards and stops at the start`() {
        val f = writeFixtureToTemp("large-session.jsonl")
        var ready: TranscriptState.Ready? = null
        val src = StaticTranscriptSource(f, windowSize = 50)
        src.subscribe { if (it is TranscriptState.Ready) ready = it }

        var cursor = ready!!.page.beforeCursor
        var pages = 0
        while (cursor != null) {
            val older = src.loadOlder(cursor) ?: break
            assertTrue(older.messages.isNotEmpty())
            cursor = older.beforeCursor
            pages++
            if (pages > 100) error("paging did not terminate")
        }
        // 816 messages / 50 per page => window + ~16 older pages, terminating cleanly.
        assertTrue(pages in 15..17, "paged to the start in a bounded number of steps: $pages")
    }

    @Test
    fun `missing file is NoTranscriptYet not Error`() {
        val states = mutableListOf<TranscriptState>()
        StaticTranscriptSource(File("/does/not/exist.jsonl")).subscribe { states.add(it) }
        assertTrue(states.any { it is TranscriptState.NoTranscriptYet })
        assertTrue(states.none { it is TranscriptState.Error })
    }

    @Test
    fun `null file is NoTranscriptYet`() {
        val states = mutableListOf<TranscriptState>()
        StaticTranscriptSource(null).subscribe { states.add(it) }
        assertTrue(states.any { it is TranscriptState.NoTranscriptYet })
    }

    @Test
    fun `loadOlder with null cursor returns null`() {
        assertNull(StaticTranscriptSource(null).loadOlder(null))
    }
}
