package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobalInteractionCoordinatorTest {
    @Test
    fun `sequential chats create independent interaction groups`() {
        val coordinator = GlobalInteractionCoordinator()

        assertTrue(coordinator.begin("a", "Chat #1"))
        assertEquals(listOf("Chat #1"), coordinator.finish("a")?.sessionNames)
        assertTrue(coordinator.begin("b", "Chat #2"))
        assertEquals(listOf("Chat #2"), coordinator.finish("b")?.sessionNames)
    }

    @Test
    fun `overlapping chats share one baseline and one attribution`() {
        val coordinator = GlobalInteractionCoordinator()

        assertTrue(coordinator.begin("a", "Chat #1"))
        assertFalse(coordinator.begin("b", "Chat #2"))
        assertNull(coordinator.finish("a"))
        assertEquals(listOf("Chat #1", "Chat #2"), coordinator.finish("b")?.sessionNames)
    }

    @Test
    fun `overlap chain waits for every active chat`() {
        val coordinator = GlobalInteractionCoordinator()

        coordinator.begin("a", "Chat #1")
        coordinator.begin("b", "Chat #2")
        assertNull(coordinator.finish("a"))
        coordinator.begin("c", "Chat #3")
        assertNull(coordinator.finish("b"))
        assertEquals(
            listOf("Chat #1", "Chat #2", "Chat #3"),
            coordinator.finish("c")?.sessionNames,
        )
    }

    @Test
    fun `duplicate begin and finish do not create duplicate groups`() {
        val coordinator = GlobalInteractionCoordinator()

        assertTrue(coordinator.begin("a", "Chat #1"))
        assertFalse(coordinator.begin("a", "Chat #1"))
        assertEquals(listOf("Chat #1"), coordinator.finish("a")?.sessionNames)
        assertNull(coordinator.finish("a"))
    }

    @Test
    fun `a chat can rejoin while another overlapping chat is still active`() {
        val coordinator = GlobalInteractionCoordinator()
        coordinator.begin("a", "Chat #1")
        coordinator.begin("b", "Chat #2")
        assertNull(coordinator.finish("a"))

        assertFalse(coordinator.begin("a", "Chat #1"))
        assertNull(coordinator.finish("b"))
        assertEquals(
            listOf("Chat #1", "Chat #2"),
            coordinator.finish("a")?.sessionNames,
        )
    }

    @Test
    fun `reset clears an abandoned interaction`() {
        val coordinator = GlobalInteractionCoordinator()
        coordinator.begin("a", "Chat #1")

        coordinator.reset()

        assertFalse(coordinator.hasActiveInteraction())
        assertTrue(coordinator.begin("b", "Chat #2"))
    }
}
