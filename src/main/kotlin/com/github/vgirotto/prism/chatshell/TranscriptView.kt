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

    enum class State { LOADING, NO_TRANSCRIPT_YET, READY, RECONNECTING, ERROR }

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

    @Volatile var state: State = State.LOADING
        private set

    var onOpenLink: (String) -> Unit = {}

    private val queue = ArrayDeque<TranscriptDelta>()
    private var inFlight: TranscriptDelta? = null
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
                    log.debug("Ack timeout epoch=${expected.epoch} rev=${expected.revision}")
                    inFlight = null; pump()
                }
            }
        }, ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onAck(raw: String) {
        val ack = TranscriptCodec.decodeAck(raw) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || ack.epoch != currentEpoch) return@invokeLater
            val cur = inFlight
            if (cur != null && cur.epoch == ack.epoch && cur.revision == ack.revision) {
                inFlight = null; pump()
            }
        }
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

    fun setFallbackText(text: String) {
        if (browser == null) fallbackArea.text = text
    }

    fun setState(newState: State) { state = newState }

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
                val bootstrap = buildString {
                    append("window.__prismAck=function(p){").append(ack.inject("p")).append("};")
                    append("window.__prismLink=function(h){").append(link.inject("h")).append("};")
                    append("window.__prismCopy=function(t){").append(copy.inject("t")).append("};")
                }
                cefBrowser.executeJavaScript(bootstrap, ShellHtmlBuilder.shellUrl, 0)
                ApplicationManager.getApplication().invokeLater {
                    shellLoaded = true
                    if (state == State.LOADING) state = State.READY
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
                return !(url == ShellHtmlBuilder.shellUrl || url.startsWith("data:") || url == "about:blank")
            }

            override fun getResourceRequestHandler(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?,
                disableDefaultHandling: BoolRef?
            ): CefResourceRequestHandler {
                return object : CefResourceRequestHandlerAdapter() {
                    override fun onBeforeResourceLoad(rb: CefBrowser?, rf: CefFrame?, rr: CefRequest?): Boolean {
                        val url = rr?.url ?: return false
                        if (url.startsWith("data:") || url == ShellHtmlBuilder.shellUrl || url == "about:blank") return false
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
    }
}
