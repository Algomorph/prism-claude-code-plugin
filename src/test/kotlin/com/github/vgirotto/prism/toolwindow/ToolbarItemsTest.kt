package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.AgentSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolbarItemsTest {

    @Test
    fun `Claude exposes every toolbar item`() {
        val items = toolbarItemsFor(AgentCli.CLAUDE)
        assertEquals(ToolbarItem.values().toList(), items)
    }

    @Test
    fun `Codex now exposes every toolbar item, each mapped to a Codex equivalent`() {
        // Every button has a wired-up Codex behaviour (identical command or a
        // CLI-specific picker flow), so Codex reaches parity with Claude.
        assertEquals(ToolbarItem.values().toList(), toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Resume with identical command`() {
        assertTrue(ToolbarItem.RESUME in toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Compact with identical command`() {
        assertTrue(ToolbarItem.COMPACT in toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Clear with identical command`() {
        assertTrue(ToolbarItem.CLEAR in toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Model via its interactive picker`() {
        assertTrue(ToolbarItem.MODEL in toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Effort via the model picker's reasoning step`() {
        assertTrue(ToolbarItem.EFFORT in toolbarItemsFor(AgentCli.CODEX))
    }

    @Test
    fun `Codex exposes Cost via the usage command`() {
        assertTrue(ToolbarItem.COST in toolbarItemsFor(AgentCli.CODEX))
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
    fun `toolbar input is refused while a sequence is in flight`() {
        val session = AgentSession(name = "test")
        assertTrue(acceptsToolbarInput(session))

        session.beginSequence()
        // The click gate has to hold on its own: the greyed-out presentation only
        // refreshes on IntelliJ's action timer, well after a double-click has landed.
        assertFalse(acceptsToolbarInput(session))

        session.endSequence()
        assertTrue(acceptsToolbarInput(session))

        session.dispose()
    }

    @Test
    fun `toolbar input is accepted when there is no session to gate on`() {
        assertTrue(acceptsToolbarInput(null))
    }

    @Test
    fun `toolbarItemsFor returns items in enum declaration order`() {
        val codex = toolbarItemsFor(AgentCli.CODEX)
        val expected = ToolbarItem.values().filter { it in codex.toSet() }
        assertEquals(expected, codex)
    }
}
