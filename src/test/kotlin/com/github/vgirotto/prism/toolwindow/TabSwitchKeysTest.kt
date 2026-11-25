package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.KeyStroke

/**
 * Which keys switch tabs from an agent terminal, and which of them the agents also use, for the
 * shipped keymaps' Next/Previous Tab bindings.
 */
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
        assertEquals(emptyList<KeyStroke>(), eclipse.agentKeys())
    }

    @Test
    fun `macOS's tab keys switch tabs, and its Ctrl+arrows are the agents' too`() {
        val mac = keys(
            next = listOf("meta shift CLOSE_BRACKET", "control RIGHT"),
            previous = listOf("meta shift OPEN_BRACKET", "control LEFT"),
        )

        assertEquals(TabStep.NEXT, mac.stepFor(stroke("meta shift CLOSE_BRACKET")))
        assertEquals(TabStep.PREVIOUS, mac.stepFor(stroke("meta shift OPEN_BRACKET")))
        assertEquals(TabStep.NEXT, mac.stepFor(stroke("ctrl RIGHT")))
        assertEquals(listOf(stroke("ctrl RIGHT"), stroke("ctrl LEFT")), mac.agentKeys())
    }

    @Test
    fun `the default keymap's Alt+arrows switch tabs and are the agents' too`() {
        val default = keys(next = listOf("alt RIGHT"), previous = listOf("alt LEFT"))

        assertEquals(TabStep.NEXT, default.stepFor(stroke("alt RIGHT")))
        assertEquals(TabStep.PREVIOUS, default.stepFor(stroke("alt LEFT")))
        assertEquals(listOf(stroke("alt RIGHT"), stroke("alt LEFT")), default.agentKeys())
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
