package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Ties a [TranscriptSource] to the [TranscriptView] via [TranscriptPayloadBuilder]
 * (design §6.3, §6.2). Group 4 does a one-shot static render of the resolved transcript
 * file; Group 5 swaps in a live tailing source behind the same contract.
 */
class TranscriptController(
    private val project: Project,
    private val view: TranscriptView,
) : Disposable {

    private val log = Logger.getInstance(TranscriptController::class.java)
    private val resolver = SessionResolver(project.basePath)
    private val builder = TranscriptPayloadBuilder(
        allowedImageRoots = listOfNotNull(project.basePath?.let { File(it) })
    )

    @Volatile private var revision = 0L
    @Volatile private var subscription: Disposable? = null
    @Volatile private var disposed = false

    /** Render the current transcript for [conversationId] once (static; Group 4). */
    fun renderStatic(conversationId: String) {
        if (disposed) return
        val file = resolver.transcriptFile(conversationId)
        subscription?.let { it.dispose() }
        subscription = StaticTranscriptSource(file).subscribe { state -> onState(state) }
    }

    private fun onState(state: TranscriptState) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            when (state) {
                is TranscriptState.Loading -> view.setState(TranscriptView.State.LOADING)
                is TranscriptState.NoTranscriptYet -> view.setState(TranscriptView.State.NO_TRANSCRIPT_YET)
                is TranscriptState.Reconnecting -> view.setState(TranscriptView.State.RECONNECTING)
                is TranscriptState.Error -> {
                    log.warn("Transcript source error", state.error)
                    view.setState(TranscriptView.State.ERROR)
                }
                is TranscriptState.Ready -> {
                    val delta = builder.buildDelta(state.page.messages, epoch = 0, revision = nextRevision())
                    view.applyDelta(delta)
                }
                is TranscriptState.Delta -> {
                    view.applyDelta(TranscriptDelta(state.epoch, state.revision, state.ops))
                }
            }
        }
    }

    private fun nextRevision(): Long = ++revision

    override fun dispose() {
        disposed = true
        subscription?.dispose()
        subscription = null
    }
}
