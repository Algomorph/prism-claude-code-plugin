package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.content.Content
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import javax.swing.JPanel
import javax.swing.JTextArea

class GlobalDiffContentHostTest {
    @Test
    fun `places global diff below a side-docked tool window`() {
        assertEquals(SplitDirection.DOWN, globalDiffSplitDirection(ToolWindowAnchor.LEFT))
        assertEquals(SplitDirection.DOWN, globalDiffSplitDirection(ToolWindowAnchor.RIGHT))
    }

    @Test
    fun `places global diff to the right of a horizontal tool window`() {
        assertEquals(SplitDirection.RIGHT, globalDiffSplitDirection(ToolWindowAnchor.TOP))
        assertEquals(SplitDirection.RIGHT, globalDiffSplitDirection(ToolWindowAnchor.BOTTOM))
    }

    private val history = content(AgentToolWindowFactory.HISTORY_TAB_KEY to true)
    private val error = content()
    private val running = content(AgentToolWindowFactory.CHAT_NUMBER_KEY to 1, AgentToolWindowFactory.SESSION_ID_KEY to "s1")
    private val starting = content(AgentToolWindowFactory.CHAT_NUMBER_KEY to 2)
    private val all = listOf(history, error, running, starting)

    @Test
    fun `a focused chat is the target, wherever focus is inside it`() {
        assertSame(starting, chooseSessionContent(all, focusInside(starting), "s1", history))
    }

    @Test
    fun `focus in History or an error panel does not make it the target`() {
        assertSame(running, chooseSessionContent(all, focusInside(history), "s1", history))
        assertSame(running, chooseSessionContent(all, focusInside(error), "s1", error))
    }

    @Test
    fun `a selected History tab gives way to a chat still starting`() {
        assertSame(starting, chooseSessionContent(listOf(history, starting), focusInside(history), null, history))
    }

    @Test
    fun `a selected chat still starting is the target when no session is active`() {
        assertSame(starting, chooseSessionContent(all, null, null, starting))
    }

    @Test
    fun `without a chat there is no target`() {
        assertNull(chooseSessionContent(listOf(history, error), focusInside(history), null, history))
    }

    private fun focusInside(content: Content) = JTextArea().also { (content.component as JPanel).add(it) }

    private fun content(vararg data: Pair<Key<*>, Any>): Content {
        val userData = data.toMap()
        val component = JPanel()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Content::class.java)) { proxy, method, args ->
            when (method.name) {
                "getUserData" -> userData[args[0]]
                "getComponent" -> component
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Content$userData"
                else -> null
            }
        } as Content
    }
}
