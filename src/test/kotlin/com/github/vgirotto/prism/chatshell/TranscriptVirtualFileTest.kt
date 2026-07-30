package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.model.AgentCli
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LightVirtualFile touches the file-type registry, so it needs an Application (no display). */
@TestApplication
class TranscriptVirtualFileTest {

    @Test
    fun `tab title is the chat name with a check mark`() {
        val file = TranscriptVirtualFile("s1", "conv-1", "Chat #3", AgentCli.CLAUDE)
        assertEquals("Chat #3 ✓", file.name)
        assertEquals("Chat #3", file.chatName)
        assertEquals("conv-1", file.convId)
        assertEquals("s1", file.sessionId)
        assertEquals(AgentCli.CLAUDE, file.cli)
    }

    @Test
    fun `identity keys on session id so reopening is idempotent`() {
        // Two instances for the same session (e.g. a fresh one handed to FileEditorManager)
        // must be equal, or a duplicate transcript tab would open.
        val a = TranscriptVirtualFile("s1", "conv-1", "Chat #1", AgentCli.CLAUDE)
        val b = TranscriptVirtualFile("s1", "conv-DIFFERENT", "Renamed", AgentCli.CODEX)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val other = TranscriptVirtualFile("s2", "conv-1", "Chat #1", AgentCli.CLAUDE)
        assertNotEquals(a, other)
    }

    @Test
    fun `renaming the chat renames the tab, without disturbing identity`() {
        val file = TranscriptVirtualFile("s1", "conv-1", "Chat #1", AgentCli.CLAUDE)
        val identity = file.hashCode()

        file.chatName = "Diagnose missing transcript"

        assertEquals("Diagnose missing transcript ✓", file.name)
        assertEquals(identity, file.hashCode())
        assertTrue(TranscriptVirtualFile.matches(file, "s1"))
    }

    @Test
    fun `matches only its own session id`() {
        val file = TranscriptVirtualFile("s7", "conv", "Chat #7", AgentCli.CODEX)
        assertTrue(TranscriptVirtualFile.matches(file, "s7"))
        assertFalse(TranscriptVirtualFile.matches(file, "s8"))
    }
}
