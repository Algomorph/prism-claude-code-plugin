package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The refresh call is looked up at runtime because no single signature exists across the platform
 * range Prism supports, so these cover the dispatch: the newer public method wins, the older
 * impl-only one is the fallback, and a host offering neither degrades quietly.
 *
 * LightVirtualFile touches the file-type registry, so it needs an Application (no display).
 */
@TestApplication
class TranscriptTabTitleTest {

    private val file: VirtualFile get() = LightVirtualFile("transcript")

    /** A host with both calls — 2025.1+ / 2026.x, where `updateFileName` is the public API. */
    private class BothMethods {
        val calls = mutableListOf<String>()
        fun updateFileName(file: VirtualFile) { calls += "updateFileName:${file.name}" }
        fun updateFilePresentation(file: VirtualFile) { calls += "updateFilePresentation:${file.name}" }
    }

    /** A host with only the older impl-level call — 2024.3, the compile target. */
    private class LegacyOnly {
        val calls = mutableListOf<String>()
        fun updateFilePresentation(file: VirtualFile) { calls += "updateFilePresentation:${file.name}" }
    }

    private class NoRefresh {
        fun refreshIcons() = Unit
    }

    /** Same name, wrong shape — must not be mistaken for the refresh call. */
    private class WrongSignature {
        var called: String? = null
        fun updateFileName(name: String) { called = name }
    }

    private class Throwing {
        fun updateFileName(file: VirtualFile): Unit =
            throw UnsupportedOperationException("no longer supported for ${file.name}")
    }

    @Test
    fun `prefers the public method when the host has both`() {
        val manager = BothMethods()
        assertTrue(TranscriptTabTitle.refreshVia(manager, file))
        assertEquals(listOf("updateFileName:transcript"), manager.calls)
    }

    @Test
    fun `falls back to the older call on the platform Prism compiles against`() {
        val manager = LegacyOnly()
        assertTrue(TranscriptTabTitle.refreshVia(manager, file))
        assertEquals(listOf("updateFilePresentation:transcript"), manager.calls)
    }

    @Test
    fun `a host with no refresh call degrades quietly`() {
        assertFalse(TranscriptTabTitle.refreshVia(NoRefresh(), file))
    }

    @Test
    fun `a same-named method with another signature is not called`() {
        val manager = WrongSignature()
        assertFalse(TranscriptTabTitle.refreshVia(manager, file))
        assertNull(manager.called)
    }

    @Test
    fun `a refresh call that throws does not propagate`() {
        assertFalse(TranscriptTabTitle.refreshVia(Throwing(), file))
    }
}
