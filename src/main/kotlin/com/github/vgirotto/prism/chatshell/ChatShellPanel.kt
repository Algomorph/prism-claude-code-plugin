package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.i18n.PrismBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Composes an always-visible header row (command toolbar + a Show/Hide Transcript toggle,
 * pinned upper-right like the IDE's show/hide changes-pane button) above the terminal — design §6.4.
 *
 * Two layouts, chosen by [editorMode]:
 *  - **Editor mode** (default, [onEditorToggle] supplied): the transcript lives in a separate
 *    IDE editor tab, so this panel is just the header over the terminal. The toggle opens/closes
 *    that editor tab via [onEditorToggle]; the factory calls [setTranscriptVisibleExternally]
 *    when the tab is closed by its × so the button label stays truthful.
 *  - **Split mode**: the transcript renders in this pane, above the terminal, behind a
 *    [JBSplitter]; the toggle collapses/reveals it and the proportion is persisted per project.
 *
 * The header lives **outside** the splitter so the toolbar and toggle stay reachable even when
 * the transcript is collapsed/absent. To ease adoption the transcript starts **hidden**.
 */
class ChatShellPanel(
    project: Project,
    toolbar: JComponent,
    private val transcriptComponent: JComponent?,
    private val terminalComponent: JComponent,
    private val editorMode: Boolean = false,
    private val onEditorToggle: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val props = PropertiesComponent.getInstance(project)
    private val splitter: JBSplitter? = if (editorMode) null else JBSplitter(true, restoreProportion())
    private var savedProportion = splitter?.proportion ?: DEFAULT_PROPORTION
    private var transcriptVisible = if (editorMode) false else props.getBoolean(VISIBLE_KEY, false)

    private val toggleButton = JButton().apply {
        isFocusable = true
        addActionListener {
            if (editorMode) onEditorToggle?.invoke()
            else setTranscriptVisible(!transcriptVisible)
        }
    }

    init {
        val toggleWrap = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(toggleButton)
        }
        val header = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.CENTER)
            add(toggleWrap, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        val sp = splitter
        if (sp == null) {
            // Editor mode: header over the terminal; transcript is a separate editor tab.
            add(terminalComponent, BorderLayout.CENTER)
            applyToggleLabel()
        } else {
            // Split mode: minimumSize 0 lets a hidden transcript collapse fully at proportion 0f
            // while the splitter still honors the terminal's minimum height.
            transcriptComponent?.let { it.minimumSize = Dimension(0, 0) }
            terminalComponent.minimumSize = Dimension(0, JBUI.scale(MIN_TERMINAL_PX))
            sp.firstComponent = transcriptComponent
            sp.secondComponent = terminalComponent
            sp.dividerWidth = JBUI.scale(3)
            sp.setHonorComponentsMinimumSize(true)

            sp.addPropertyChangeListener(JBSplitter.PROP_PROPORTION) {
                // Persist the proportion only while the transcript is shown, so the collapsed
                // 0f is never saved as the restore point.
                if (transcriptVisible) {
                    savedProportion = sp.proportion
                    props.setValue(PROPORTION_KEY, savedProportion.toString())
                }
            }

            add(sp, BorderLayout.CENTER)
            applyVisibility()
        }
    }

    /**
     * Editor mode: the factory reports the real editor-tab state here (e.g. the user closed the
     * tab with its ×, or the tab was opened) so the toggle button's label reflects reality
     * without re-triggering the open/close action.
     */
    fun setTranscriptVisibleExternally(visible: Boolean) {
        if (visible == transcriptVisible) return
        transcriptVisible = visible
        applyToggleLabel()
    }

    /** Enable/disable the toggle (e.g. disabled when the CLI can't render a transcript). */
    fun setToggleEnabled(enabled: Boolean, disabledTooltip: String? = null) {
        toggleButton.isEnabled = enabled
        if (!enabled && disabledTooltip != null) toggleButton.toolTipText = disabledTooltip
    }

    private fun setTranscriptVisible(visible: Boolean) {
        if (visible == transcriptVisible) return
        if (!visible) savedProportion = (splitter?.proportion ?: savedProportion).coerceIn(0.1f, 0.9f)
        transcriptVisible = visible
        props.setValue(VISIBLE_KEY, visible)
        applyVisibility()
    }

    private fun applyVisibility() {
        splitter?.let { it.proportion = if (transcriptVisible) savedProportion else 0.0f }
        applyToggleLabel()
    }

    private fun applyToggleLabel() {
        val label = PrismBundle.message(
            if (transcriptVisible) "chatshell.hideTranscript" else "chatshell.showTranscript"
        )
        toggleButton.text = label
        if (toggleButton.isEnabled) toggleButton.toolTipText = label
        toggleButton.accessibleContext.accessibleName = label
    }

    private fun restoreProportion(): Float =
        props.getValue(PROPORTION_KEY)?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: DEFAULT_PROPORTION

    companion object {
        private const val PROPORTION_KEY = "prism.chatshell.proportion"
        private const val VISIBLE_KEY = "prism.chatshell.transcriptVisible"
        private const val DEFAULT_PROPORTION = 0.5f
        /** Minimum terminal strip height in unscaled px (~10 rows). Tuned in HITL. */
        private const val MIN_TERMINAL_PX = 180
    }
}
