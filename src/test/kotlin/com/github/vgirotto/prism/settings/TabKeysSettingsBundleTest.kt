package com.github.vgirotto.prism.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Properties

/** Guards the tab-key settings' strings in every shipped locale's own file, not the English one it falls back to. */
class TabKeysSettingsBundleTest {

    private val keys = listOf(
        "settings.group.keys",
        "settings.keys.tabs",
        "settings.keys.tabs.current",
        "settings.keys.tabs.none",
        "settings.keys.tabs.edit",
        "settings.keys.tabs.comment",
        "settings.keys.tabs.warning",
    )

    @ParameterizedTest
    @ValueSource(strings = ["", "_pt", "_es"])
    fun `every locale has the tab-key settings strings`(suffix: String) {
        val file = "/messages/PrismBundle$suffix.properties"
        val strings = Properties()
        javaClass.getResourceAsStream(file)!!.reader(Charsets.UTF_8).use(strings::load)
        for (key in keys) {
            val value = strings.getProperty(key)
            assertNotNull(value, "missing '$key' in $file")
            assertFalse(value.isBlank(), "blank '$key' in $file")
        }
        assertTrue(strings.getProperty("settings.keys.tabs.current").let { "{0}" in it && "{1}" in it }, "keys pattern in $file")
        assertTrue("{0}" in strings.getProperty("settings.keys.tabs.warning"), "warning pattern in $file")
    }
}
