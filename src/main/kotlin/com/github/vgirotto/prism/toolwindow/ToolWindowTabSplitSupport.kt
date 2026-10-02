package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentManager
import java.awt.Component

internal enum class SplitDirection { RIGHT, DOWN, UNSPLIT }
internal enum class SplitStrategyKind { MODERN, LEGACY, UNAVAILABLE }

/** A direction only makes sense together with the support that can carry it out; pass both or neither. */
internal data class SplitRequest(val direction: SplitDirection, val support: ToolWindowTabSplitSupport)

internal fun selectSplitStrategy(hasModernApi: Boolean, hasLegacyActions: Boolean): SplitStrategyKind = when {
    hasModernApi -> SplitStrategyKind.MODERN
    hasLegacyActions -> SplitStrategyKind.LEGACY
    else -> SplitStrategyKind.UNAVAILABLE
}

internal fun modernSplitActionId(direction: SplitDirection): String = when (direction) {
    SplitDirection.RIGHT -> "TW.SplitAndMoveRight"
    SplitDirection.DOWN -> "TW.SplitAndMoveDown"
    SplitDirection.UNSPLIT -> "TW.Unsplit"
}

/**
 * Runtime bridge for ToolWindow tab splitting.
 *
 * Prism compiles against build 243, where the public ToolWindow split API does
 * not exist. Fixed reflective lookups keep one artifact compatible while newer
 * IDEs use their current public actions and older IDEs use the legacy action
 * implementation already shipped by the platform.
 */
internal class ToolWindowTabSplitSupport(private val toolWindow: ToolWindow) {
    private val log = Logger.getInstance(ToolWindowTabSplitSupport::class.java)
    private val modernApi = findModernEnableMethod()
    private val legacySplitClass = findClass(LEGACY_SPLIT_ACTION)
    private val legacyUnsplitClass = findClass(LEGACY_UNSPLIT_ACTION)

    val strategy: SplitStrategyKind = selectSplitStrategy(
        hasModernApi = modernApi != null && SplitDirection.values().all { modernAction(it) != null },
        hasLegacyActions = legacySplitClass != null && legacyUnsplitClass != null,
    )

    init {
        if (strategy == SplitStrategyKind.MODERN) {
            runCatching { modernApi?.invoke(toolWindow, true) }
                .onFailure { log.warn("Failed to enable modern ToolWindow tab splitting", it) }
        }
        log.info("ToolWindow tab split strategy: $strategy")
    }

    fun isAvailable(): Boolean = SplitDirection.values().all(::isAvailable)

    fun isAvailable(direction: SplitDirection): Boolean = when (strategy) {
        SplitStrategyKind.MODERN -> modernAction(direction) != null
        SplitStrategyKind.LEGACY -> when (direction) {
            SplitDirection.RIGHT, SplitDirection.DOWN -> legacySplitClass != null
            SplitDirection.UNSPLIT -> legacyUnsplitClass != null
        }
        SplitStrategyKind.UNAVAILABLE -> false
    }

    fun perform(direction: SplitDirection, manager: ContentManager, context: Component): Boolean {
        val action = when (strategy) {
            SplitStrategyKind.MODERN -> modernAction(direction)
            SplitStrategyKind.LEGACY -> legacyAction(direction, manager)
            SplitStrategyKind.UNAVAILABLE -> null
        } ?: return false

        return runCatching {
            val callback = ActionManager.getInstance().tryToExecute(
                action,
                null,
                context,
                ActionPlaces.TOOLWINDOW_TITLE,
                true,
            )
            !callback.isRejected
        }.onFailure {
            log.warn("ToolWindow tab split action failed: $direction", it)
        }.getOrDefault(false)
    }

    private fun modernAction(direction: SplitDirection): AnAction? =
        ActionManager.getInstance().getAction(modernSplitActionId(direction))

    private fun legacyAction(direction: SplitDirection, manager: ContentManager): AnAction? = runCatching {
        when (direction) {
            SplitDirection.RIGHT, SplitDirection.DOWN -> legacySplitClass
                ?.getConstructor(ContentManager::class.java, Boolean::class.javaPrimitiveType)
                ?.newInstance(manager, direction == SplitDirection.RIGHT) as? AnAction
            SplitDirection.UNSPLIT -> legacyUnsplitClass
                ?.getConstructor(ContentManager::class.java)
                ?.newInstance(manager) as? AnAction
        }
    }.onFailure {
        log.warn("Failed to create legacy ToolWindow split action: $direction", it)
    }.getOrNull()

    private fun findModernEnableMethod() = runCatching {
        toolWindow.javaClass.methods.firstOrNull {
            it.name == "setTabsSplittingAllowed" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
    }.getOrNull()

    private fun findClass(name: String): Class<*>? = runCatching {
        Class.forName(name, false, javaClass.classLoader)
    }.getOrNull()

    private companion object {
        const val LEGACY_SPLIT_ACTION = "com.intellij.ui.content.tabs.TabbedContentAction\$SplitTabAction"
        const val LEGACY_UNSPLIT_ACTION = "com.intellij.ui.content.tabs.TabbedContentAction\$UnsplitTabAction"
    }
}
