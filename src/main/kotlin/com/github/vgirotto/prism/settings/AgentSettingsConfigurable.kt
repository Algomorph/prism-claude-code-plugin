package com.github.vgirotto.prism.settings

import com.github.vgirotto.prism.i18n.ClaudeBundle
import com.github.vgirotto.prism.services.AgentSettingsState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel as dslPanel

class AgentSettingsConfigurable : BoundConfigurable(ClaudeBundle.message("settings.title")) {

    private val settings = AgentSettingsState.getInstance()

    override fun createPanel() = dslPanel {
        group(ClaudeBundle.message("settings.group.general")) {
            row(ClaudeBundle.message("settings.claude.path")) {
                textField()
                    .bindText(settings::claudePath)
                    .columns(COLUMNS_LARGE)
                    .comment(ClaudeBundle.message("settings.claude.path.comment"))
            }
            row(ClaudeBundle.message("settings.shell")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle(ClaudeBundle.message("settings.shell.browse"))
                )
                    .bindText(settings::shellPath)
                    .columns(COLUMNS_LARGE)
                    .comment(ClaudeBundle.message("settings.shell.comment"))
            }
            row {
                checkBox(ClaudeBundle.message("settings.autostart"))
                    .bindSelected(settings::autoStartOnOpen)
            }
        }

        group(ClaudeBundle.message("settings.group.appearance")) {
            row {
                checkBox(ClaudeBundle.message("settings.show.changes"))
                    .bindSelected(settings::showChangesOnStartup)
            }
        }

        group(ClaudeBundle.message("settings.group.language")) {
            row(ClaudeBundle.message("settings.language")) {
                val languages = listOf("en" to "English", "pt" to "Português", "es" to "Español")
                comboBox(languages.map { it.second })
                    .applyToComponent {
                        selectedIndex = languages.indexOfFirst { it.first == settings.language }.coerceAtLeast(0)
                    }
                    .onChanged {
                        val idx = it.selectedIndex
                        if (idx >= 0) {
                            settings.language = languages[idx].first
                            ClaudeBundle.invalidateCache()
                        }
                    }
                    .comment(ClaudeBundle.message("settings.language.comment"))
            }
        }

        group(ClaudeBundle.message("settings.group.snapshot")) {
            row(ClaudeBundle.message("settings.excluded")) {
                textField()
                    .bindText(settings::excludedPatterns)
                    .columns(COLUMNS_LARGE)
                    .comment(ClaudeBundle.message("settings.excluded.comment"))
            }
            row(ClaudeBundle.message("settings.max.file.size")) {
                spinner(1..10240, 64)
                    .bindIntValue(settings::maxFileSizeKb)
                    .comment(ClaudeBundle.message("settings.max.file.size.comment"))
            }
        }
    }
}
