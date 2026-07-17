package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

    // viewEpoch drives the view + deltas and bumps on every stream boundary (attach, rotation,
    // rebind), so a rebind is a clean reset the browser can't confuse with the old stream.
    // sourceEpoch is the tailing source's own epoch, used only to match its Appended emissions.
    @Volatile private var viewEpoch = 0L
    @Volatile private var sourceEpoch = -1L
    @Volatile private var revision = 0L
    @Volatile private var paused = false
    @Volatile private var pendingFullRender = false
    @Volatile private var subscription: Disposable? = null
    @Volatile private var disposed = false

    /** The conversation the transcript is currently bound to (mutable across `/resume`, R2). */
    @Volatile private var boundConvId: String? = null
    @Volatile private var siblingWatcher: SiblingTranscriptWatcher? = null

    // Codex rollout binding (design §11): the path is discovered, not deterministic, so a
    // background poller resolves the newest matching rollout and (re)binds when it changes.
    @Volatile private var codexPoller: ScheduledFuture<*>? = null
    @Volatile private var boundFilePath: String? = null

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

    /** Attach a live tailing source for [conversationId], and watch for a `/resume` switch. */
    fun attachLive(conversationId: String) {
        if (disposed) return
        val file = resolver.transcriptFile(conversationId) ?: return
        boundConvId = conversationId
        subscription?.dispose()
        subscription = LiveTranscriptSource(file).subscribe { state -> onState(state) }
        ensureSiblingWatcher(file.parentFile)
    }

    /**
     * Rebind the transcript to a conversation the user switched to via `/resume` (§9). Treated
     * as a fresh stream: the source is re-attached, the mirror cleared, and [viewEpoch] bumped
     * so the browser resets rather than merging into the old conversation.
     */
    private fun rebind(conversationId: String) {
        if (disposed || conversationId == boundConvId) return
        val file = resolver.transcriptFile(conversationId) ?: return
        log.info("Transcript rebinding to resumed conversation $conversationId")
        boundConvId = conversationId
        subscription?.dispose()
        mirror.clear(); mirrorIndex.clear()
        sourceEpoch = -1L
        subscription = LiveTranscriptSource(file).subscribe { state -> onState(state) }
    }

    private fun ensureSiblingWatcher(dir: File?) {
        if (dir == null || siblingWatcher != null) return
        val watcher = SiblingTranscriptWatcher(
            dir = dir,
            boundConvId = { boundConvId ?: "" },
            isActive = { !paused && !disposed },
            onSwitch = { convId ->
                ApplicationManager.getApplication().invokeLater { if (!disposed) rebind(convId) }
            },
        ).start()
        siblingWatcher = watcher
        Disposer.register(this, watcher)
    }

    /**
     * Attach a live tailing source for the current project's Codex session (design §11). Codex
     * supplies no session id, so we poll for the newest rollout file whose `cwd` matches the
     * project and bind to it; the same poll rebinds when a newer rollout appears (new session or
     * a native resume). Uses the [CodexTranscriptParser] rather than the Claude schema.
     */
    fun attachLiveCodex() {
        if (disposed || codexPoller != null) return
        val codexResolver = CodexSessionResolver(project.basePath)
        codexPoller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                try {
                    if (disposed) return@scheduleWithFixedDelay
                    val newest = codexResolver.newestForProject() ?: return@scheduleWithFixedDelay
                    if (newest.absolutePath != boundFilePath) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed) bindCodexFile(newest)
                        }
                    }
                } catch (_: Exception) {
                    // Resolution is best-effort; a transient FS error just retries next tick.
                }
            },
            0, 500, TimeUnit.MILLISECONDS
        )
    }

    /** (Re)bind the live source to a resolved Codex rollout file as a fresh stream. */
    private fun bindCodexFile(file: File) {
        if (disposed || file.absolutePath == boundFilePath) return
        log.info("Transcript binding to Codex rollout ${file.name}")
        boundFilePath = file.absolutePath
        subscription?.dispose()
        mirror.clear(); mirrorIndex.clear()
        sourceEpoch = -1L
        subscription = LiveTranscriptSource(file, CodexTranscriptParser()).subscribe { state -> onState(state) }
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
                    sourceEpoch = state.epoch
                    viewEpoch++ // fresh view stream: first attach, rotation, or rebind
                    resetMirror(state.page.messages)
                    if (paused) pendingFullRender = true else renderFull()
                }
                is TranscriptState.Appended -> {
                    if (state.epoch != sourceEpoch) return@invokeLater
                    mergeMirror(state.messages)
                    if (paused) { pendingFullRender = true; return@invokeLater }
                    val ops = state.messages.filter { it.isRenderable }.map { builder.upsertOp(it) }
                    if (ops.isNotEmpty()) {
                        view.applyDelta(TranscriptDelta(viewEpoch, nextRevision(), ops))
                    }
                }
            }
        }
    }

    private fun renderFull() {
        view.applyDelta(builder.buildDelta(mirror, viewEpoch, nextRevision()))
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
        codexPoller?.cancel(false)
        codexPoller = null
        subscription?.dispose()
        subscription = null
    }
}
