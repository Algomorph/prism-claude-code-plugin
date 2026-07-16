package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.i18n.ClaudeBundle
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
 * Composes the transcript pane above the existing `[toolbar + terminal]` strip
 * (design §6.4). The internal split is **always vertical** (transcript on top); the outer
 * splitter (terminal-vs-DiffPanel) keeps flipping by dock side, so nesting is safe.
 *
 * Min-row guard + mandatory expand (R11/R12): the terminal strip has a minimum height and
 * the splitter honors it, and an always-available "expand terminal" toggle drives the
 * strip to (near) full height so a long `AskUserQuestion` list is always reachable. The
 * guarantee is *never below the tested minimum, always expandable* — not "never clipped."
 */
class ChatShellPanel(
    project: Project,
    toolbar: JComponent,
    transcriptComponent: JComponent,
    private val terminalComponent: JComponent,
) : JPanel(BorderLayout()) {

    private val props = PropertiesComponent.getInstance(project)
    private val splitter = JBSplitter(true, restoreProportion())
    private var savedProportion = splitter.proportion
    private var expanded = false

    private val expandButton = JButton(ClaudeBundle.message("chatshell.expandTerminal")).apply {
        isFocusable = true
        toolTipText = ClaudeBundle.message("chatshell.expandTerminal")
        accessibleContext.accessibleName = ClaudeBundle.message("chatshell.expandTerminal")
        addActionListener { toggleExpand() }
    }

    init {
        // The command toolbar and the expand toggle share one row at the very top of the
        // chat (just below the tabs): actions fill the width, the toggle is pinned right
        // (design §6.4, HITL). The toolbar sits above the transcript so it is visible
        // regardless of the splitter proportion, unlike its old spot atop the terminal.
        val toggleWrap = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(expandButton)
        }
        val header = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.CENTER)
            add(toggleWrap, BorderLayout.EAST)
        }
        val transcriptWithHeader = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(transcriptComponent, BorderLayout.CENTER)
        }

        terminalComponent.minimumSize = Dimension(0, JBUI.scale(MIN_TERMINAL_PX))
        splitter.firstComponent = transcriptWithHeader
        splitter.secondComponent = terminalComponent
        splitter.dividerWidth = JBUI.scale(3)
        splitter.setHonorComponentsMinimumSize(true)

        splitter.addPropertyChangeListener(JBSplitter.PROP_PROPORTION) {
            if (!expanded) {
                savedProportion = splitter.proportion
                props.setValue(PROPORTION_KEY, savedProportion.toString())
            }
        }

        add(splitter, BorderLayout.CENTER)
    }

    private fun toggleExpand() {
        expanded = !expanded
        if (expanded) {
            savedProportion = splitter.proportion
            splitter.proportion = EXPANDED_PROPORTION // transcript shrinks, terminal near-full
            expandButton.text = ClaudeBundle.message("chatshell.collapseTerminal")
            expandButton.accessibleContext.accessibleName = ClaudeBundle.message("chatshell.collapseTerminal")
        } else {
            splitter.proportion = savedProportion
            expandButton.text = ClaudeBundle.message("chatshell.expandTerminal")
            expandButton.accessibleContext.accessibleName = ClaudeBundle.message("chatshell.expandTerminal")
        }
    }

    private fun restoreProportion(): Float =
        props.getValue(PROPORTION_KEY)?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: DEFAULT_PROPORTION

    companion object {
        private const val PROPORTION_KEY = "prism.chatshell.proportion"
        private const val DEFAULT_PROPORTION = 0.5f
        private const val EXPANDED_PROPORTION = 0.04f
        /** Minimum terminal strip height in unscaled px (~10 rows). Tuned in HITL. */
        private const val MIN_TERMINAL_PX = 180
    }
}
