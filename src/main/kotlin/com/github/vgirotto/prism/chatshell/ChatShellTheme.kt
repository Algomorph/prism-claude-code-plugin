package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Derives the `--prism-*` CSS variables the shell uses from the IDE's global editor color
 * scheme (design §10), so the transcript matches the current light/dark theme. Re-derived
 * and patched **in place** on theme/scheme change (no page reload) — see
 * [TranscriptView.setTheme].
 */
object ChatShellTheme {

    fun currentVars(): Map<String, String> {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val dark = ColorUtil.isDark(bg)

        val border = blend(bg, fg, 0.22)
        val muted = blend(bg, fg, 0.5)
        val codeBg = if (dark) alpha(fg, 0.10) else alpha(fg, 0.06)
        val accent = JBColor.namedColor("Link.activeForeground", JBColor(0x3574F0, 0x548AF7))
        val texCmd = if (dark) Color(0xD1, 0xA5, 0x50) else Color(0xC8, 0x91, 0x2F)

        return linkedMapOf(
            "--prism-bg" to hex(bg),
            "--prism-fg" to hex(fg),
            "--prism-accent" to hex(accent),
            "--prism-border" to hex(border),
            "--prism-muted" to hex(muted),
            "--prism-code-bg" to rgba(fg, if (dark) 0.10 else 0.06),
            "--prism-tex-cmd" to hex(texCmd),
        )
    }

    private fun hex(c: Color): String = "#" + ColorUtil.toHex(c)

    private fun blend(a: Color, b: Color, t: Double): Color {
        val r = (a.red * (1 - t) + b.red * t).toInt()
        val g = (a.green * (1 - t) + b.green * t).toInt()
        val bl = (a.blue * (1 - t) + b.blue * t).toInt()
        return Color(r.coerceIn(0, 255), g.coerceIn(0, 255), bl.coerceIn(0, 255))
    }

    private fun alpha(c: Color, a: Double): Color =
        Color(c.red, c.green, c.blue, (a * 255).toInt().coerceIn(0, 255))

    private fun rgba(c: Color, a: Double): String = "rgba(${c.red},${c.green},${c.blue},$a)"
}
