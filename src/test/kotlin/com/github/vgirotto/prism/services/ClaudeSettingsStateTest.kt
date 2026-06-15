package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClaudeSettingsStateTest {

    @Test
    fun `default state has expected values`() {
        val state = ClaudeSettingsState.State()

        assertEquals("claude", state.claudePath)
        assertTrue(state.autoStartOnOpen)
        assertNotNull(state.shellPath)
        assertTrue(state.showChangesOnStartup)
        assertTrue(state.showStatusBarWidget)
        assertTrue(state.excludedPatterns.contains(".git"))
        assertEquals(512, state.maxFileSizeKb)
    }

    @Test
    fun `state properties are mutable`() {
        val settings = ClaudeSettingsState()

        settings.loadState(ClaudeSettingsState.State(
            claudePath = "/usr/local/bin/claude",
            autoStartOnOpen = false,
            shellPath = "/bin/bash"
        ))

        assertEquals("/usr/local/bin/claude", settings.claudePath)
        assertFalse(settings.autoStartOnOpen)
        assertEquals("/bin/bash", settings.shellPath)
    }

    @Test
    fun `getState returns current state`() {
        val settings = ClaudeSettingsState()
        settings.loadState(ClaudeSettingsState.State(claudePath = "custom-claude"))

        val state = settings.state
        assertEquals("custom-claude", state.claudePath)
    }

    @Test
    fun `setting individual properties updates state`() {
        val settings = ClaudeSettingsState()

        settings.claudePath = "/opt/claude"
        settings.autoStartOnOpen = false
        settings.shellPath = "/bin/fish"
        settings.showChangesOnStartup = false
        settings.showStatusBarWidget = false
        settings.excludedPatterns = ".git,build"
        settings.maxFileSizeKb = 1024

        assertEquals("/opt/claude", settings.state.claudePath)
        assertFalse(settings.state.autoStartOnOpen)
        assertEquals("/bin/fish", settings.state.shellPath)
        assertFalse(settings.state.showChangesOnStartup)
        assertFalse(settings.state.showStatusBarWidget)
        assertEquals(".git,build", settings.state.excludedPatterns)
        assertEquals(1024, settings.state.maxFileSizeKb)
    }

    @Test
    fun `loadState replaces entire state`() {
        val settings = ClaudeSettingsState()
        settings.claudePath = "old"

        settings.loadState(ClaudeSettingsState.State(claudePath = "new"))

        assertEquals("new", settings.claudePath)
    }

}
