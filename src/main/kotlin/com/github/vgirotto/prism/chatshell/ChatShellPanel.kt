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
 * The terminal always lives in the splitter's second component and is **never reparented**, so a
 * live [setHosting] switch between the two transcript hostings cannot disturb the running session:
 *  - **Split mode**: the transcript renders in the splitter's first component, above the terminal;
 *    the toggle collapses/reveals it and the proportion is persisted per project.
 *  - **Editor mode** ([onEditorToggle] supplied, first component empty): the transcript lives in a
 *    separate IDE editor tab; the toggle opens/closes it via [onEditorToggle], and the factory
 *    calls [setTranscriptVisibleExternally] when the tab is closed by its × so the label stays true.
 *
 * The header lives **outside** the splitter so the toolbar and toggle stay reachable even when the
 * transcript is collapsed/absent. To ease adoption the transcript starts **hidden**.
 */
class ChatShellPanel(
    project: Project,
    toolbar: JComponent,
    transcriptComponent: JComponent?,
    private val terminalComponent: JComponent,
    editorMode: Boolean = false,
    onEditorToggle: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val props = PropertiesComponent.getInstance(project)
    private val splitter = JBSplitter(true, restoreProportion())
    private var savedProportion = splitter.proportion

    private var editorMode = editorMode
    private var onEditorToggle = onEditorToggle
    private var transcriptComponent = transcriptComponent
    private var transcriptVisible = if (editorMode) false else props.getBoolean(VISIBLE_KEY, false)

    private val toggleButton = JButton().apply {
        isFocusable = true
        addActionListener {
            if (this@ChatShellPanel.editorMode) this@ChatShellPanel.onEditorToggle?.invoke()
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

        // The terminal keeps its minimum height and is the permanent second component; a hidden or
        // absent transcript collapses the first component fully at proportion 0f.
        terminalComponent.minimumSize = Dimension(0, JBUI.scale(MIN_TERMINAL_PX))
        splitter.secondComponent = terminalComponent
        splitter.dividerWidth = JBUI.scale(3)
        splitter.setHonorComponentsMinimumSize(true)
        splitter.addPropertyChangeListener(JBSplitter.PROP_PROPORTION) {
            // Persist the proportion only in split mode while the transcript is shown, so the
            // collapsed 0f (or editor mode) is never saved as the restore point.
            if (!this.editorMode && transcriptVisible) {
                savedProportion = splitter.proportion
                props.setValue(PROPORTION_KEY, savedProportion.toString())
            }
        }
        add(splitter, BorderLayout.CENTER)

        installTranscriptComponent()
        applyVisibility()
    }

    /**
     * Switch transcript hosting at runtime (Settings → "Show transcript in the editor area"),
     * keeping the terminal and toolbar in place. [showTranscript] is where the transcript should
     * end up displayed after the switch (in the pane for split mode, or as the caller's cue to
     * open the editor tab in editor mode) so the currently-shown transcript follows the toggle.
     */
    fun setHosting(
        transcriptComponent: JComponent?,
        editorMode: Boolean,
        onEditorToggle: (() -> Unit)?,
        showTranscript: Boolean,
    ) {
        this.editorMode = editorMode
        this.onEditorToggle = onEditorToggle
        this.transcriptComponent = transcriptComponent
        toggleButton.isEnabled = true
        toggleButton.toolTipText = null
        installTranscriptComponent()
        transcriptVisible = showTranscript
        if (!editorMode) {
            props.setValue(VISIBLE_KEY, showTranscript)
            savedProportion = savedProportion.coerceIn(0.1f, 0.9f)
        }
        applyVisibility()
        revalidate()
        repaint()
    }

    private fun installTranscriptComponent() {
        val tc = transcriptComponent
        if (!editorMode && tc != null) {
            tc.minimumSize = Dimension(0, 0)
            splitter.firstComponent = tc
        } else {
            // Editor mode (or not yet built): no in-pane transcript; the terminal fills the pane.
            splitter.firstComponent = null
        }
    }

    /** True when the transcript is currently displayed (shown in the pane, or its editor tab open). */
    fun isTranscriptVisible(): Boolean = transcriptVisible

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
        if (!visible) savedProportion = splitter.proportion.coerceIn(0.1f, 0.9f)
        transcriptVisible = visible
        props.setValue(VISIBLE_KEY, visible)
        applyVisibility()
    }

    private fun applyVisibility() {
        if (!editorMode) splitter.proportion = if (transcriptVisible) savedProportion else 0.0f
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
