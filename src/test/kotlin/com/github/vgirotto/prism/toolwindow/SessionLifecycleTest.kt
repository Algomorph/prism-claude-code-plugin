package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionLifecycleTest {
    @Test
    fun `attaches exactly once while creating`() {
        val lifecycle = SessionLifecycle()

        assertTrue(lifecycle.attach("session-a"))
        assertFalse(lifecycle.attach("session-b"))
        assertTrue(lifecycle.isAttached("session-a"))
        assertEquals(SessionLifecycle.State.ATTACHED, lifecycle.state())
    }

    @Test
    fun `dispose before attach rejects late session`() {
        val lifecycle = SessionLifecycle()

        assertNull(lifecycle.dispose())
        assertFalse(lifecycle.attach("late-session"))
        assertEquals(SessionLifecycle.State.DISPOSED, lifecycle.state())
    }

    @Test
    fun `dispose returns attached session only once`() {
        val lifecycle = SessionLifecycle()
        lifecycle.attach("session-a")

        assertEquals("session-a", lifecycle.dispose())
        assertNull(lifecycle.dispose())
        assertFalse(lifecycle.isAttached("session-a"))
    }
}
