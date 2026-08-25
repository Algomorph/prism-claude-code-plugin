package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliCommandParserTest {

    @Test
    fun `parses arguments with quoted spaces`() {
        assertEquals(
            CliCommandParser.Result.Success(
                "claude",
                listOf("--plugin-dir", "/Users/Some User/my plugin"),
            ),
            CliCommandParser.parse("claude --plugin-dir \"/Users/Some User/my plugin\"", "claude"),
        )
    }

    @Test
    fun `uses the default executable for an empty setting`() {
        assertEquals(
            CliCommandParser.Result.Success("claude", emptyList()),
            CliCommandParser.parse("  ", "claude"),
        )
    }

    @Test
    fun `rejects an unclosed quote`() {
        assertTrue(
            CliCommandParser.parse("claude --plugin-dir '/tmp/local", "claude") is CliCommandParser.Result.Invalid,
        )
    }
}
