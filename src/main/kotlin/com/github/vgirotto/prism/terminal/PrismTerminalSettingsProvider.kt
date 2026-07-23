package com.github.vgirotto.prism.terminal

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import java.awt.Font
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Terminal settings for the embedded Prism terminal that follow the IDE's own
 * terminal font settings (Settings > Tools > Terminal > Font Settings).
 *
 * The bare [JBTerminalSystemSettingsProviderBase] sources the font family from
 * the editor's console color scheme and its size from an *unscaled* console
 * font-size provider — so on a HiDPI display the embedded terminal renders in
 * the wrong font, tiny. The IDE's own terminal instead uses the terminal
 * plugin's `JBTerminalSystemSettingsProvider`, which reads the reworked font
 * family and, crucially, a HiDPI-*scaled* size.
 *
 * We keep extending the base (so all of Prism's tuned shortcut/paste behavior is
 * preserved) but delegate only the font, font size, and line spacing to an
 * instance of that IDE provider. It lives in the terminal plugin, which is not
 * on the 2024.3 build baseline's API surface, so it is instantiated reflectively
 * through the terminal plugin's own classloader. When unavailable (older IDEs),
 * every delegate transparently falls back to the base behavior.
 */
class PrismTerminalSettingsProvider : JBTerminalSystemSettingsProviderBase() {

    private val ideProvider: JBTerminalSystemSettingsProviderBase? = IdeTerminalProvider.create()
    private val logged = AtomicBoolean(false)

    override fun getTerminalFont(): Font {
        val font = ideProvider?.terminalFont ?: super.getTerminalFont()
        if (logged.compareAndSet(false, true)) {
            LOG.info(
                "Prism terminal font: delegate=${ideProvider != null}, " +
                    "family=${font.family}, size=${font.size2D}"
            )
        }
        return font
    }

    override fun getTerminalFontSize(): Float =
        ideProvider?.terminalFontSize ?: super.getTerminalFontSize()

    override fun getLineSpacing(): Float =
        ideProvider?.lineSpacing ?: super.getLineSpacing()

    companion object {
        private val LOG = Logger.getInstance(PrismTerminalSettingsProvider::class.java)
    }
}

/**
 * Instantiates the terminal plugin's own `JBTerminalSystemSettingsProvider`
 * (the exact provider the IDE's terminal uses) through the terminal plugin's
 * classloader, which is guaranteed to see it. Returns it typed as the platform
 * base class. Any failure yields `null` so the caller falls back to defaults.
 */
private object IdeTerminalProvider {

    private val LOG = Logger.getInstance(IdeTerminalProvider::class.java)

    fun create(): JBTerminalSystemSettingsProviderBase? = try {
        val loader = PluginManagerCore
            .getPlugin(PluginId.getId("org.jetbrains.plugins.terminal"))
            ?.pluginClassLoader
        if (loader == null) {
            null
        } else {
            val cls = Class.forName(
                "org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider", true, loader
            )
            cls.getDeclaredConstructor().newInstance() as? JBTerminalSystemSettingsProviderBase
        }
    } catch (t: Throwable) {
        LOG.info("IDE terminal settings provider unavailable; using base font. Cause: $t")
        null
    }
}
