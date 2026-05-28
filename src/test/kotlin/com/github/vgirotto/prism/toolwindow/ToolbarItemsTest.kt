package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolbarItemsTest {

    @Test
    fun `Claude exposes every toolbar item`() {
        val items = toolbarItemsFor(AgentCli.CLAUDE)
        assertEquals(ToolbarItem.values().toList(), items)
    }

    @Test
    fun `Codex omits Claude-specific items`() {
        val items = toolbarItemsFor(AgentCli.CODEX)
        for (claudeOnly in listOf(
            ToolbarItem.RESUME,
            ToolbarItem.COMPACT,
            ToolbarItem.CLEAR,
            ToolbarItem.MODEL,
            ToolbarItem.EFFORT,
            // /cost is a Claude-only slash command; Codex sessions shouldn't see it
            // since clicking it would send /cost into the Codex PTY.
            ToolbarItem.COST,
        )) {
            assertFalse(claudeOnly in items, "Codex should not expose $claudeOnly")
        }
    }

    @Test
    fun `Codex still exposes universal items`() {
        val items = toolbarItemsFor(AgentCli.CODEX)
        assertTrue(ToolbarItem.TEMPLATES in items)
        assertTrue(ToolbarItem.SETTINGS in items)
    }

    @Test
    fun `isToolbarItemAvailable agrees with toolbarItemsFor`() {
        for (cli in AgentCli.values()) {
            val expected = toolbarItemsFor(cli).toSet()
            for (item in ToolbarItem.values()) {
                assertEquals(item in expected, isToolbarItemAvailable(cli, item))
            }
        }
    }

    @Test
    fun `toolbarItemsFor returns items in enum declaration order`() {
        val codex = toolbarItemsFor(AgentCli.CODEX)
        val expected = ToolbarItem.values().filter { it in codex.toSet() }
        assertEquals(expected, codex)
    }
}
