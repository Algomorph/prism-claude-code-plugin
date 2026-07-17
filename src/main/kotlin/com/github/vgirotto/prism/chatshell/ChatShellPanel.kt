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
 * pinned upper-right like the IDE's show/hide changes-pane button) above a vertical split of
 * the rendered transcript (top) over the terminal strip (bottom) — design §6.4.
 *
 * The header lives **outside** the splitter so the toolbar and toggle stay reachable even
 * when the transcript is collapsed. To ease adoption the transcript starts **hidden**
 * (terminal expanded); the toggle reveals it, and the choice is persisted per project.
 *
 * Min-row guard (R11/R12): the terminal keeps a minimum height the splitter honors; hiding
 * the transcript drives the split to give the terminal (near) all the space so a long
 * `AskUserQuestion` list is always reachable.
 */
class ChatShellPanel(
    project: Project,
    toolbar: JComponent,
    private val transcriptComponent: JComponent,
    private val terminalComponent: JComponent,
) : JPanel(BorderLayout()) {

    private val props = PropertiesComponent.getInstance(project)
    private val splitter = JBSplitter(true, restoreProportion())
    private var savedProportion = splitter.proportion
    private var transcriptVisible = props.getBoolean(VISIBLE_KEY, false)

    private val toggleButton = JButton().apply {
        isFocusable = true
        addActionListener { setTranscriptVisible(!transcriptVisible) }
    }

    init {
        val toggleWrap = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(toggleButton)
        }
        val header = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.CENTER)
            add(toggleWrap, BorderLayout.EAST)
        }

        // minimumSize 0 lets a hidden transcript collapse fully at proportion 0f while the
        // splitter still honors the terminal's minimum height.
        transcriptComponent.minimumSize = Dimension(0, 0)
        terminalComponent.minimumSize = Dimension(0, JBUI.scale(MIN_TERMINAL_PX))
        splitter.firstComponent = transcriptComponent
        splitter.secondComponent = terminalComponent
        splitter.dividerWidth = JBUI.scale(3)
        splitter.setHonorComponentsMinimumSize(true)

        splitter.addPropertyChangeListener(JBSplitter.PROP_PROPORTION) {
            // Persist the proportion only while the transcript is shown, so the collapsed
            // 0f is never saved as the restore point.
            if (transcriptVisible) {
                savedProportion = splitter.proportion
                props.setValue(PROPORTION_KEY, savedProportion.toString())
            }
        }

        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        applyVisibility()
    }

    private fun setTranscriptVisible(visible: Boolean) {
        if (visible == transcriptVisible) return
        if (!visible) savedProportion = splitter.proportion.coerceIn(0.1f, 0.9f)
        transcriptVisible = visible
        props.setValue(VISIBLE_KEY, visible)
        applyVisibility()
    }

    private fun applyVisibility() {
        splitter.proportion = if (transcriptVisible) savedProportion else 0.0f
        val label = PrismBundle.message(
            if (transcriptVisible) "chatshell.hideTranscript" else "chatshell.showTranscript"
        )
        toggleButton.text = label
        toggleButton.toolTipText = label
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
