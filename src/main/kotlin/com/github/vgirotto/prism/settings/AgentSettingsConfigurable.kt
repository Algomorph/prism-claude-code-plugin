package com.github.vgirotto.prism.settings

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.services.AgentSettingsState
import com.github.vgirotto.prism.toolwindow.TabStep
import com.github.vgirotto.prism.toolwindow.TabSwitchKeys
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.impl.ui.KeymapPanel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel as dslPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.event.HierarchyEvent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
                // bindItem lets the framework track modifications, so Apply/Cancel
                // behave per the IntelliJ settings contract instead of the combo
                // mutating the persistent setting the moment the selection changes.
                comboBox(
                    AgentCli.entries,
                    // The (nullValue, Function) overload is scheduled for removal; the
                    // customizer form has to blank the label itself for a null value.
                    SimpleListCellRenderer.create<AgentCli> { label, value, _ ->
                        label.text = value?.let { labels.getValue(it) }.orEmpty()
                    },
                )
                    .bindItem({ settings.defaultCli }, { settings.defaultCli = it ?: AgentCli.DEFAULT })
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

        group(PrismBundle.message("settings.group.keys")) {
            val tabKeys = TabSwitchKeys()
            lateinit var current: JLabel
            lateinit var warningText: JEditorPane
            row(PrismBundle.message("settings.keys.tabs")) {
                current = label("").component
                link(PrismBundle.message("settings.keys.tabs.edit")) { editTabShortcuts(it.source as Component) }
            }.rowComment(PrismBundle.message("settings.keys.tabs.comment"))
            val warning = row {
                icon(AllIcons.General.Warning).align(AlignY.TOP)
                warningText = text("").component
            }
            fun refresh() {
                fun keys(strokes: List<KeyStroke>) =
                    strokes.joinToString(", ", transform = KeymapUtil::getKeystrokeText)
                        .ifEmpty { PrismBundle.message("settings.keys.tabs.none") }
                current.text = PrismBundle.message(
                    "settings.keys.tabs.current", keys(tabKeys.strokes(TabStep.NEXT)), keys(tabKeys.strokes(TabStep.PREVIOUS)),
                )
                val clashes = tabKeys.agentKeys()
                warning.visible(clashes.isNotEmpty())
                warningText.text = StringUtil.escapeXmlEntities(PrismBundle.message("settings.keys.tabs.warning", keys(clashes)))
            }
            refresh()
            // The keymap can change in this same Settings dialog, so the keys are read again each time the page shows.
            current.addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && current.isShowing) refresh()
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
            lateinit var excludedPatternsEditor: javax.swing.JTextArea
            row(PrismBundle.message("settings.excluded")) {
                textArea()
                    .bindText(settings::excludedPatterns)
                    .columns(COLUMNS_LARGE)
                    .applyToComponent {
                        excludedPatternsEditor = this
                        rows = 4
                        lineWrap = true
                        wrapStyleWord = true
                    }
            }
            row("") {
                label("").applyToComponent {
                    font = JBUI.Fonts.smallFont()
                    foreground = UIUtil.getContextHelpForeground()
                    fun updateHelp() {
                        text = "<html>${PrismBundle.message("settings.excluded.comment")}<br>" +
                            PrismBundle.message(
                            "settings.excluded.count",
                            AgentSettingsState.parseExcludedPatterns(excludedPatternsEditor.text).size,
                            ) + "</html>"
                    }
                    updateHelp()
                    excludedPatternsEditor.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(event: DocumentEvent) = updateHelp()
                        override fun removeUpdate(event: DocumentEvent) = updateHelp()
                        override fun changedUpdate(event: DocumentEvent) = updateHelp()
                    })
                }
            }.topGap(TopGap.NONE)
            row(PrismBundle.message("settings.max.file.size")) {
                spinner(1..10240, 64)
                    .bindIntValue(settings::maxFileSizeKb)
                    .comment(PrismBundle.message("settings.max.file.size.comment"))
            }
        }
    }

    /** Opens Keymap at Select Next Tab, in this Settings dialog when there is one. */
    private fun editTabShortcuts(source: Component) {
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(source))
        val keymap = settings?.find(KeymapPanel::class.java)
        if (settings != null && keymap != null) {
            settings.select(keymap).doWhenDone { keymap.selectAction(TabStep.NEXT.actionId) }
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(null, KeymapPanel::class.java) { it.selectAction(TabStep.NEXT.actionId) }
        }
    }
}
