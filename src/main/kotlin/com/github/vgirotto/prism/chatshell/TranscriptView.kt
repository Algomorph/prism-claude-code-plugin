package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The rendered transcript pane (design §6.1). A persistent JCEF shell loaded **once** and
 * patched incrementally via base64 [TranscriptDelta]s; content safety lives in `shell.js`
 * (§6.8). Falls back to a read-only Swing text area when JCEF is unavailable (R3).
 *
 * The JCEF browser is created **lazily** ([initialize]) so opening many tabs does not spin
 * up a browser + DOM + libs per tab until a tab is actually shown (R20, multi-tab cost).
 * Full `loadHTML` is recovery-only (epoch reset / corruption).
 */
class TranscriptView(private val parentDisposable: Disposable) : Disposable {

    enum class State { LOADING, NO_TRANSCRIPT_YET, READY, RECONNECTING, ERROR, UNAVAILABLE }

    private val log = Logger.getInstance(TranscriptView::class.java)
    private val supported = JBCefApp.isSupported()

    private val root = JPanel(BorderLayout())

    // Swing fallback.
    private val fallbackArea = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
    }
    private val fallbackScroll = JBScrollPane(fallbackArea)

    // JCEF (built lazily in initialize()).
    private var browser: JBCefBrowser? = null
    private var ackQuery: JBCefJSQuery? = null
    private var linkQuery: JBCefJSQuery? = null
    private var copyQuery: JBCefJSQuery? = null

    @Volatile private var shellLoaded = false
    @Volatile private var disposed = false
    @Volatile private var initialized = false
    @Volatile private var currentEpoch = 0L

    /** External resource requests the interceptor blocked — asserted == 0 by tests. */
    val externalRequestCount = AtomicInteger(0)

    /** Revision of the last successfully-rendered delta (ack after render+layout). Lets a
     *  browser test wait for a real render instead of a blind sleep (review #10). */
    @Volatile
    var lastAckRevisionForTest: Long = -1L
        private set

    @Volatile var state: State = State.LOADING
        private set

    var onOpenLink: (String) -> Unit = {}

    /** Invoked when a delta render fails or times out — the controller re-renders from its
     *  authoritative mirror so a dropped/errored delta can't leave stale DOM (review #9). */
    var onRecoveryNeeded: () -> Unit = {}

    private val queue = ArrayDeque<TranscriptDelta>()
    private var inFlight: TranscriptDelta? = null
    @Volatile private var recovering = false
    @Volatile private var pendingStatusB64: String? = null
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    init {
        Disposer.register(parentDisposable, this)
        if (!supported) {
            root.add(fallbackScroll, BorderLayout.CENTER)
            state = State.READY
        }
    }

    val component: JComponent get() = root
    val isJcef: Boolean get() = supported

    /**
     * Build the browser + load the shell on first use (lazy, R20). Idempotent. No-op when
     * JCEF is unavailable (the Swing fallback is already in place).
     */
    fun initialize(theme: Map<String, String> = emptyMap()) {
        if (disposed || !supported || initialized) return
        initialized = true
        val b = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
        browser = b
        Disposer.register(this, b)
        installHandlers(b)
        root.add(b.component, BorderLayout.CENTER)
        root.revalidate()
        loadShell(theme)
    }

    /** Load the shell HTML once. Also the recovery/reset path (schema reset, corruption). */
    fun loadShell(theme: Map<String, String> = emptyMap()) {
        val b = browser ?: return
        state = State.LOADING
        shellLoaded = false
        b.loadHTML(ShellHtmlBuilder.build(theme), ShellHtmlBuilder.shellUrl)
    }

    fun applyDelta(delta: TranscriptDelta) {
        if (disposed || browser == null) return
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            if (delta.epoch != currentEpoch) {
                currentEpoch = delta.epoch
                queue.clear()
                inFlight = null
            }
            queue.addLast(delta)
            pump()
        }
    }

    private fun pump() {
        if (disposed || inFlight != null) return
        val next = queue.pollFirst() ?: return
        if (!shellLoaded) { queue.addFirst(next); return }
        inFlight = next
        val b64 = TranscriptCodec.encodeDelta(next)
        browser?.cefBrowser?.executeJavaScript(
            "window.__prismApplyDelta(\"$b64\");", ShellHtmlBuilder.shellUrl, 0
        )
        state = State.READY
        val expected = next
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && inFlight === expected) {
                    log.warn("Ack timeout epoch=${expected.epoch} rev=${expected.revision} — recovering")
                    triggerRecovery()
                }
            }
        }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onAck(raw: String) {
        val ack = TranscriptCodec.decodeAck(raw) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || ack.epoch != currentEpoch) return@invokeLater
            val cur = inFlight
            if (cur == null || cur.epoch != ack.epoch || cur.revision != ack.revision) return@invokeLater
            if (ack.status != "ok") {
                log.warn("Render error ack epoch=${ack.epoch} rev=${ack.revision} status=${ack.status} — recovering")
                triggerRecovery()
                return@invokeLater
            }
            recovering = false
            lastAckRevisionForTest = ack.revision
            inFlight = null; pump()
        }
    }

    /**
     * A delta failed to render (JS error) or never acked (timeout). Drop the pending work
     * and ask the controller for a full reset+rebuild. The [recovering] latch prevents an
     * infinite reset loop if the recovery render itself keeps failing — it clears on the
     * next successful ack. Must be called on the EDT.
     */
    private fun triggerRecovery() {
        inFlight = null
        queue.clear()
        if (recovering) { pump(); return }
        recovering = true
        onRecoveryNeeded()
    }

    private fun onCopy(text: String) {
        val capped = if (text.length > MAX_COPY_CHARS) text.substring(0, MAX_COPY_CHARS) else text
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val ok = try {
                CopyPasteManager.getInstance().setContents(StringSelection(capped)); true
            } catch (e: Exception) {
                log.warn("Clipboard copy failed", e); false
            }
            browser?.cefBrowser?.executeJavaScript("window.__prismCopyDone($ok);", ShellHtmlBuilder.shellUrl, 0)
        }
    }

    private fun disclosureLabels(): Map<String, String> = mapOf(
        "thinking" to com.github.vgirotto.prism.i18n.ClaudeBundle.message("chatshell.disclosure.thinking"),
        "output" to com.github.vgirotto.prism.i18n.ClaudeBundle.message("chatshell.disclosure.output"),
        "details" to com.github.vgirotto.prism.i18n.ClaudeBundle.message("chatshell.disclosure.details"),
    )

    fun setFallbackText(text: String) {
        if (browser == null) fallbackArea.text = text
    }

    fun setState(newState: State) {
        state = newState
        applyStatus(statusFor(newState))
    }

    private fun statusFor(s: State): String? {
        fun m(key: String) = com.github.vgirotto.prism.i18n.ClaudeBundle.message(key)
        return when (s) {
            State.LOADING -> m("chatshell.loading")
            State.NO_TRANSCRIPT_YET -> m("chatshell.noTranscript")
            State.RECONNECTING -> m("chatshell.reconnecting")
            State.ERROR -> m("chatshell.error")
            State.UNAVAILABLE -> m("chatshell.unavailable")
            State.READY -> null // content replaces the banner
        }
    }

    /**
     * Render a status line in whichever surface is active so no state is ever a blank pane
     * (review #4): the Swing text area when JCEF is unavailable, otherwise the shell's
     * status banner. Null/empty clears it. Deferred until the shell has loaded.
     */
    private fun applyStatus(text: String?) {
        val b64 = if (text.isNullOrEmpty()) "" else Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            val b = browser
            if (b == null) {
                fallbackArea.text = text ?: ""
                return@invokeLater
            }
            if (shellLoaded) b.cefBrowser.executeJavaScript("window.__prismSetStatus(\"$b64\");", ShellHtmlBuilder.shellUrl, 0)
            else pendingStatusB64 = b64
        }
    }

    /** Patch the shell's CSS variables in place — no reload (design §10). */
    fun setTheme(vars: Map<String, String>) {
        val b = browser ?: return
        val b64 = TranscriptCodec.encodeStringMap(vars)
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) b.cefBrowser.executeJavaScript("window.__prismSetTheme(\"$b64\");", ShellHtmlBuilder.shellUrl, 0)
        }
    }

    private fun installHandlers(b: JBCefBrowser) {
        val ack = JBCefJSQuery.create(b as JBCefBrowserBase)
        val link = JBCefJSQuery.create(b as JBCefBrowserBase)
        val copy = JBCefJSQuery.create(b as JBCefBrowserBase)
        ack.addHandler { req -> onAck(req); null }
        link.addHandler { href ->
            if (isSafeExternalLink(href)) ApplicationManager.getApplication().invokeLater { onOpenLink(href) }
            null
        }
        copy.addHandler { text -> onCopy(text); null }
        ackQuery = ack; linkQuery = link; copyQuery = copy
        Disposer.register(this, ack); Disposer.register(this, link); Disposer.register(this, copy)

        val client = b.jbCefClient
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
                if (frame != null && !frame.isMain) return
                val labelsB64 = TranscriptCodec.encodeStringMap(disclosureLabels())
                val bootstrap = buildString {
                    append("window.__prismAck=function(p){").append(ack.inject("p")).append("};")
                    append("window.__prismLink=function(h){").append(link.inject("h")).append("};")
                    append("window.__prismCopy=function(t){").append(copy.inject("t")).append("};")
                    // i18n disclosure labels (base64 JSON), decoded by the shell.
                    append("(function(){var b=\"").append(labelsB64).append("\";")
                    append("var s=atob(b);var by=new Uint8Array(s.length);for(var i=0;i<s.length;i++)by[i]=s.charCodeAt(i);")
                    append("window.__prismLabels=JSON.parse(new TextDecoder('utf-8').decode(by));})();")
                }
                cefBrowser.executeJavaScript(bootstrap, ShellHtmlBuilder.shellUrl, 0)
                ApplicationManager.getApplication().invokeLater {
                    shellLoaded = true
                    if (state == State.LOADING) state = State.READY
                    pendingStatusB64?.let { s ->
                        cefBrowser.executeJavaScript("window.__prismSetStatus(\"$s\");", ShellHtmlBuilder.shellUrl, 0)
                        pendingStatusB64 = null
                    }
                    pump()
                }
            }
        }, b.cefBrowser)

        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                userGesture: Boolean, isRedirect: Boolean
            ): Boolean {
                val url = request?.url ?: return false
                return !isSameOriginRequest(url)
            }

            override fun getResourceRequestHandler(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?,
                disableDefaultHandling: BoolRef?
            ): CefResourceRequestHandler {
                return object : CefResourceRequestHandlerAdapter() {
                    override fun onBeforeResourceLoad(rb: CefBrowser?, rf: CefFrame?, rr: CefRequest?): Boolean {
                        val url = rr?.url ?: return false
                        if (isSameOriginRequest(url)) return false
                        externalRequestCount.incrementAndGet()
                        log.warn("Blocked external resource request from transcript view: $url")
                        return true
                    }
                }
            }
        }, b.cefBrowser)
    }

    override fun dispose() {
        disposed = true
        queue.clear()
        inFlight = null
    }

    companion object {
        private const val ACK_TIMEOUT_MS = 4000L
        private const val MAX_COPY_CHARS = 200_000

        private val SAFE_SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)
        fun isSafeExternalLink(href: String): Boolean = SAFE_SCHEME.containsMatchIn(href)

        /**
         * True for requests that belong to the shell itself and must be allowed to load; every
         * other request is a genuinely external (remote-host) fetch that we block and count
         * (§6.8, `externalRequestCount == 0`).
         *
         * `JBCefBrowser.loadHTML(html, url)` does NOT navigate to [ShellHtmlBuilder.shellUrl];
         * it serves the in-memory document under an internal pseudo-URL of the form
         * `file:///jbcefbrowser/<id>#url=<shellUrl>`. That is not a filesystem or network read —
         * the JBCef scheme handler only ever returns the registered HTML string — so allowing
         * the `file:///jbcefbrowser/` prefix lets the shell's own document (and its resource
         * requests, which arrive with the fragment stripped) load while remote hosts stay
         * blocked. Missing this prefix was what cancelled the shell load and left the pane blank.
         */
        fun isSameOriginRequest(url: String): Boolean =
            url == ShellHtmlBuilder.shellUrl ||
                url.startsWith("data:") ||
                url == "about:blank" ||
                url.startsWith("file:///jbcefbrowser/")
    }
}
