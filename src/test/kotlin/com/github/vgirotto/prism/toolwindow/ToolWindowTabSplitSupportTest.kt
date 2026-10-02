package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentManager

class ToolWindowTabSplitSupportTest {
    @Test
    fun `prefers modern capability when both are available`() {
        assertEquals(SplitStrategyKind.MODERN, selectSplitStrategy(true, true))
    }

    @Test
    fun `uses legacy capability for build 243 style runtime`() {
        assertEquals(SplitStrategyKind.LEGACY, selectSplitStrategy(false, true))
    }

    @Test
    fun `fails closed when no split capability exists`() {
        assertEquals(SplitStrategyKind.UNAVAILABLE, selectSplitStrategy(false, false))
    }

    @Test
    fun `current test runtime exposes a complete split contract`() {
        val modern = ToolWindow::class.java.methods.any {
            it.name == "setTabsSplittingAllowed" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }

        if (modern) {
            assertEquals("TW.SplitAndMoveRight", modernSplitActionId(SplitDirection.RIGHT))
            assertEquals("TW.SplitAndMoveDown", modernSplitActionId(SplitDirection.DOWN))
            assertEquals("TW.Unsplit", modernSplitActionId(SplitDirection.UNSPLIT))
        } else {
            val split = Class.forName("com.intellij.ui.content.tabs.TabbedContentAction\$SplitTabAction")
            val unsplit = Class.forName("com.intellij.ui.content.tabs.TabbedContentAction\$UnsplitTabAction")
            assertNotNull(split.getConstructor(ContentManager::class.java, Boolean::class.javaPrimitiveType))
            assertNotNull(unsplit.getConstructor(ContentManager::class.java))
        }
    }
}
