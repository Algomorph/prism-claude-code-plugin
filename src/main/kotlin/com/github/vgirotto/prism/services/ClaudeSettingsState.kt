package com.github.vgirotto.prism.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "ClaudeCodeWrapperSettings",
    storages = [Storage("ClaudeCodeWrapper.xml")]
)
class ClaudeSettingsState : PersistentStateComponent<ClaudeSettingsState.State> {

    data class State(
        var claudePath: String = "claude",
        var autoStartOnOpen: Boolean = true,
        var shellPath: String = System.getenv("SHELL") ?: "/bin/zsh",
        var showChangesOnStartup: Boolean = true,
        var showStatusBarWidget: Boolean = true,
        var excludedPatterns: String = ".git,node_modules,build,out,.gradle,.idea,target,dist,.next,__pycache__,.venv,vendor,.intellijPlatform,.DS_Store,.cls,.cache",
        var maxFileSizeKb: Int = 512,
        var language: String = "en",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var claudePath: String
        get() = state.claudePath
        set(value) { state.claudePath = value }

    var autoStartOnOpen: Boolean
        get() = state.autoStartOnOpen
        set(value) { state.autoStartOnOpen = value }

    var shellPath: String
        get() = state.shellPath
        set(value) { state.shellPath = value }

    var showChangesOnStartup: Boolean
        get() = state.showChangesOnStartup
        set(value) { state.showChangesOnStartup = value }

    var showStatusBarWidget: Boolean
        get() = state.showStatusBarWidget
        set(value) { state.showStatusBarWidget = value }

    var excludedPatterns: String
        get() = state.excludedPatterns
        set(value) { state.excludedPatterns = value }

    var maxFileSizeKb: Int
        get() = state.maxFileSizeKb
        set(value) { state.maxFileSizeKb = value }

    var language: String
        get() = state.language
        set(value) { state.language = value }

    fun getExcludedPatterns(): List<String> =
        excludedPatterns.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getExcludedDirSet(): Set<String> =
        getExcludedPatterns().toSet()

    companion object {
        fun getInstance(): ClaudeSettingsState =
            ApplicationManager.getApplication().getService(ClaudeSettingsState::class.java)
    }
}
