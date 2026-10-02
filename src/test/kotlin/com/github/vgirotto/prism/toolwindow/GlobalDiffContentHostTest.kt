package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.wm.ToolWindowAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
