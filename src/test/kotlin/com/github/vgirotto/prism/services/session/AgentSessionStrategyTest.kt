package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.ResolvedCliCommand
import com.github.vgirotto.prism.services.shellCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class AgentSessionStrategyTest {

    @TempDir lateinit var dir: Path

    private val tab by lazy { TabSessionFiles(dir.resolve("tab")).create() }

    @AfterEach
    fun clearCache() = CodexSessionStrategy.clearCacheForTests()

    private fun claude(vararg args: String) = ResolvedCliCommand("/bin/claude", args.toList())
    private fun codex(vararg args: String) = ResolvedCliCommand("/bin/codex", args.toList())

    private fun codexStrategy(version: String? = "0.159.0", store: CodexSessionStore? = CodexSessionStore(dir)) =
        CodexSessionStrategy(versionOf = { version }, fixedStore = store)

    // ── Claude ──

    @Test
    fun `Claude gets the session hook in its settings, after the user's arguments`() {
        val launch = ClaudeSessionStrategy().launchCommand(tab, claude("--model", "opus"))
        assertEquals("/bin/claude", launch.executable)
        assertEquals(
            listOf("--model", "opus", "--settings", ClaudeSessionHook.settingsJson(tab.claudeEvents)),
            launch.arguments,
        )
    }

    @Test
    fun `a user's own settings flag is left alone`() {
        for (command in listOf(claude("--settings", "/x.json"), claude("--settings=/x.json"))) {
            assertEquals(command, ClaudeSessionStrategy().launchCommand(tab, command))
        }
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

    // ── Codex ──

    @Test
    fun `Codex from the supported version on gets the title items, through the home report`() {
        val launch = codexStrategy().launchCommand(tab, codex("--model", "o3"))
        assertEquals("/bin/sh", launch.executable)
        assertEquals(
            listOf(
                "-c", CodexSessionStrategy.HOME_REPORT, "sh", tab.codexHome.toString(),
                "/bin/codex", "--model", "o3", "-c", CodexSessionStrategy.TITLE_OVERRIDE,
            ),
            launch.arguments,
        )
    }

    @Test
    fun `older or unknown Codex versions launch as configured`() {
        assertEquals(codex(), codexStrategy(version = "0.158.9").launchCommand(tab, codex()))
        CodexSessionStrategy.clearCacheForTests()
        assertEquals(codex(), codexStrategy(version = null).launchCommand(tab, codex()))
    }

    @Test
    fun `the version is probed once per executable`() {
        var probes = 0
        val strategy = CodexSessionStrategy(versionOf = { probes++; "0.160.0" })
        strategy.launchCommand(tab, codex())
        strategy.launchCommand(tab, codex())
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

        val own = codex("-c", "tui.terminal_title=[]")
        assertEquals(own, codexStrategy().launchCommand(tab, own))
    }

    @Test
    fun `Codex titles are read only after a launch with Prism's title items`() {
        val title = "01a0edbb-4501-7591-82b7-36c4c... | gpt-5.5"
        val skipped = codexStrategy()
        skipped.launchCommand(tab, codex("-c", "tui.terminal_title=[\"thread-id\",\"model\"]"))
        assertNull(skipped.parseTitle(title))

        val tracked = codexStrategy()
        assertNull(tracked.parseTitle(title)) // Not launched yet.
        tracked.launchCommand(tab, codex())
        assertEquals("gpt-5.5", (tracked.parseTitle(title) as TitleReading.Named).name)
    }

    @Test
    fun `Codex's full name is taken only when Codex would show it as the title does`() {
        val long = "Investigate  the flaky\tintegration tests in the payments service"
        dir.resolve(CodexSessionStore.INDEX_FILE).toFile()
            .writeText("""{"id":"t1","thread_name":"${long.replace("\t", "\\t")}"}""" + "\n")
        val strategy = codexStrategy(version = null)
        val identity = SessionIdentity("t1", null)

        assertEquals(
            "Investigate the flaky integration tests in the payments service",
            strategy.fullName(identity, "Investigate the flaky integration tests in t..."),
        )
        // The index still holds the previous name, or the title shows a different one.
        assertNull(strategy.fullName(identity, "Investigate the flaky integration tests in th..."))
        assertNull(strategy.fullName(SessionIdentity("t2", null), "Anything..."))
    }

    /** Types [launch] into `sh` in [workDir] with [env] changes, as the tab's shell would run it. */
    private fun runTyped(launch: ResolvedCliCommand, workDir: Path, env: Map<String, String?>) {
        val process = ProcessBuilder("sh", "-c", shellCommand(launch)).directory(workDir.toFile()).apply {
            for ((name, value) in env) if (value == null) environment().remove(name) else environment()[name] = value
        }.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
    }

    @Test
    fun `the shell reports the CODEX_HOME Codex sees and then runs Codex with its arguments`() {
        // A stand-in for Codex that records the variable it inherited and its arguments.
        val fake = dir.resolve("it's codex").toFile().apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$CODEX_HOME\" \"\$@\" > \"\$(dirname \"\$0\")/ran\"\n")
            setExecutable(true)
        }
        val work = dir.resolve("work").also { it.toFile().mkdirs() }
        val custom = work.resolve("custom home").also { it.toFile().mkdirs() }

        val strategy = CodexSessionStrategy(versionOf = { "0.159.0" })
        val launch = strategy.launchCommand(tab, ResolvedCliCommand(fake.path, listOf("--model", "o 3")))
        runTyped(launch, work, mapOf("CODEX_HOME" to "custom home"))

        assertEquals("$custom\n", tab.codexHome.toFile().readText())
        assertEquals(
            listOf("custom home", "--model", "o 3", "-c", CodexSessionStrategy.TITLE_OVERRIDE),
            dir.resolve("ran").toFile().readLines(),
        )

        // The store is the one at the reported home.
        custom.resolve(CodexSessionStore.INDEX_FILE).toFile()
            .writeText("""{"id":"t1","thread_name":"Found in the custom home"}""" + "\n")
        assertEquals("Found in the custom home", strategy.fullName(SessionIdentity("t1", null), "Found in the custom home"))
    }

    @Test
    fun `without CODEX_HOME the shell reports the home directory's codex folder`() {
        val home = dir.resolve("home")
        val strategy = codexStrategy(store = null)
        val launch = strategy.launchCommand(tab, ResolvedCliCommand("/bin/true", emptyList()))
        runTyped(launch, dir, mapOf("CODEX_HOME" to "", "HOME" to home.toString()))
        assertEquals("${home.resolve(".codex")}\n", tab.codexHome.toFile().readText())
    }

    @Test
    fun `nothing is looked up before the shell has reported the home`() {
        val strategy = codexStrategy(store = null)
        strategy.launchCommand(tab, codex())
        assertNull(strategy.resolveIdentity(IdHint("01a0edbb-4501", isPrefix = true)))
        tab.codexHome.toFile().writeText(dir.toString()) // Not finished: no newline yet.
        assertNull(strategy.fullName(SessionIdentity("t1", null), "x"))
    }

    // ── Tab files ──

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
