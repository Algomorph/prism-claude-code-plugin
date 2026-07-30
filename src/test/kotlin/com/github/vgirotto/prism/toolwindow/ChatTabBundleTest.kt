package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards the chat-tab strings in every shipped locale (the i18n requirement, R21). Reads the
 * resource bundles directly — with fallback disabled, so a key missing from es/pt fails here
 * instead of silently resolving to English at runtime.
 */
class ChatTabBundleTest {

    private val keys = listOf("toolwindow.tab.chat", "toolwindow.tab.tooltip")

    private val control = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?): Locale? = null
    }

    private fun bundle(locale: Locale): ResourceBundle =
        ResourceBundle.getBundle("messages.PrismBundle", locale, control)

    private fun assertBundleHasAllKeys(locale: Locale) {
        val bundle = bundle(locale)
        for (k in keys) {
            assertTrue(bundle.containsKey(k), "missing key '$k' in locale '$locale'")
            assertFalse(bundle.getString(k).isBlank(), "blank value for '$k' in locale '$locale'")
        }
    }

    @Test fun `english bundle has the chat-tab keys`() = assertBundleHasAllKeys(Locale.ROOT)
    @Test fun `spanish bundle has the chat-tab keys`() = assertBundleHasAllKeys(Locale.of("es"))
    @Test fun `portuguese bundle has the chat-tab keys`() = assertBundleHasAllKeys(Locale.of("pt"))

    @Test
    fun `the tooltip pattern takes the agent name and the chat name, in that order`() {
        for (locale in listOf(Locale.ROOT, Locale.of("es"), Locale.of("pt"))) {
            val formatted = MessageFormat(bundle(locale).getString("toolwindow.tab.tooltip"))
                .format(arrayOf("Claude Code", "Diagnose missing transcript"))
            assertEquals("Claude Code — Diagnose missing transcript", formatted, "locale '$locale'")
        }
    }
}
