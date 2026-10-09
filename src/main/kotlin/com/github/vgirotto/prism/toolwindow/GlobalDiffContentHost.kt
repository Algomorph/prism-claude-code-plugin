package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.model.InteractionDiff
import com.github.vgirotto.prism.services.AgentProcessManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

internal fun globalDiffSplitDirection(anchor: ToolWindowAnchor): SplitDirection =
    if (anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.RIGHT) {
        SplitDirection.DOWN
    } else {
        SplitDirection.RIGHT
    }

/**
 * Resolves the chat a session action should act on (see [chooseSessionContent]). Shared by
 * [GlobalDiffContentHost] and [AgentToolWindowFactory] so the two callers can't drift out of sync
 * the way they previously did.
 */
internal fun resolveActiveSessionContent(project: Project, toolWindow: ToolWindow): Content? =
    chooseSessionContent(
        toolWindow.contentManager.contentsRecursively,
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
        AgentProcessManager.getInstance(project).activeSessionId,
        toolWindow.contentManager.selectedContent,
    )

/**
 * The chat among [contents] that holds keyboard focus ([focusOwner]), else the one running session
 * [activeSessionId], else [selected], else the first. Only chats count, a chat still starting
 * included: never History, the global Diff or an error panel, even when one of them has focus.
 */
internal fun chooseSessionContent(
    contents: List<Content>,
    focusOwner: Component?,
    activeSessionId: String?,
    selected: Content?,
): Content? {
    val chats = contents.filter(AgentToolWindowFactory::isChat)
    return chats.firstOrNull { holdsFocus(it, focusOwner) }
        ?: activeSessionId?.let { id -> chats.firstOrNull { it.getUserData(AgentToolWindowFactory.SESSION_ID_KEY) == id } }
        ?: selected?.takeIf(AgentToolWindowFactory::isChat)
        ?: chats.firstOrNull()
}

/** The content, other than the global Diff, that holds keyboard focus, if any. */
internal fun resolveFocusedContent(toolWindow: ToolWindow): Content? {
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    return toolWindow.contentManager.contentsRecursively
        .filterNot(GlobalDiffContentHost::isGlobalDiff)
        .firstOrNull { holdsFocus(it, focusOwner) }
}

private fun holdsFocus(content: Content, focusOwner: Component?): Boolean =
    focusOwner != null &&
        (focusOwner === content.component || SwingUtilities.isDescendingFrom(focusOwner, content.component))

/**
 * Owns the single project-wide Diff UI and presents it as an independent
 * ToolWindow content split. Session contents never own or duplicate this panel.
 */
internal class GlobalDiffContentHost(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val splitSupport: ToolWindowTabSplitSupport,
) {
    private val diffPanel = DiffPanel(project) { clearAndReset() }
    private var hasCreatedSession = false
    private val content = toolWindow.contentManager.factory.createContent(
        diffPanel,
        PrismBundle.message("toolwindow.tab.changes"),
        false,
    ).apply {
        isCloseable = false
        putUserData(CONTENT_KEY, true)
    }

    fun isVisible(): Boolean = content.manager != null

    fun show(): Boolean = show(findActiveSessionContent())

    fun show(anchorContent: Content?): Boolean {
        content.manager?.let {
            it.setSelectedContent(content)
            return true
        }

        val targetManager = anchorContent?.manager ?: toolWindow.contentManager
        if (targetManager.isDisposed) return false

        targetManager.addContent(content)
        targetManager.setSelectedContent(content)

        if (splitFrom(targetManager)) return true

        targetManager.removeContent(content, false)
        return false
    }

    fun sessionCreated(sessionContent: Content, showOnStartup: Boolean) {
        val isFirstSession = !hasCreatedSession
        hasCreatedSession = true

        val diffManager = content.manager
        if (diffManager != null && diffManager === sessionContent.manager && diffManager.contentCount > 1) {
            diffManager.setSelectedContent(content)
            splitFrom(diffManager)
        } else if (isFirstSession && showOnStartup) {
            show(sessionContent)
        }
    }

    fun hide() {
        content.manager?.removeContent(content, false)
    }

    fun refreshDiff() = diffPanel.refreshDiff()

    fun showDiff(diff: InteractionDiff) = diffPanel.showDiff(diff)

    private fun clearAndReset(): Unit = diffPanel.clearAndReset()

    private fun splitFrom(manager: ContentManager): Boolean =
        splitSupport.perform(globalDiffSplitDirection(toolWindow.anchor), manager, diffPanel)

    private fun findActiveSessionContent(): Content? = resolveActiveSessionContent(project, toolWindow)

    companion object {
        private val CONTENT_KEY = Key.create<Boolean>("PrismGlobalDiffContent")
        private const val HOST_PROPERTY = "com.github.vgirotto.prism.globalDiffContentHost"

        fun isGlobalDiff(content: Content): Boolean = content.getUserData(CONTENT_KEY) == true

        fun install(
            project: Project,
            toolWindow: ToolWindow,
            splitSupport: ToolWindowTabSplitSupport,
        ): GlobalDiffContentHost {
            get(toolWindow)?.let { return it }
            return GlobalDiffContentHost(project, toolWindow, splitSupport).also {
                toolWindow.component.putClientProperty(HOST_PROPERTY, it)
            }
        }

        fun get(toolWindow: ToolWindow): GlobalDiffContentHost? =
            toolWindow.component.getClientProperty(HOST_PROPERTY) as? GlobalDiffContentHost
    }
}
