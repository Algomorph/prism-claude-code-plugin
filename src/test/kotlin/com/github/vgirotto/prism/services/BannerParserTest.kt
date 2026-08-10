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
    fun `Codex parser reads the effort from the model line`() {
        val banner = "│ model:     gpt-5.6-terra high   /model to change │"
        val parsed = CodexBannerParser.parse(banner)
        assertEquals("gpt-5.6-terra" to "high", parsed)
    }

    @Test
    fun `Codex parser withholds a result while the model is still loading`() {
        assertNull(CodexBannerParser.parse("│ model:     loading   /model to change │"))
        assertNull(CodexBannerParser.parse("│ model:     loading...   /model to change │"))
    }

    @Test
    fun `Codex parser skips the placeholder once the box repaints`() {
        // Both paints, as a buffer of the whole startup burst holds them. Escapes are
        // written as \u escapes to keep the source plain text (see BannerParser).
        val banner = "│ model:     loading   /model to change │\n" +
            "gpt-5.6-terra high · ~ · Context 100% left\n" +
            "\u001B[K\u001B[2m│ model:     \u001B[22mgpt-5.6-terra high\u001B[2m   \u001B[22m/model to change │"
        val parsed = CodexBannerParser.parse(banner)
        assertEquals("gpt-5.6-terra" to "high", parsed)
    }

    @Test
    fun `Codex parser waits out a model id cut off by a partial read`() {
        assertNull(CodexBannerParser.parse("│ model:     gpt"))
        assertEquals(
            "gpt-5.6-terra" to "high",
            CodexBannerParser.parse("│ model:     gpt-5.6-terra high   /model to change │"),
        )
    }

    @Test
    fun `Codex parser returns null for non-matching output`() {
        assertNull(CodexBannerParser.parse("Welcome to a different tool"))
    }
}
