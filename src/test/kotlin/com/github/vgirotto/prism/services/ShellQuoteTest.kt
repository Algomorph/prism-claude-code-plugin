package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * [shellQuote] guards the resolved CLI path typed into the session's login shell, which
 * — unlike the bare binary name used before — can contain spaces.
 */
class ShellQuoteTest {

    @Test
    fun `plain path is quoted as a single word`() {
        assertEquals("'/home/user/.local/bin/codex'", shellQuote("/home/user/.local/bin/codex"))
    }

    @Test
    fun `path with spaces stays one word`() {
        assertEquals("'/Users/Some User/bin/codex'", shellQuote("/Users/Some User/bin/codex"))
    }

    @Test
    fun `embedded single quote is escaped`() {
        assertEquals("'/home/it'\\''s/codex'", shellQuote("/home/it's/codex"))
    }

    @Test
    fun `shell metacharacters are not interpreted`() {
        assertEquals("'/opt/a b;rm -rf x/codex'", shellQuote("/opt/a b;rm -rf x/codex"))
    }

    @Test
    fun `command quotes every configured argument independently`() {
        assertEquals(
            "'/opt/Claude Code/claude' '--plugin-dir' '/tmp/my plugin' ';rm'",
            shellCommand(
                ResolvedCliCommand(
                    "/opt/Claude Code/claude",
                    listOf("--plugin-dir", "/tmp/my plugin", ";rm"),
                ),
            ),
        )
    }
}
