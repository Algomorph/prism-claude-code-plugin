package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ChangeStatus
import com.github.vgirotto.prism.model.InteractionDiff
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercises the production service with IDE lookup substituted and VFS changes reported directly. */
class FileSnapshotRefreshTest {

    @TempDir
    lateinit var projectDir: File

    private lateinit var service: FileSnapshotService
    private var previousApplication: Application? = null
    private val applicationField = ApplicationManager::class.java.getDeclaredField("ourApplication").apply {
        isAccessible = true
    }

    @BeforeEach
    fun setup() {
        previousApplication = ApplicationManager.getApplication()
        val settings = AgentSettingsState()
        val application = Proxy.newProxyInstance(
            Application::class.java.classLoader, arrayOf(Application::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getService" -> if (arguments?.firstOrNull() == AgentSettingsState::class.java) settings else null
                "isUnitTestMode" -> true
                "isDisposed", "isDispatchThread" -> false
                else -> null
            }
        }
        applicationField.set(null, application)

        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader, arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> projectDir.absolutePath
                "isDisposed" -> false
                else -> null
            }
        } as Project
        File(projectDir, "test.txt").writeText("before")
        service = FileSnapshotService(project)
        service.resetSnapshotIfNoActiveInteraction()
    }

    @AfterEach
    fun cleanup() {
        try {
            if (::service.isInitialized) service.dispose()
        } finally {
            applicationField.set(null, previousApplication)
        }
    }

    private fun finish(sessionId: String): InteractionDiff? {
        val result = CompletableFuture<InteractionDiff?>()
        service.finishInteractionAsync(sessionId) { result.complete(it) }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun completedInteraction(): InteractionDiff {
        service.beginInteraction("a", "Chat #1")
        File(projectDir, "test.txt").writeText("after")
        service.recordChange(File(projectDir, "test.txt").absolutePath)
        return requireNotNull(finish("a"))
    }

    @Test
    fun `repeated refresh preserves the recorded index attribution and history`() {
        val recorded = completedInteraction()
        assertEquals(1, recorded.interactionIndex)
        assertEquals(1, recorded.changes.size)

        repeat(3) {
            val refreshed = requireNotNull(service.refreshVfsAndComputeDiffIfIdle())
            assertEquals(recorded.interactionIndex, refreshed.interactionIndex)
            assertEquals(recorded.timestamp, refreshed.timestamp)
            assertEquals(listOf("Chat #1"), refreshed.sessionNames)
            assertArrayEquals("after".toByteArray(), refreshed.changes.single().modifiedContent)
            assertEquals(1, service.getDiffHistory().size)
            assertSame(recorded, service.getLatestDiff())
        }
    }

    @Test
    fun `manual edits refresh the view without rewriting completed history`() {
        val recorded = completedInteraction()
        File(projectDir, "test.txt").writeText("manual edit")

        val refreshed = requireNotNull(service.refreshVfsAndComputeDiffIfIdle())

        assertEquals(1, refreshed.interactionIndex)
        assertArrayEquals("manual edit".toByteArray(), refreshed.changes.single().modifiedContent)
        assertArrayEquals("after".toByteArray(), recorded.changes.single().modifiedContent)
        assertEquals(1, service.getDiffHistory().size)
    }

    @Test
    fun `refresh preserves deleted baseline files across consecutive refreshes`() {
        assertTrue(File(projectDir, "test.txt").delete())

        repeat(3) {
            val refreshed = requireNotNull(service.refreshVfsAndComputeDiffIfIdle())
            assertEquals(0, refreshed.interactionIndex)
            assertEquals(ChangeStatus.DELETED, refreshed.changes.single().status)
            assertArrayEquals("before".toByteArray(), refreshed.changes.single().originalContent)
            assertTrue(service.getDiffHistory().isEmpty())
        }
    }

    @Test
    fun `refresh shows an empty diff when disk returns to the baseline`() {
        completedInteraction()
        File(projectDir, "test.txt").writeText("before")

        val refreshed = requireNotNull(service.refreshVfsAndComputeDiffIfIdle())

        assertTrue(refreshed.changes.isEmpty())
        assertEquals(1, refreshed.interactionIndex)
        assertEquals(1, service.getDiffHistory().size)
    }

    @Test
    fun `refresh during overlap cannot publish a diff and completion records once`() {
        service.beginInteraction("a", "Chat #1")
        service.beginInteraction("b", "Chat #2")
        File(projectDir, "test.txt").writeText("overlap")

        assertNull(service.refreshVfsAndComputeDiffIfIdle())
        assertNull(finish("a"))
        assertNull(service.refreshVfsAndComputeDiffIfIdle())
        val recorded = requireNotNull(finish("b"))

        assertEquals(listOf("Chat #1", "Chat #2"), recorded.sessionNames)
        assertEquals(1, service.getDiffHistory().size)
        assertNull(finish("b"))
        service.refreshVfsAndComputeDiffIfIdle()
        assertEquals(1, service.getDiffHistory().size)
    }

    @Test
    fun `the next real interaction advances only once after repeated refresh`() {
        completedInteraction()
        repeat(3) { service.refreshVfsAndComputeDiffIfIdle() }

        service.beginInteraction("b", "Chat #2")
        File(projectDir, "test.txt").writeText("second interaction")
        val second = requireNotNull(finish("b"))

        assertEquals(2, second.interactionIndex)
        assertEquals(2, service.getDiffHistory().size)
        assertArrayEquals("after".toByteArray(), second.changes.single().originalContent)
    }
}
