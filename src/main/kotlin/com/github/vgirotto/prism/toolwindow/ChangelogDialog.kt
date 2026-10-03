package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.changelog.ChangelogContent
import com.github.vgirotto.prism.changelog.ChangelogResult
import com.github.vgirotto.prism.changelog.isExternalChangelogLink
import com.github.vgirotto.prism.i18n.PrismBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import java.util.concurrent.Future
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent

internal class ChangelogDialog(private val project: Project) : DialogWrapper(project, false) {

    private val contentPanel = JPanel(BorderLayout()).apply {
        preferredSize = JBUI.size(760, 600)
    }

    private val editorPane = createEditorPane()
    private var loadTask: Future<*>? = null
    private var closed = false

    init {
        title = PrismBundle.message("changelog.title")
        setModal(false)
        isResizable = true
        setCancelButtonText(PrismBundle.message("changelog.close"))
        showMessage(PrismBundle.message("changelog.loading"))
        init()
        loadContent()
    }

    override fun createCenterPanel(): JComponent = contentPanel

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = editorPane

    override fun getDimensionServiceKey(): String = "Prism.ChangelogDialog"

    override fun dispose() {
        closed = true
        loadTask?.cancel(true)
        super.dispose()
    }

    private fun loadContent() {
        val locale = PrismBundle.getLocale()
        val application = ApplicationManager.getApplication()

        loadTask = application.executeOnPooledThread {
            val result = ChangelogContent().load(locale)
            application.invokeLater({
                if (!closed && !project.isDisposed) {
                    showContent(result)
                }
            }, ModalityState.any())
        }
    }

    private fun createEditorPane(): JEditorPane = JEditorPane().apply {
        isEditable = false
        font = UIUtil.getLabelFont()
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        border = JBUI.Borders.empty(12)
        editorKit = createChangelogEditorKit(
            foreground,
            JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6)),
        )

        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val destination = event.description.orEmpty()
                if (isExternalChangelogLink(destination)) {
                    BrowserUtil.browse(destination)
                }
            }
        }
    }

    private fun showContent(result: ChangelogResult) {
        contentPanel.removeAll()

        when (result) {
            is ChangelogResult.Rendered -> {
                editorPane.text = "<html><body>${result.html}</body></html>"
                showDocument()
            }

            is ChangelogResult.PlainText -> {
                editorPane.contentType = "text/plain"
                editorPane.font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                editorPane.text = result.markdown
                contentPanel.add(JBLabel(PrismBundle.message("changelog.plain.text")).apply {
                    border = JBUI.Borders.empty(8)
                }, BorderLayout.NORTH)
                showDocument()
            }

            ChangelogResult.Unavailable -> showMessage(PrismBundle.message("changelog.unavailable"))
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun showDocument() {
        editorPane.caretPosition = 0
        contentPanel.add(JBScrollPane(editorPane).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
    }

    private fun showMessage(message: String) {
        contentPanel.add(JBLabel(message, SwingConstants.CENTER), BorderLayout.CENTER)
    }
}
