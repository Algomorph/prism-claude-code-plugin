package com.github.vgirotto.prism.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileSnapshotTest {

    @Test
    fun `FileDiffEntry equality based on path and status`() {
        val e1 = FileDiffEntry("src/a.kt", ChangeStatus.MODIFIED, "old".toByteArray(), "new".toByteArray())
        val e2 = FileDiffEntry("src/a.kt", ChangeStatus.MODIFIED, "different".toByteArray(), "content".toByteArray())
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `FileDiffEntry inequality when status differs`() {
        val e1 = FileDiffEntry("src/a.kt", ChangeStatus.ADDED, null, "new".toByteArray())
        val e2 = FileDiffEntry("src/a.kt", ChangeStatus.MODIFIED, "old".toByteArray(), "new".toByteArray())
        assertNotEquals(e1, e2)
    }

    @Test
    fun `FileDiffEntry inequality when path differs`() {
        val e1 = FileDiffEntry("a.kt", ChangeStatus.MODIFIED, "x".toByteArray(), "y".toByteArray())
        val e2 = FileDiffEntry("b.kt", ChangeStatus.MODIFIED, "x".toByteArray(), "y".toByteArray())
        assertNotEquals(e1, e2)
    }

    @Test
    fun `InteractionDiff stores changes correctly`() {
        val changes = listOf(
            FileDiffEntry("a.kt", ChangeStatus.ADDED, null, "content".toByteArray()),
            FileDiffEntry("b.kt", ChangeStatus.MODIFIED, "old".toByteArray(), "new".toByteArray()),
            FileDiffEntry("c.kt", ChangeStatus.DELETED, "content".toByteArray(), null),
        )
        val diff = InteractionDiff(1, 1000L, changes)

        assertEquals(1, diff.interactionIndex)
        assertEquals(3, diff.changes.size)
        assertEquals(ChangeStatus.ADDED, diff.changes[0].status)
        assertEquals(ChangeStatus.MODIFIED, diff.changes[1].status)
        assertEquals(ChangeStatus.DELETED, diff.changes[2].status)
    }

    @Test
    fun `InteractionDiff empty`() {
        val diff = InteractionDiff(0, 1000L, emptyList())
        assertEquals(0, diff.interactionIndex)
        assertTrue(diff.changes.isEmpty())
    }

    @Test
    fun `legacy session name initializes participant names`() {
        val diff = InteractionDiff(1, 1000L, emptyList(), sessionName = "Chat #4")

        assertEquals(listOf("Chat #4"), diff.sessionNames)
    }

    @Test
    fun `ChangeStatus enum values`() {
        assertEquals(3, ChangeStatus.entries.size)
    }

    @Test
    fun `FileDiffEntry originalContent null for ADDED`() {
        val entry = FileDiffEntry("new.kt", ChangeStatus.ADDED, null, "content".toByteArray())
        assertNull(entry.originalContent)
        assertNotNull(entry.modifiedContent)
    }

    @Test
    fun `FileDiffEntry modifiedContent null for DELETED`() {
        val entry = FileDiffEntry("old.kt", ChangeStatus.DELETED, "content".toByteArray(), null)
        assertNotNull(entry.originalContent)
        assertNull(entry.modifiedContent)
    }
}
