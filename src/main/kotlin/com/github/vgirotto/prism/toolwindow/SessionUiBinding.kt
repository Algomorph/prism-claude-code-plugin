package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.services.AgentProcessManager
import com.intellij.openapi.project.Project

internal class SessionLifecycle {
    enum class State { CREATING, ATTACHED, DISPOSED }

    private var state = State.CREATING
    private var sessionId: String? = null

    @Synchronized
    fun attach(id: String): Boolean {
        if (state != State.CREATING) return false
        sessionId = id
        state = State.ATTACHED
        return true
    }

    @Synchronized
    fun dispose(): String? {
        if (state == State.DISPOSED) return null
        state = State.DISPOSED
        return sessionId
    }

    @Synchronized
    fun isAttached(id: String): Boolean = state == State.ATTACHED && sessionId == id

    @Synchronized
    fun id(): String? = sessionId

    @Synchronized
    fun state(): State = state
}

internal class SessionUiBinding(
    private val project: Project,
    val cli: AgentCli,
) {
    private val lifecycle = SessionLifecycle()
    private val manager: AgentProcessManager get() = AgentProcessManager.getInstance(project)

    val sessionId: String? get() = lifecycle.id()
    val session: AgentSession? get() = sessionId?.let(manager::getSession)

    fun attach(sessionId: String): Boolean = lifecycle.attach(sessionId)
    fun isAttached(sessionId: String): Boolean = lifecycle.isAttached(sessionId)
    fun dispose(): String? = lifecycle.dispose()

    fun activate() {
        sessionId?.let(manager::setActiveSession)
    }

    fun sendText(text: String) = manager.sendText(text, sessionId)
    fun sendSequence(chunks: List<String>) = manager.sendSequence(chunks, sessionId)
    fun setEffort(effort: String) = manager.setSessionEffort(effort, sessionId)
}
