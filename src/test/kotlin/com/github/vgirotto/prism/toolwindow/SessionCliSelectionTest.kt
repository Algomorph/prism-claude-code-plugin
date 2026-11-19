package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.ResolvedCliCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionCliSelectionTest {
    private val claude = ResolvedCliCommand("/custom/claude", listOf("--model", "opus"))
    private val codex = ResolvedCliCommand("/custom/codex", listOf("--profile", "work"))

    @Test
    fun `automatic launch falls back to the only installed agent for either default`() {
        for ((preferred, installed, command) in listOf(
            Triple(AgentCli.CLAUDE, AgentCli.CODEX, codex),
            Triple(AgentCli.CODEX, AgentCli.CLAUDE, claude),
        )) {
            val result = resolveSessionCli(null, preferred) { if (it == installed) command else null }
            assertEquals(installed, result.first)
            assertSame(command, result.second)
        }
    }

    @Test
    fun `automatic launch keeps an available default without resolving alternatives`() {
        for ((preferred, command) in listOf(AgentCli.CLAUDE to claude, AgentCli.CODEX to codex)) {
            val attempts = mutableListOf<AgentCli>()
            val result = resolveSessionCli(null, preferred) {
                attempts += it
                if (it == AgentCli.CLAUDE) claude else codex
            }
            assertEquals(preferred to command, result)
            assertEquals(listOf(preferred), attempts)
        }
    }

    @Test
    fun `no installed agents keeps the default for its error message`() {
        for (preferred in AgentCli.values()) {
            val attempts = mutableListOf<AgentCli>()
            assertEquals(preferred to null, resolveSessionCli(null, preferred) { attempts += it; null })
            assertEquals(2, attempts.size)
            assertEquals(preferred, attempts.first())
        }
    }

    @Test
    fun `explicit unavailable selection never launches another agent`() {
        val attempts = mutableListOf<AgentCli>()
        val result = resolveSessionCli(AgentCli.CLAUDE, AgentCli.CODEX) {
            attempts += it
            if (it == AgentCli.CODEX) codex else null
        }
        assertEquals(AgentCli.CLAUDE to null, result)
        assertEquals(listOf(AgentCli.CLAUDE), attempts)
    }

    @Test
    fun `explicit choice overrides default and retains configured command arguments`() {
        val result = resolveSessionCli(AgentCli.CODEX, AgentCli.CLAUDE) {
            if (it == AgentCli.CODEX) codex else claude
        }
        assertEquals(AgentCli.CODEX, result.first)
        assertSame(codex, result.second)
    }
}
