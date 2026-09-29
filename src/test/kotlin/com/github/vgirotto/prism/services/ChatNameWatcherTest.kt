package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The never-downgrade rule, exercised through [ChatNameWatcher.pollOnce] — which returns the new
 * name only when the tab should actually be renamed, and needs no IDE Application to run.
 */
class ChatNameWatcherTest {

    /** A source whose next answer the test dictates. */
    private class Scripted(var next: ChatName?) : ChatNameSource {
        override fun poll(): ChatName? = next
    }

    private fun name(text: String, origin: ChatName.Origin) = ChatName(text, origin)

    @Test
    fun `the first resolved name is reported`() {
        val source = Scripted(name("Review the branch", ChatName.Origin.FIRST_MESSAGE))
        val watcher = ChatNameWatcher(source)

        assertEquals(name("Review the branch", ChatName.Origin.FIRST_MESSAGE), watcher.pollOnce())
        assertEquals("Review the branch", watcher.current?.text)
    }

    @Test
    fun `an unchanged name is not reported again`() {
        val source = Scripted(name("Review the branch", ChatName.Origin.AGENT_TITLE))
        val watcher = ChatNameWatcher(source)

        watcher.pollOnce()
        assertNull(watcher.pollOnce())
    }

    @Test
    fun `no name yet is not a change`() {
        val watcher = ChatNameWatcher(Scripted(null))
        assertNull(watcher.pollOnce())
        assertNull(watcher.current)
    }

    @Test
    fun `a generated title replaces the first-message stand-in`() {
        val source = Scripted(name("Establish the bug", ChatName.Origin.FIRST_MESSAGE))
        val watcher = ChatNameWatcher(source)
        watcher.pollOnce()

        source.next = name("Diagnose missing transcript", ChatName.Origin.AGENT_TITLE)
        assertEquals("Diagnose missing transcript", watcher.pollOnce()?.text)
    }

    @Test
    fun `a generated title never wins back a chat the user named`() {
        // Claude keeps writing ai-title records after a rename; the tab must not bounce.
        val source = Scripted(name("font-settings-menu", ChatName.Origin.USER_TITLE))
        val watcher = ChatNameWatcher(source)
        watcher.pollOnce()

        source.next = name("Terminal font configuration", ChatName.Origin.AGENT_TITLE)
        assertNull(watcher.pollOnce())
        assertEquals("font-settings-menu", watcher.current?.text)
    }

    @Test
    fun `a title that scrolled out of the tail window does not demote the tab`() {
        val source = Scripted(name("Diagnose missing transcript", ChatName.Origin.AGENT_TITLE))
        val watcher = ChatNameWatcher(source)
        watcher.pollOnce()

        // A busy turn pushed the title records past the bounded tail; only the first message is
        // still visible. The held name stands.
        source.next = name("Establish the bug", ChatName.Origin.FIRST_MESSAGE)
        assertNull(watcher.pollOnce())
        assertEquals("Diagnose missing transcript", watcher.current?.text)
    }

    @Test
    fun `a retitle at the same authority is applied`() {
        val source = Scripted(name("First idea", ChatName.Origin.USER_TITLE))
        val watcher = ChatNameWatcher(source)
        watcher.pollOnce()

        source.next = name("Second idea", ChatName.Origin.USER_TITLE)
        assertEquals("Second idea", watcher.pollOnce()?.text)
    }

    @Test
    fun `a disposed watcher stops resolving`() {
        val watcher = ChatNameWatcher(Scripted(name("Anything", ChatName.Origin.USER_TITLE)))
        watcher.dispose()
        assertNull(watcher.pollOnce())
    }
}
