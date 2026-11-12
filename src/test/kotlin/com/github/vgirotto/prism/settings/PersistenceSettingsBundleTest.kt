package com.github.vgirotto.prism.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import java.util.ResourceBundle

/** Guards the persistence settings' strings in every shipped locale, without falling back to English. */
class PersistenceSettingsBundleTest {

    private val keys = listOf(
        "settings.persist.tabs",
        "settings.persist.tabs.comment",
        "settings.persist.sessions",
        "settings.persist.sessions.comment",
    )

    private val noFallback = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?): Locale? = null
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "pt", "es"])
    fun `every locale has the persistence settings strings`(language: String) {
        val bundle = ResourceBundle.getBundle("messages.PrismBundle", Locale.of(language), noFallback)
        for (key in keys) {
            assertTrue(bundle.containsKey(key), "missing '$key' in locale '$language'")
            assertFalse(bundle.getString(key).isBlank(), "blank '$key' in locale '$language'")
        }
    }
}
