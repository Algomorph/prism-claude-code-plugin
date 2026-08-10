package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.AgentSession.SessionState
import com.github.vgirotto.prism.services.AgentProcessManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JComponent

class AgentStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Prism"

    override fun createWidget(project: Project): StatusBarWidget =
        AgentStatusBarWidget(project)

    companion object {
        const val WIDGET_ID = "ClaudeCodeStatus"
    }
}

class AgentStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private var statusBar: StatusBar? = null

    private val label = JBLabel(PrismBundle.message("status.starting")).apply {
        border = JBUI.Borders.empty(0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = PrismBundle.message("status.click.tooltip")
    }

    override fun ID(): String = AgentStatusBarWidgetFactory.WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                ToolWindowManager.getInstance(project)
                    .getToolWindow("Prism")
                    ?.activate(null)
                return true
            }
        }.installOn(label)

        AgentProcessManager.getInstance(project).addStateListener {
            updateLabel()
        }
        updateLabel()
    }

    override fun getComponent(): JComponent = label

    override fun dispose() {
        statusBar = null
    }

    private fun updateLabel() {
        val pm = AgentProcessManager.getInstance(project)
        val allSessions = pm.getAllSessions()
        val session = pm.activeSession
        val sessionCount = allSessions.size

        // Model/effort from active session
        val modelPart = session?.model?.ifEmpty { null }
        val effortPart = session?.effort?.ifEmpty { null }

        // Global state: if ANY session is working, show working
        val workingSessions = allSessions.filter { it.state == SessionState.WORKING }
        val startingSessions = allSessions.filter { it.state == SessionState.STARTING }
        val globalState = when {
            workingSessions.isNotEmpty() -> SessionState.WORKING
            startingSessions.isNotEmpty() -> SessionState.STARTING
            allSessions.any { it.state == SessionState.IDLE } -> SessionState.IDLE
            else -> SessionState.STOPPED
        }

        val workingCount = workingSessions.size
        val labelPrefix = PrismBundle.message("status.label.prefix")
        val sessionSuffix = when {
            sessionCount <= 1 -> ""
            workingCount > 0 -> " [$workingCount/$sessionCount ${PrismBundle.message("status.working")}]"
            else -> " [$sessionCount]"
        }

        val (text, color) = when (globalState) {
            SessionState.STOPPED -> "${PrismBundle.message("status.stopped")}$sessionSuffix" to JBColor.GRAY
            SessionState.STARTING -> "${PrismBundle.message("status.starting")}$sessionSuffix" to JBColor.GRAY
            SessionState.WORKING -> {
                val info = listOfNotNull(modelPart, effortPart, PrismBundle.message("status.working"))
                    .joinToString(" \u00b7 ")
                "$labelPrefix $info$sessionSuffix" to JBColor(0xCCAA00, 0xCCAA00)
            }
            SessionState.IDLE -> {
                val info = listOfNotNull(modelPart, effortPart, PrismBundle.message("status.idle"))
                    .joinToString(" \u00b7 ")
                "$labelPrefix $info$sessionSuffix" to JBColor.foreground()
            }
        }

        label.text = text
        label.foreground = color
        label.toolTipText = buildString {
            append(PrismBundle.message("status.tooltip.prefix"))
            if (session != null) append(" — ${session.name}")
            if (modelPart != null) append(", ${PrismBundle.message("status.tooltip.model")} $modelPart")
            if (effortPart != null) append(", ${PrismBundle.message("status.tooltip.effort")} $effortPart")
            if (workingCount > 0) {
                val names = workingSessions.joinToString(", ") { it.name }
                append(", ${PrismBundle.message("status.tooltip.working")} $names")
            }
            append(", ${PrismBundle.message("status.tooltip.status")} ${globalState.name.lowercase()}")
            if (sessionCount > 1) append(" (${PrismBundle.message("status.tooltip.sessions", sessionCount)})")
        }
    }
}
