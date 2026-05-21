package com.github.vgirotto.prism.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentCliTest {

    @Test
    fun `DEFAULT is CLAUDE`() {
        assertEquals(AgentCli.CLAUDE, AgentCli.DEFAULT)
    }

    @Test
    fun `enum has CLAUDE and CODEX values`() {
        val names = AgentCli.values().map { it.name }.toSet()
        assertEquals(setOf("CLAUDE", "CODEX"), names)
    }

    @Test
    fun `valueOf round-trips`() {
        for (cli in AgentCli.values()) {
            assertEquals(cli, AgentCli.valueOf(cli.name))
        }
    }

    @Test
    fun `ClaudeSession defaults cli to CLAUDE`() {
        val session = ClaudeSession()
        assertEquals(AgentCli.CLAUDE, session.cli)
    }

    @Test
    fun `ClaudeSession accepts explicit cli`() {
        val codex = ClaudeSession(cli = AgentCli.CODEX)
        assertEquals(AgentCli.CODEX, codex.cli)
    }
}
