package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.AgentCli
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BannerParserTest {

    @Test
    fun `forCli returns the matching parser implementation`() {
        assertSame(ClaudeBannerParser, BannerParser.forCli(AgentCli.CLAUDE))
        assertSame(CodexBannerParser, BannerParser.forCli(AgentCli.CODEX))
    }

    @Test
    fun `Claude parser extracts model and effort from welcome banner`() {
        val banner = "Welcome to Claude Code\nSonnet 4.6 with medium effort\n"
        val parsed = ClaudeBannerParser.parse(banner)
        assertEquals("sonnet" to "medium", parsed)
    }

    @Test
    fun `Claude parser tolerates ANSI escapes around model name`() {
        val banner = "[1mOpus 4.6[0m with high effort"
        val parsed = ClaudeBannerParser.parse(banner)
        assertEquals("opus" to "high", parsed)
    }

    @Test
    fun `Claude parser handles model-only banner without effort`() {
        val banner = "Haiku 4.5 ready"
        val parsed = ClaudeBannerParser.parse(banner)
        assertEquals("haiku" to "", parsed)
    }

    @Test
    fun `Claude parser returns null when nothing matches`() {
        assertNull(ClaudeBannerParser.parse("some unrelated output"))
    }

    @Test
    fun `Codex parser extracts model and reasoning effort from config block`() {
        val banner = """
            >_ OpenAI Codex
            model: gpt-5-codex
            reasoning effort: medium
        """.trimIndent()
        val parsed = CodexBannerParser.parse(banner)
        assertEquals("gpt-5-codex" to "medium", parsed)
    }

    @Test
    fun `Codex parser handles missing reasoning effort line`() {
        val banner = "model: gpt-5-codex\nstatus: ready"
        val parsed = CodexBannerParser.parse(banner)
        assertEquals("gpt-5-codex" to "", parsed)
    }

    @Test
    fun `Codex parser returns null for non-matching output`() {
        assertNull(CodexBannerParser.parse("Welcome to a different tool"))
    }
}
