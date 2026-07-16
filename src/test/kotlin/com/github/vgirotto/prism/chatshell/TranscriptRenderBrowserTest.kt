package com.github.vgirotto.prism.chatshell

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

/**
 * Browser-level rendering tests (design §7): math becomes KaTeX widgets, code-fenced `$`
 * is not math, the byte-exact source is preserved, and images resolve/block correctly.
 * Tagged "browser" — runs in the Xvfb CI job; self-skips without JCEF.
 */
@Tag("browser")
@TestApplication
class TranscriptRenderBrowserTest {

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block)

    @Test
    fun `math renders, code is not math, source is byte-exact, images resolve`() {
        assumeTrue(JBCefApp.isSupported(), "JCEF unavailable on this runner")
        val disposable = Disposer.newDisposable()
        try {
            val browser = AtomicReference<JBCefBrowser>()
            val loaded = CountDownLatch(1)
            val result = AtomicReference<String>()
            val gotResult = CountDownLatch(1)
            var frame: JFrame? = null

            onEdt {
                val b = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
                Disposer.register(disposable, b)
                browser.set(b)
                val q = JBCefJSQuery.create(b as JBCefBrowserBase)
                q.addHandler { r -> result.set(r); gotResult.countDown(); null }
                b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cb: CefBrowser, f: CefFrame?, code: Int) {
                        if (f != null && !f.isMain) return
                        cb.executeJavaScript("window.__prismTestResult=function(r){${q.inject("r")}};",
                            ShellHtmlBuilder.shellUrl, 0)
                        loaded.countDown()
                    }
                }, b.cefBrowser)
                frame = JFrame().apply { contentPane.add(b.component); setSize(800, 600); isVisible = true }
                b.loadHTML(ShellHtmlBuilder.build(), ShellHtmlBuilder.shellUrl)
            }
            assertTrue(loaded.await(30, TimeUnit.SECONDS), "shell did not load")
            val snippet = javaClass.getResourceAsStream("/js/render-test.js")!!.bufferedReader().readText()
            onEdt { browser.get().cefBrowser.executeJavaScript(snippet, ShellHtmlBuilder.shellUrl, 0) }
            assertTrue(gotResult.await(30, TimeUnit.SECONDS), "no render result")

            val j = JsonParser.parseString(result.get()).asJsonObject
            assertTrue(j["blockMathWidgets"].asInt >= 1, "\$\$…\$\$ must become a block math widget")
            assertTrue(j["inlineMathWidgets"].asInt >= 1, "\$…\$ must become an inline math widget")
            assertTrue(j["katexRendered"].asInt >= 1, "KaTeX must actually render spans")
            assertEquals(0, j["mathInCode"].asInt, "\$ inside a code fence must NOT be math")
            assertTrue(j["byteExact"].asBoolean, "revealed source must equal the original byte-exact")
            assertEquals(1, j["imagesRendered"].asInt, "an approved data: image renders")
            assertEquals(1, j["blockedImages"].asInt, "a remote markdown image is blocked")

            onEdt { frame?.dispose() }
        } finally {
            onEdt { Disposer.dispose(disposable) }
        }
    }
}
