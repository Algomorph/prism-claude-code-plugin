package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.i18n.PrismBundle
import com.github.vgirotto.prism.services.AgentProcessManager
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

    /**
     * The `--session-id` this chat was launched with — the stable id [SiblingTranscriptWatcher]
     * matches on to follow a `/resume` (it appears in every content line as snake-case
     * `session_id`, even after the conversation file changes). Captured on first [attachLive].
     */
    @Volatile private var launchSessionId: String? = null

    /**
     * While true, a Prism-initiated `/resume` is in flight for this chat and the resumed
     * conversation is not yet attributable to us (no per-chat id lands in the transcript file
     * until the first post-resume turn). We show a "syncing on next message" hint and suppress
     * rendering the now-abandoned launch file so the pane doesn't flash empty. Cleared when real
     * content arrives (rebind or the current file gaining renderable messages) or on timeout.
     */
    @Volatile private var awaitingResume = false
    @Volatile private var resumeHintTimeout: ScheduledFuture<*>? = null
    private var resumeUnsub: (() -> Unit)? = null

    init {
        // A Prism-initiated /resume for this chat raises the "syncing" hint (see [showResumeHint]).
        resumeUnsub = AgentProcessManager.getInstance(project).addResumeListener { sid ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && sid == launchSessionId) showResumeHint()
            }
        }
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
        // The first conversation we attach to is our launch --session-id; the sibling watcher
        // follows a /resume by that stable id, so it must never be overwritten by a rebind.
        if (launchSessionId == null) launchSessionId = conversationId
        boundConvId = conversationId
        subscription?.dispose()
        subscription = LiveTranscriptSource(file).subscribe { state -> onState(state) }
        ensureSiblingWatcher(file.parentFile)
        // Opened while a Prism /resume is mid-flight (clicked Resume, then showed the transcript):
        // pick up the pending hint the live listener above would have missed.
        if (AgentProcessManager.getInstance(project).getSession(conversationId)?.resumePending == true) {
            showResumeHint()
        }
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
        // The resume completed and is now attributable to us; the incoming stream carries the
        // resumed history, so stand the hint down (its render clears the banner).
        clearResumeHint(revert = false)
        boundConvId = conversationId
        subscription?.dispose()
        mirror.clear(); mirrorIndex.clear()
        sourceEpoch = -1L
        subscription = LiveTranscriptSource(file).subscribe { state -> onState(state) }
    }

    /** Show the "resumed — syncing on next message" hint until real content arrives (or timeout). */
    private fun showResumeHint() {
        if (disposed) return
        awaitingResume = true
        view.setState(TranscriptView.State.RESUMING)
        resumeHintTimeout?.cancel(false)
        // Safety net: a cancelled resume picker never produces content, so don't strand the hint.
        resumeHintTimeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater { clearResumeHint(revert = true) }
        }, RESUME_HINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun clearResumeHint(revert: Boolean) {
        resumeHintTimeout?.cancel(false); resumeHintTimeout = null
        if (!awaitingResume) return
        awaitingResume = false
        launchSessionId?.let { AgentProcessManager.getInstance(project).getSession(it)?.resumePending = false }
        if (revert && !disposed) {
            // Nothing arrived (e.g. picker cancelled): fall back to the current stream's state.
            if (mirror.any { it.isRenderable }) renderFull()
            else view.setState(TranscriptView.State.NO_TRANSCRIPT_YET)
        }
    }

    private fun ensureSiblingWatcher(dir: File?) {
        if (dir == null || siblingWatcher != null) return
        val launch = launchSessionId ?: return
        val watcher = SiblingTranscriptWatcher(
            dir = dir,
            launchSessionId = launch,
            boundConvId = { boundConvId ?: "" },
            // Runs regardless of which tab is visible: a background chat's own /resume must still
            // be tracked so it renders correctly when shown. The session_id match guarantees this
            // watcher only ever follows *its own* chat, so no active-tab gating is needed.
            isActive = { !disposed },
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
            // While a resume hint is up, the still-attached (now abandoned) launch file must not
            // flash its empty placeholder states over the banner; only real content stands it down.
            when (state) {
                is TranscriptState.Loading ->
                    if (!awaitingResume) view.setState(TranscriptView.State.LOADING)
                is TranscriptState.NoTranscriptYet ->
                    if (!awaitingResume) view.setState(TranscriptView.State.NO_TRANSCRIPT_YET)
                is TranscriptState.Reconnecting ->
                    if (!awaitingResume) view.setState(TranscriptView.State.RECONNECTING)
                is TranscriptState.Error -> {
                    log.warn("Transcript source error", state.error)
                    if (!awaitingResume) view.setState(TranscriptView.State.ERROR)
                }
                is TranscriptState.Ready -> {
                    sourceEpoch = state.epoch
                    viewEpoch++ // fresh view stream: first attach, rotation, or rebind
                    resetMirror(state.page.messages)
                    if (awaitingResume && mirror.none { it.isRenderable }) return@invokeLater
                    clearResumeHint(revert = false)
                    if (paused) pendingFullRender = true else renderFull()
                }
                is TranscriptState.Appended -> {
                    if (state.epoch != sourceEpoch) return@invokeLater
                    mergeMirror(state.messages)
                    if (awaitingResume && state.messages.none { it.isRenderable }) return@invokeLater
                    clearResumeHint(revert = false)
                    if (paused) { pendingFullRender = true; return@invokeLater }
                    // The plain-text fallback has no DOM to patch incrementally; re-emit the
                    // whole mirror instead (setting a text area is cheap).
                    if (!view.isJcef) { renderFull(); return@invokeLater }
                    val ops = state.messages.filter { it.isRenderable }.map { builder.upsertOp(it) }
                    if (ops.isNotEmpty()) {
                        view.applyDelta(TranscriptDelta(viewEpoch, nextRevision(), ops))
                    }
                }
            }
        }
    }

    /**
     * Emit the whole visible transcript. IDEs without an embedded browser (Android Studio ships
     * the JCEF bindings but not the native library) render into a text area instead, where a
     * delta means nothing — route those to the plain-text renderer or the transcript never
     * appears at all.
     */
    private fun renderFull() {
        if (view.isJcef) {
            view.applyDelta(builder.buildDelta(mirror, viewEpoch, nextRevision()))
        } else {
            view.setFallbackText(TranscriptPlainTextRenderer.render(mirror, plainTextLabels()))
        }
    }

    private fun plainTextLabels() = TranscriptPlainTextRenderer.Labels(
        thinking = PrismBundle.message("chatshell.disclosure.thinking"),
        output = PrismBundle.message("chatshell.disclosure.output"),
        blockedImage = PrismBundle.message("chatshell.blockedImage"),
        unsupported = PrismBundle.message("chatshell.unsupported"),
    )

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
        resumeHintTimeout?.cancel(false); resumeHintTimeout = null
        resumeUnsub?.invoke(); resumeUnsub = null
        subscription?.dispose()
        subscription = null
    }

    private companion object {
        /** How long the resume hint lingers before assuming the resume was cancelled. */
        const val RESUME_HINT_TIMEOUT_MS = 60_000L
    }
}
