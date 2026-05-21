package com.github.vgirotto.prism.i18n

import com.github.vgirotto.prism.services.AgentSettingsState
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Internationalization bundle for the Prism plugin.
 * Supports en, pt, es with fallback to English (base bundle).
 *
 * The base bundle (ClaudeBundle.properties) is English.
 * We use a custom Control to prevent the JVM from falling back to the
 * system locale (which would load _pt on a Portuguese macOS even when
 * the user selected English).
 */
object ClaudeBundle {

    private const val BUNDLE_NAME = "messages.ClaudeBundle"

    private var cachedLocale: Locale? = null
    private var cachedBundle: ResourceBundle? = null

    /**
     * Custom control that falls back to ROOT (base bundle = English)
     * instead of the system locale.
     */
    private val control = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?): Locale? {
            // Don't fall back to system locale — go straight to base bundle
            return null
        }
    }

    fun getLocale(): Locale {
        val lang = AgentSettingsState.getInstance().language
        return when (lang) {
            "pt" -> Locale.of("pt")
            "es" -> Locale.of("es")
            else -> Locale.ROOT // ROOT loads ClaudeBundle.properties (English)
        }
    }

    private fun getBundle(): ResourceBundle {
        val locale = getLocale()
        if (cachedBundle == null || cachedLocale != locale) {
            cachedLocale = locale
            cachedBundle = ResourceBundle.getBundle(BUNDLE_NAME, locale, control)
        }
        return cachedBundle!!
    }

    fun invalidateCache() {
        cachedBundle = null
        cachedLocale = null
        // Also clear ResourceBundle's internal cache
        ResourceBundle.clearCache()
    }

    fun message(key: String, vararg params: Any): String {
        return try {
            val pattern = getBundle().getString(key)
            if (params.isEmpty()) pattern
            else MessageFormat.format(pattern, *params)
        } catch (_: MissingResourceException) {
            // Fallback: load base bundle directly
            try {
                val fallback = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, control)
                val pattern = fallback.getString(key)
                if (params.isEmpty()) pattern
                else MessageFormat.format(pattern, *params)
            } catch (_: MissingResourceException) {
                "!$key!"
            }
        }
    }
}
