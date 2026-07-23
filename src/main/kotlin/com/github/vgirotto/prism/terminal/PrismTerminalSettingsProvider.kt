package com.github.vgirotto.prism.terminal

import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import java.awt.Font
import java.lang.reflect.Method

/**
 * Terminal settings for the embedded Prism terminal that follow the IDE's own
 * terminal font settings (Settings > Tools > Terminal > Font Settings) instead
 * of the editor's console color-scheme font, which is what the bare
 * [JBTerminalSystemSettingsProviderBase] uses.
 *
 * The reworked-terminal font settings live in
 * `org.jetbrains.plugins.terminal.TerminalFontSettingsService`, which does not
 * exist on the 2024.3 platform baseline this plugin builds against — so it is
 * read reflectively via [ReworkedTerminalFont]. When the service is unavailable
 * (older IDEs), every override transparently falls back to the base behavior.
 *
 * Font size is intentionally left to the base class: on every supported IDE the
 * base already sources it from the terminal's own (zoom-aware) size provider, so
 * overriding it here would break Ctrl+scroll zoom.
 */
class PrismTerminalSettingsProvider : JBTerminalSystemSettingsProviderBase() {

    override fun getTerminalFont(): Font {
        val family = ReworkedTerminalFont.fontFamily() ?: return super.getTerminalFont()
        // Match the base contract: build the family at the current (zoom-aware) size.
        return Font(family, Font.PLAIN, 1).deriveFont(terminalFontSize)
    }

    override fun getLineSpacing(): Float =
        ReworkedTerminalFont.lineSpacing() ?: super.getLineSpacing()
}

/**
 * Null-safe reflective reader for the reworked terminal's font settings. Handle
 * lookup is cached; individual reads are live so changes to the IDE terminal
 * font are picked up on the next repaint. Any failure yields `null`, letting the
 * caller fall back to platform defaults.
 */
private object ReworkedTerminalFont {

    private class Handles(
        val getInstance: Method,
        val getSettings: Method,
        val getFontFamily: Method,
        val getLineSpacing: Method,
        val lineSpacingFloatValue: Method,
    )

    private val handles: Handles? by lazy { resolve() }

    private fun resolve(): Handles? = try {
        val serviceCls = Class.forName("org.jetbrains.plugins.terminal.TerminalFontSettingsService")
        val settingsCls = Class.forName("org.jetbrains.plugins.terminal.TerminalFontSettings")
        val lineSpacingCls = Class.forName("org.jetbrains.plugins.terminal.TerminalLineSpacing")
        Handles(
            getInstance = serviceCls.getMethod("getInstance"),
            getSettings = serviceCls.getMethod("getSettings"),
            getFontFamily = settingsCls.getMethod("getFontFamily"),
            getLineSpacing = settingsCls.getMethod("getLineSpacing"),
            lineSpacingFloatValue = lineSpacingCls.getMethod("getFloatValue"),
        )
    } catch (_: Throwable) {
        null
    }

    private fun settings(h: Handles): Any? {
        return try {
            val service = h.getInstance.invoke(null) ?: return null
            h.getSettings.invoke(service)
        } catch (_: Throwable) {
            null
        }
    }

    fun fontFamily(): String? {
        val h = handles ?: return null
        return try {
            val s = settings(h) ?: return null
            (h.getFontFamily.invoke(s) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun lineSpacing(): Float? {
        val h = handles ?: return null
        return try {
            val s = settings(h) ?: return null
            val lineSpacing = h.getLineSpacing.invoke(s) ?: return null
            (h.lineSpacingFloatValue.invoke(lineSpacing) as? Float)?.takeIf { it > 0f }
        } catch (_: Throwable) {
            null
        }
    }
}
