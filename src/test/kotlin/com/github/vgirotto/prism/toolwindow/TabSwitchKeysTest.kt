package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.KeyStroke

/** Which keys switch tabs from an agent terminal, for the shipped keymaps' Next/Previous Tab bindings. */
class TabSwitchKeysTest {

    private fun keys(next: List<String>, previous: List<String>) = TabSwitchKeys { actionId ->
        when (actionId) {
            "NextTab" -> next
            "PreviousTab" -> previous
            else -> emptyList()
        }.map { KeyStroke.getKeyStroke(it)!! }
    }

    private fun stroke(text: String) = KeyStroke.getKeyStroke(text)!!

    @Test
    fun `Eclipse's tab keys switch tabs`() {
        val eclipse = keys(
            next = listOf("control F6", "control alt LEFT", "control PAGE_DOWN"),
            previous = listOf("shift control F6", "control alt RIGHT", "control PAGE_UP"),
        )

        assertEquals(TabStep.NEXT, eclipse.stepFor(stroke("ctrl PAGE_DOWN")))
        assertEquals(TabStep.PREVIOUS, eclipse.stepFor(stroke("ctrl PAGE_UP")))
        assertEquals(TabStep.NEXT, eclipse.stepFor(stroke("ctrl F6")))
        assertEquals(TabStep.PREVIOUS, eclipse.stepFor(stroke("ctrl shift F6")))
        assertEquals(TabStep.NEXT, eclipse.stepFor(stroke("ctrl alt LEFT")))
    }

    @Test
    fun `macOS's bracket keys switch tabs and its Ctrl+arrows stay with the agent`() {
        val mac = keys(
            next = listOf("meta shift CLOSE_BRACKET", "control RIGHT"),
            previous = listOf("meta shift OPEN_BRACKET", "control LEFT"),
        )

        assertEquals(TabStep.NEXT, mac.stepFor(stroke("meta shift CLOSE_BRACKET")))
        assertEquals(TabStep.PREVIOUS, mac.stepFor(stroke("meta shift OPEN_BRACKET")))
        assertNull(mac.stepFor(stroke("ctrl RIGHT")))
        assertNull(mac.stepFor(stroke("ctrl LEFT")))
    }

    @Test
    fun `the default keymap's Alt+arrows stay with the agent`() {
        val default = keys(next = listOf("alt RIGHT"), previous = listOf("alt LEFT"))

        assertNull(default.stepFor(stroke("alt RIGHT")))
        assertNull(default.stepFor(stroke("alt LEFT")))
    }

    @Test
    fun `keys the keymap does not bind to a tab action pass through`() {
        val eclipse = keys(next = listOf("control PAGE_DOWN"), previous = listOf("control PAGE_UP"))

        assertNull(eclipse.stepFor(stroke("PAGE_DOWN")))
        assertNull(eclipse.stepFor(stroke("shift PAGE_UP")))
    }

    @Test
    fun `a rebinding applies to the next press`() {
        var next = listOf("control PAGE_DOWN")
        val keys = TabSwitchKeys { if (it == "NextTab") next.map { s -> KeyStroke.getKeyStroke(s)!! } else emptyList() }

        assertEquals(TabStep.NEXT, keys.stepFor(stroke("ctrl PAGE_DOWN")))
        next = listOf("control TAB")
        assertNull(keys.stepFor(stroke("ctrl PAGE_DOWN")))
        assertEquals(TabStep.NEXT, keys.stepFor(stroke("ctrl TAB")))
    }
}
