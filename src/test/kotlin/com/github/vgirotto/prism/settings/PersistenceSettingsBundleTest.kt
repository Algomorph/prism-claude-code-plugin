package com.github.vgirotto.prism.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Properties

/** Guards the persistence settings' strings in every shipped locale's own file, not the English one it falls back to. */
class PersistenceSettingsBundleTest {

    private val keys = listOf(
        "settings.persist.tabs",
        "settings.persist.tabs.comment",
        "settings.persist.sessions",
        "settings.persist.sessions.comment",
    )

    @ParameterizedTest
    @ValueSource(strings = ["", "_pt", "_es"])
    fun `every locale has the persistence settings strings`(suffix: String) {
        val file = "/messages/PrismBundle$suffix.properties"
        val strings = Properties()
        javaClass.getResourceAsStream(file)!!.reader(Charsets.UTF_8).use(strings::load)
        for (key in keys) {
            val value = strings.getProperty(key)
            assertNotNull(value, "missing '$key' in $file")
            assertFalse(value.isBlank(), "blank '$key' in $file")
        }
    }
}
