package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Ties a [TranscriptSource] to the [TranscriptView] via [TranscriptPayloadBuilder]
 * (design §6.3, §6.2, §8). Owns epoch/revision bookkeeping and a mirror of the visible
 * messages so it can (a) apply incremental upserts as the session grows and (b) re-render
 * on resume after a background tab was paused.
 *
 * Background-tab batching (R20): while [pause]d, render deltas are withheld (the source's
 * watcher keeps running so no appends are missed) and coalesced into one full re-render on
 * [resume].
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

    private val mirror = ArrayList<TranscriptMessage>()
    private val mirrorIndex = HashMap<String, Int>()

    @Volatile private var currentEpoch = 0L
    @Volatile private var revision = 0L
    @Volatile private var paused = false
    @Volatile private var pendingFullRender = false
    @Volatile private var subscription: Disposable? = null
    @Volatile private var disposed = false

    init {
        // Render-failure recovery (review #9): if a delta errors or times out in the view,
        // re-emit the whole visible transcript from our authoritative mirror.
        view.onRecoveryNeeded = {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) renderFull()
            }
        }
        // In-place theme sync (design §10): re-derive + patch on LaF or scheme change.
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        conn.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC,
            com.intellij.ide.ui.LafManagerListener { pushTheme() })
        conn.subscribe(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC,
            com.intellij.openapi.editor.colors.EditorColorsListener { pushTheme() })
    }

    private fun pushTheme() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) view.setTheme(ChatShellTheme.currentVars())
        }
    }

    /** Attach a live tailing source for [conversationId]. */
    fun attachLive(conversationId: String) {
        if (disposed) return
        val file = resolver.transcriptFile(conversationId) ?: return
        subscription?.dispose()
        subscription = LiveTranscriptSource(file).subscribe { state -> onState(state) }
    }

    /** One-shot static render (Group 4 behavior; used where live tailing isn't wanted). */
    fun renderStatic(conversationId: String) {
        if (disposed) return
        val file = resolver.transcriptFile(conversationId)
        subscription?.dispose()
        subscription = StaticTranscriptSource(file).subscribe { state -> onState(state) }
    }

    fun pause() { paused = true }

    fun resume() {
        paused = false
        if (pendingFullRender) {
            pendingFullRender = false
            renderFull()
        }
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
                    currentEpoch = state.epoch
                    resetMirror(state.page.messages)
                    if (paused) pendingFullRender = true else renderFull()
                }
                is TranscriptState.Appended -> {
                    if (state.epoch != currentEpoch) return@invokeLater
                    mergeMirror(state.messages)
                    if (paused) { pendingFullRender = true; return@invokeLater }
                    val ops = state.messages.filter { it.isRenderable }.map { builder.upsertOp(it) }
                    if (ops.isNotEmpty()) {
                        view.applyDelta(TranscriptDelta(currentEpoch, nextRevision(), ops))
                    }
                }
            }
        }
    }

    private fun renderFull() {
        view.applyDelta(builder.buildDelta(mirror, currentEpoch, nextRevision()))
    }

    private fun resetMirror(messages: List<TranscriptMessage>) {
        mirror.clear(); mirrorIndex.clear()
        for (m in messages) { mirrorIndex[m.id] = mirror.size; mirror.add(m) }
    }

    private fun mergeMirror(messages: List<TranscriptMessage>) {
        for (m in messages) {
            val idx = mirrorIndex[m.id]
            if (idx != null) mirror[idx] = m else { mirrorIndex[m.id] = mirror.size; mirror.add(m) }
        }
    }

    private fun nextRevision(): Long = ++revision

    override fun dispose() {
        disposed = true
        subscription?.dispose()
        subscription = null
    }
}
