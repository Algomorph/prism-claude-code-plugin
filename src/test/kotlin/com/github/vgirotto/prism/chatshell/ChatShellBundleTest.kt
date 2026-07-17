package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards the i18n requirement (R21): every new chat-shell UI string must exist in en, es,
 * and pt. Reads the resource bundles directly (no Application needed) so it runs headless.
 */
class ChatShellBundleTest {

    private val keys = listOf(
        "chatshell.loading", "chatshell.noTranscript", "chatshell.unavailable",
        "chatshell.reconnecting", "chatshell.error", "chatshell.recovery.choose", "chatshell.recovery.retry",
        "chatshell.recovery.terminalOnly", "chatshell.showTranscript", "chatshell.hideTranscript",
        "chatshell.blockedImage", "chatshell.unsupported", "chatshell.showFull", "chatshell.copied",
        "chatshell.disclosure.thinking", "chatshell.disclosure.output", "chatshell.disclosure.details",
    )

    private val control = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?): Locale? = null
    }

    private fun assertBundleHasAllKeys(locale: Locale) {
        val bundle = ResourceBundle.getBundle("messages.ClaudeBundle", locale, control)
        for (k in keys) {
            assertTrue(bundle.containsKey(k), "missing key '$k' in locale '$locale'")
            assertFalse(bundle.getString(k).isBlank(), "blank value for '$k' in locale '$locale'")
        }
    }

    @Test fun `english bundle has all chat-shell keys`() = assertBundleHasAllKeys(Locale.ROOT)
    @Test fun `spanish bundle has all chat-shell keys`() = assertBundleHasAllKeys(Locale.of("es"))
    @Test fun `portuguese bundle has all chat-shell keys`() = assertBundleHasAllKeys(Locale.of("pt"))
}
