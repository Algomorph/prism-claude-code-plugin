package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.ResolvedCliCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentSessionStrategyTest {

    @TempDir lateinit var dir: Path

    private val tab by lazy { TabSessionFiles(dir.resolve("tab")).create() }

    @AfterEach
    fun clearCache() = CodexSessionStrategy.clearCacheForTests()

    private fun claude(vararg args: String) = ResolvedCliCommand("/bin/claude", args.toList())
    private fun codex(vararg args: String) = ResolvedCliCommand("/bin/codex", args.toList())

    @Test
    fun `Claude gets the session hook in its settings`() {
        val args = ClaudeSessionStrategy().launchArguments(tab, claude())
        assertEquals(2, args.size)
        assertEquals("--settings", args[0])
        assertEquals(ClaudeSessionHook.settingsJson(tab.claudeEvents), args[1])
    }

    @Test
    fun `a user's own settings flag is left alone`() {
        assertEquals(emptyList<String>(), ClaudeSessionStrategy().launchArguments(tab, claude("--settings", "/x.json")))
        assertEquals(emptyList<String>(), ClaudeSessionStrategy().launchArguments(tab, claude("--settings=/x.json")))
    }

    @Test
    fun `Claude's title is not disabled and nested-session markers are dropped`() {
        val env = ClaudeSessionStrategy().launchEnvironment()
        for (name in listOf("CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "CLAUDECODE", "CLAUDE_CODE_CHILD_SESSION")) {
            assertTrue(env.containsKey(name))
            assertNull(env[name])
        }
    }

    @Test
    fun `Claude's hook events are read from the tab's own file`() {
        tab.claudeEvents.toFile().writeText("""{"session_id":"s1","source":"startup"}""" + "\n")
        assertEquals("s1", ClaudeSessionStrategy().identityEvents(tab).poll()?.sessionId)
    }

    @Test
    fun `Codex from the supported version on gets the title items`() {
        val strategy = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { "0.159.0" })
        assertEquals(listOf("-c", CodexSessionStrategy.TITLE_OVERRIDE), strategy.launchArguments(tab, codex()))
    }

    @Test
    fun `older or unknown Codex versions launch as configured`() {
        val old = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { "0.158.9" })
        assertEquals(emptyList<String>(), old.launchArguments(tab, codex()))
        CodexSessionStrategy.clearCacheForTests()
        val unknown = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { null })
        assertEquals(emptyList<String>(), unknown.launchArguments(tab, codex()))
    }

    @Test
    fun `the version is probed once per executable`() {
        var probes = 0
        val strategy = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { probes++; "0.160.0" })
        strategy.launchArguments(tab, codex())
        strategy.launchArguments(tab, codex())
        assertEquals(1, probes)
    }

    @Test
    fun `a user's own title items are left alone`() {
        assertTrue(CodexSessionStrategy.setsTerminalTitle(listOf("-c", "tui.terminal_title=[\"model\"]")))
        assertTrue(CodexSessionStrategy.setsTerminalTitle(listOf("--config", "tui.terminal_title=[]")))
        assertTrue(CodexSessionStrategy.setsTerminalTitle(listOf("--config=tui.terminal_title=[]")))
        assertTrue(CodexSessionStrategy.setsTerminalTitle(listOf("-ctui.terminal_title=[]")))
        assertFalse(CodexSessionStrategy.setsTerminalTitle(listOf("-c", "model=\"o3\"")))
        assertFalse(CodexSessionStrategy.setsTerminalTitle(listOf("tui.terminal_title")))

        val strategy = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { "0.159.0" })
        assertEquals(emptyList<String>(), strategy.launchArguments(tab, codex("-c", "tui.terminal_title=[]")))
    }

    @Test
    fun `Codex titles are read only after a launch with Prism's title items`() {
        val title = "01a0edbb-4501-7591-82b7-36c4c... | gpt-5.5"
        val skipped = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { "0.159.0" })
        skipped.launchArguments(tab, codex("-c", "tui.terminal_title=[\"thread-id\",\"model\"]"))
        assertNull(skipped.parseTitle(title))

        val tracked = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { "0.159.0" })
        assertNull(tracked.parseTitle(title)) // Not launched yet.
        tracked.launchArguments(tab, codex())
        assertEquals("gpt-5.5", (tracked.parseTitle(title) as TitleReading.Named).name)
    }

    @Test
    fun `Codex's full name is taken only when Codex would show it as the title does`() {
        val long = "Investigate  the flaky\tintegration tests in the payments service"
        dir.resolve(CodexSessionStore.INDEX_FILE).toFile()
            .writeText("""{"id":"t1","thread_name":"${long.replace("\t", "\\t")}"}""" + "\n")
        val strategy = CodexSessionStrategy(CodexSessionStore(dir), versionOf = { null })
        val identity = SessionIdentity("t1", null)

        assertEquals(
            "Investigate the flaky integration tests in the payments service",
            strategy.fullName(identity, "Investigate the flaky integration tests in t..."),
        )
        // The index still holds the previous name, or the title shows a different one.
        assertNull(strategy.fullName(identity, "Investigate the flaky integration tests in th..."))
        assertNull(strategy.fullName(SessionIdentity("t2", null), "Anything..."))
    }

    @Test
    fun `a tab's files live in their own directory and go with it`() {
        val files = TabSessionFiles.under(dir.toString(), "session-1").create()
        assertEquals(dir.resolve("prism-sessions/session-1"), files.dir)
        assertTrue(files.dir.toFile().isDirectory)
        files.claudeEvents.toFile().writeText("x")
        files.delete()
        assertFalse(files.dir.toFile().exists())
    }
}
