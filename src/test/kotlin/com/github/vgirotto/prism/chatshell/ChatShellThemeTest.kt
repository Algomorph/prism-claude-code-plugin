package com.github.vgirotto.prism.chatshell

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the theme derivation produces the CSS variables the shell expects, with valid
 * values. Needs an Application (EditorColorsManager) but no display, so it runs headless.
 */
@TestApplication
class ChatShellThemeTest {

    @Test
    fun `currentVars yields the expected prism variables with valid values`() {
        val vars = ChatShellTheme.currentVars()
        val required = listOf(
            "--prism-bg", "--prism-fg", "--prism-accent",
            "--prism-border", "--prism-muted", "--prism-code-bg", "--prism-tex-cmd",
        )
        for (k in required) {
            assertTrue(vars.containsKey(k), "missing $k")
            val v = vars.getValue(k)
            assertTrue(v.startsWith("#") || v.startsWith("rgba("), "unexpected color value for $k: $v")
        }
        // Hex colors are #rrggbb.
        assertTrue(Regex("^#[0-9a-fA-F]{6}$").matches(vars.getValue("--prism-bg")))
        assertTrue(Regex("^#[0-9a-fA-F]{6}$").matches(vars.getValue("--prism-fg")))
    }
}
