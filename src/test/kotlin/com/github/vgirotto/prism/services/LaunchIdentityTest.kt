package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.services.session.ClaudeSessionStrategy
import com.github.vgirotto.prism.services.session.CodexSessionStrategy
import com.github.vgirotto.prism.services.session.SessionIdentity
import com.github.vgirotto.prism.services.session.TabSessionFiles
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The `--session-id` a Claude tab is launched with is its identity only while the hook follows switches. */
class LaunchIdentityTest {

    @TempDir lateinit var dir: Path

    @AfterEach
    fun clearCodexVersions() = CodexSessionStrategy.clearCacheForTests()

    private val tab by lazy { TabSessionFiles(dir.resolve("tab")).create() }

    private fun claude(vararg args: String) = ResolvedCliCommand("/bin/claude", args.toList())

    @Test
    fun `with Prism's hook the launch id is the provisional identity`() {
        val strategy = ClaudeSessionStrategy()
        strategy.launchCommand(tab, claude("--session-id", "s1"))
        assertEquals(SessionIdentity("s1", null), launchIdentity("s1", passesSessionId = true, strategy))
    }

    @Test
    fun `with the user's own settings the identity stays unknown`() {
        for (command in listOf(claude("--settings", "/x.json"), claude("--settings={}"))) {
            val strategy = ClaudeSessionStrategy()
            strategy.launchCommand(tab, command)
            assertNull(launchIdentity("s1", passesSessionId = true, strategy), command.arguments.toString())
        }
    }

    @Test
    fun `a strategy reused for a launch without the hook forgets the hook`() {
        val strategy = ClaudeSessionStrategy()
        strategy.launchCommand(tab, claude())
        strategy.launchCommand(tab, claude("--settings", "/x.json"))
        assertNull(launchIdentity("s1", passesSessionId = true, strategy))
    }

    @Test
    fun `no session id flag, or Codex, gives no launch identity`() {
        val claude = ClaudeSessionStrategy().apply { launchCommand(tab, claude()) }
        assertNull(launchIdentity("s1", passesSessionId = false, claude))
        assertNull(launchIdentity("s1", passesSessionId = true, CodexSessionStrategy(versionOf = { null })))
    }

    private val recorded = SessionIdentity("s9", "/projects/s9.jsonl")

    @Test
    fun `a resumed Claude chat starts on its recorded conversation while the hook is installed`() {
        val strategy = ClaudeSessionStrategy().apply { launchCommand(tab, claude("--resume", "s9")) }
        assertEquals(recorded, resumedIdentity(recorded, strategy))
    }

    @Test
    fun `a resumed Claude chat with the user's own settings stays unknown`() {
        val strategy = ClaudeSessionStrategy().apply { launchCommand(tab, claude("--resume", "s9", "--settings", "/x.json")) }
        assertNull(resumedIdentity(recorded, strategy))
    }

    @Test
    fun `a resumed Codex chat starts on its recorded thread only with Prism's title items`() {
        fun launched(version: String, vararg args: String) = CodexSessionStrategy(versionOf = { version })
            .apply { launchCommand(tab, ResolvedCliCommand("/codex-$version", args.toList())) }

        assertEquals(recorded, resumedIdentity(recorded, launched("0.160.0", "resume", "s9")))
        assertNull(resumedIdentity(recorded, launched("0.160.0", "resume", "s9", "-c", "tui.terminal_title=[]")))
        assertNull(resumedIdentity(recorded, launched("0.150.0", "resume", "s9")))
    }

    @Test
    fun `nothing to resume gives no resumed identity`() {
        val strategy = ClaudeSessionStrategy().apply { launchCommand(tab, claude()) }
        assertNull(resumedIdentity(null, strategy))
    }
}
