package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentSettingsStateTest {

    @Test
    fun `default state has expected values`() {
        val state = AgentSettingsState.State()

        assertEquals("claude", state.claudePath)
        assertEquals("codex", state.codexPath)
        assertEquals(AgentCli.DEFAULT, state.defaultCli)
        assertTrue(state.autoStartOnOpen)
        assertNotNull(state.shellPath)
        assertTrue(state.showChangesOnStartup)
        assertTrue(state.showStatusBarWidget)
        assertTrue(state.excludedPatterns.contains(".git"))
        assertEquals(512, state.maxFileSizeKb)
    }

    @Test
    fun `state properties are mutable`() {
        val settings = AgentSettingsState()

        settings.loadState(AgentSettingsState.State(
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
        val settings = AgentSettingsState()
        settings.loadState(AgentSettingsState.State(claudePath = "custom-claude"))

        val state = settings.state
        assertEquals("custom-claude", state.claudePath)
    }

    @Test
    fun `setting individual properties updates state`() {
        val settings = AgentSettingsState()

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
        val settings = AgentSettingsState()
        settings.claudePath = "old"

        settings.loadState(AgentSettingsState.State(claudePath = "new"))

        assertEquals("new", settings.claudePath)
    }

    @Test
    fun `getExcludedDirSet parses comma-separated patterns`() {
        val settings = AgentSettingsState()
        settings.excludedPatterns = ".git, node_modules , build"

        val dirs = settings.getExcludedDirSet()
        assertEquals(setOf(".git", "node_modules", "build"), dirs)
    }

    @Test
    fun `getExcludedDirSet handles empty string`() {
        val settings = AgentSettingsState()
        settings.excludedPatterns = ""

        assertTrue(settings.getExcludedDirSet().isEmpty())
    }

    @Test
    fun `codexPath defaults to codex and is mutable`() {
        val settings = AgentSettingsState()
        assertEquals("codex", settings.codexPath)
        settings.codexPath = "/opt/codex"
        assertEquals("/opt/codex", settings.state.codexPath)
    }

    @Test
    fun `defaultCli defaults to AgentCli DEFAULT and is mutable`() {
        val settings = AgentSettingsState()
        assertEquals(AgentCli.DEFAULT, settings.defaultCli)
        settings.defaultCli = AgentCli.CODEX
        assertEquals(AgentCli.CODEX, settings.state.defaultCli)
    }

    @Test
    fun `cliPath returns the per-CLI executable path`() {
        val settings = AgentSettingsState()
        settings.claudePath = "/opt/claude"
        settings.codexPath = "/opt/codex"
        assertEquals("/opt/claude", settings.cliPath(AgentCli.CLAUDE))
        assertEquals("/opt/codex", settings.cliPath(AgentCli.CODEX))
    }
}
