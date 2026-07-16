package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
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
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * The rendered transcript pane (design §6.1). A persistent JCEF shell that is loaded
 * **once** and patched incrementally via base64 [TranscriptDelta]s; content safety lives
 * in `shell.js` (§6.8). Falls back to a read-only Swing text area when JCEF is
 * unavailable (R3). Full `loadHTML` is a recovery-only path (epoch reset / corruption).
 */
class TranscriptView(parentDisposable: Disposable) : Disposable {

    enum class State { LOADING, NO_TRANSCRIPT_YET, READY, RECONNECTING, ERROR }

    private val log = Logger.getInstance(TranscriptView::class.java)
    private val supported = JBCefApp.isSupported()

    // --- Swing fallback ---
    private val fallbackArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val fallbackScroll = JBScrollPane(fallbackArea)

    // --- JCEF ---
    private val browser: JBCefBrowser? =
        if (supported) JBCefBrowser.createBuilder().setOffScreenRendering(true).build() else null
    private var ackQuery: JBCefJSQuery? = null
    private var linkQuery: JBCefJSQuery? = null

    @Volatile private var shellLoaded = false
    @Volatile private var disposed = false
    @Volatile private var currentEpoch = 0L

    /** External resource requests the interceptor blocked — asserted == 0 by tests. */
    val externalRequestCount = AtomicInteger(0)

    @Volatile var state: State = State.LOADING
        private set

    var onOpenLink: (String) -> Unit = {}

    // Serial delta queue: only one delta is in flight; the next is sent after a matching
    // ack or a timeout (§8.3).
    private val queue = ArrayDeque<TranscriptDelta>()
    private var inFlight: TranscriptDelta? = null
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    init {
        Disposer.register(parentDisposable, this)
        if (browser != null) {
            Disposer.register(this, browser)
            installHandlers(browser)
        } else {
            fallbackArea.text = "" // populated via setFallbackText
        }
    }

    val component: JComponent
        get() = browser?.component ?: fallbackScroll

    /** Load the shell HTML once. Safe to call again only as a recovery reset. */
    fun loadShell(theme: Map<String, String> = emptyMap()) {
        val b = browser ?: run { state = State.READY; return }
        state = State.LOADING
        shellLoaded = false
        b.loadHTML(ShellHtmlBuilder.build(theme), ShellHtmlBuilder.shellUrl)
    }

    /**
     * Queue a delta for serial application. A non-matching epoch bumps [currentEpoch] and
     * clears the queue (a reset supersedes anything pending).
     */
    fun applyDelta(delta: TranscriptDelta) {
        if (disposed) return
        if (browser == null) return
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
        if (!shellLoaded) {
            // Shell not ready yet; requeue and wait for onLoadEnd to pump again.
            queue.addFirst(next)
            return
        }
        inFlight = next
        val b64 = TranscriptCodec.encodeDelta(next)
        // Only base64 is interpolated — never transcript content (R15).
        browser?.cefBrowser?.executeJavaScript(
            "window.__prismApplyDelta(\"$b64\");", ShellHtmlBuilder.shellUrl, 0
        )
        state = State.READY
        // Ack timeout: if the browser never acks, drop this delta and continue.
        val expected = next
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && inFlight === expected) {
                    log.debug("Ack timeout for epoch=${expected.epoch} rev=${expected.revision}")
                    inFlight = null
                    pump()
                }
            }
        }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onAck(raw: String) {
        val ack = TranscriptCodec.decodeAck(raw) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            // Discard stale-epoch or post-disposal acks — they can never graduate state.
            if (ack.epoch != currentEpoch) return@invokeLater
            val cur = inFlight
            if (cur != null && cur.epoch == ack.epoch && cur.revision == ack.revision) {
                inFlight = null
                pump()
            }
        }
    }

    fun setFallbackText(text: String) {
        if (browser == null) fallbackArea.text = text
    }

    fun setState(newState: State) {
        state = newState
    }

    private fun installHandlers(b: JBCefBrowser) {
        val ack = JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase)
        val link = JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase)
        ack.addHandler { req -> onAck(req); null }
        link.addHandler { href ->
            if (isSafeExternalLink(href)) ApplicationManager.getApplication().invokeLater { onOpenLink(href) }
            null
        }
        ackQuery = ack
        linkQuery = link
        Disposer.register(this, ack)
        Disposer.register(this, link)

        val client = b.jbCefClient

        // Bootstrap the JS bridges once the shell finishes loading, then pump the queue.
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
                if (frame != null && !frame.isMain) return
                val bootstrap = buildString {
                    append("window.__prismAck=function(p){").append(ack.inject("p")).append("};")
                    append("window.__prismLink=function(h){").append(link.inject("h")).append("};")
                }
                cefBrowser.executeJavaScript(bootstrap, ShellHtmlBuilder.shellUrl, 0)
                ApplicationManager.getApplication().invokeLater {
                    shellLoaded = true
                    if (state == State.LOADING) state = State.READY
                    pump()
                }
            }
        }, b.cefBrowser)

        // Navigation + resource interception (§6.8): block all in-view navigation away
        // from the shell, and block/count every external resource request so the
        // "no remote request occurred" property is provable, not assumed.
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                userGesture: Boolean, isRedirect: Boolean
            ): Boolean {
                val url = request?.url ?: return false
                // Allow the initial shell load; cancel any subsequent navigation.
                return !(url == ShellHtmlBuilder.shellUrl || url.startsWith("data:") || url == "about:blank")
            }

            override fun getResourceRequestHandler(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?,
                disableDefaultHandling: BoolRef?
            ): CefResourceRequestHandler {
                return object : CefResourceRequestHandlerAdapter() {
                    override fun onBeforeResourceLoad(
                        rb: CefBrowser?, rf: CefFrame?, rr: CefRequest?
                    ): Boolean {
                        val url = rr?.url ?: return false
                        if (url.startsWith("data:") || url == ShellHtmlBuilder.shellUrl || url == "about:blank") {
                            return false // allowed inline content
                        }
                        externalRequestCount.incrementAndGet()
                        log.warn("Blocked external resource request from transcript view: $url")
                        return true // cancel
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

        private val SAFE_SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)
        fun isSafeExternalLink(href: String): Boolean = SAFE_SCHEME.containsMatchIn(href)
    }
}
