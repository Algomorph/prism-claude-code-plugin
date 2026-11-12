package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.session.SessionIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How a restored chat's agent is launched on its recorded conversation. */
class ResumeArgumentsTest {

    private val conversation = SessionIdentity("0b7c9a3e-0000-4000-8000-000000000000", "/t.jsonl")
    private val id = conversation.sessionId

    @Test
    fun `nothing to resume adds nothing`() {
        assertNull(resumeArguments(AgentCli.CLAUDE, listOf("--model", "opus"), null))
        assertNull(resumeArguments(AgentCli.CODEX, listOf("--model", "o3"), null))
    }

    @Test
    fun `Claude resumes after the configured arguments`() {
        assertEquals(
            listOf("--model", "opus", "--resume", id),
            resumeArguments(AgentCli.CLAUDE, listOf("--model", "opus"), conversation),
        )
    }

    @Test
    fun `Codex resumes with the subcommand ahead of the configured arguments`() {
        assertEquals(
            listOf("resume", id, "-c", "model=\"o3\"", "fix the build"),
            resumeArguments(AgentCli.CODEX, listOf("-c", "model=\"o3\"", "fix the build"), conversation),
        )
    }

    @Test
    fun `configured Claude arguments that pick a conversation take precedence`() {
        for (configured in listOf(listOf("--continue"), listOf("-c"), listOf("--resume", "x"), listOf("--session-id=y"))) {
            assertNull(resumeArguments(AgentCli.CLAUDE, configured, conversation), configured.toString())
        }
    }

    @Test
    fun `configured Codex subcommands that pick a conversation take precedence`() {
        assertNull(resumeArguments(AgentCli.CODEX, listOf("resume", "--last"), conversation))
        assertNull(resumeArguments(AgentCli.CODEX, listOf("fork", "x"), conversation))
    }
}
