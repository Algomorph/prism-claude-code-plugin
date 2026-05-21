package com.github.vgirotto.prism.settings

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentSettingsState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel as dslPanel

class AgentSettingsConfigurable : BoundConfigurable(PrismBundle.message("settings.title")) {

    private val settings = AgentSettingsState.getInstance()

    override fun createPanel() = dslPanel {
        group(PrismBundle.message("settings.group.general")) {
            row(PrismBundle.message("settings.shell")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle(PrismBundle.message("settings.shell.browse"))
                )
                    .bindText(settings::shellPath)
                    .columns(COLUMNS_LARGE)
                    .comment(PrismBundle.message("settings.shell.comment"))
            }
            row {
                checkBox(PrismBundle.message("settings.autostart"))
                    .bindSelected(settings::autoStartOnOpen)
            }
            row(PrismBundle.message("settings.default.cli")) {
                val labels = mapOf(
                    AgentCli.CLAUDE to "Claude Code",
                    AgentCli.CODEX to "Codex",
                )
                val ordered = AgentCli.values().toList()
                comboBox(ordered.map { labels.getValue(it) })
                    .applyToComponent {
                        selectedIndex = ordered.indexOf(settings.defaultCli).coerceAtLeast(0)
                    }
                    .onChanged {
                        val idx = it.selectedIndex
                        if (idx >= 0) settings.defaultCli = ordered[idx]
                    }
                    .comment(PrismBundle.message("settings.default.cli.comment"))
            }
        }

        group(PrismBundle.message("settings.group.claude")) {
            row(PrismBundle.message("settings.claude.path")) {
                textField()
                    .bindText(settings::claudePath)
                    .columns(COLUMNS_LARGE)
                    .comment(PrismBundle.message("settings.claude.path.comment"))
            }
        }

        group(PrismBundle.message("settings.group.codex")) {
            row(PrismBundle.message("settings.codex.path")) {
                textField()
                    .bindText(settings::codexPath)
                    .columns(COLUMNS_LARGE)
                    .comment(PrismBundle.message("settings.codex.path.comment"))
            }
        }

        group(PrismBundle.message("settings.group.appearance")) {
            row {
                checkBox(PrismBundle.message("settings.show.changes"))
                    .bindSelected(settings::showChangesOnStartup)
            }
        }

        group(PrismBundle.message("settings.group.language")) {
            row(PrismBundle.message("settings.language")) {
                val languages = listOf("en" to "English", "pt" to "Português", "es" to "Español")
                comboBox(languages.map { it.second })
                    .applyToComponent {
                        selectedIndex = languages.indexOfFirst { it.first == settings.language }.coerceAtLeast(0)
                    }
                    .onChanged {
                        val idx = it.selectedIndex
                        if (idx >= 0) {
                            settings.language = languages[idx].first
                            PrismBundle.invalidateCache()
                        }
                    }
                    .comment(PrismBundle.message("settings.language.comment"))
            }
        }

        group(PrismBundle.message("settings.group.snapshot")) {
            row(PrismBundle.message("settings.excluded")) {
                textField()
                    .bindText(settings::excludedPatterns)
                    .columns(COLUMNS_LARGE)
                    .comment(PrismBundle.message("settings.excluded.comment"))
            }
            row(PrismBundle.message("settings.max.file.size")) {
                spinner(1..10240, 64)
                    .bindIntValue(settings::maxFileSizeKb)
                    .comment(PrismBundle.message("settings.max.file.size.comment"))
            }
        }
    }
}
