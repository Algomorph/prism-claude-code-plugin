package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.google.gson.JsonParser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

/**
 * Browser-level attack-surface tests (design §6.8, §7). These render real DOM in JCEF
 * and assert the sanitization pipeline neutralizes hostile transcript content, plus that
 * the CEF request interceptor observed **zero** external resource requests — a DOM
 * placeholder assertion alone cannot prove nothing was fetched (review #1).
 *
 * Tagged "browser": excluded from the default headless `./gradlew test`, opted in with
 * `-PbrowserTests` under Xvfb (see ui-test.yml). Also self-skips if JCEF is unavailable.
 */
@Tag("browser")
@TestApplication
class TranscriptSecurityBrowserTest {

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeAndWait(block)

    @Test
    fun `sanitization neutralizes hostile transcript content`() {
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
                val resultQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
                resultQuery.addHandler { r -> result.set(r); gotResult.countDown(); null }
                b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cb: CefBrowser, f: CefFrame?, code: Int) {
                        if (f != null && !f.isMain) return
                        cb.executeJavaScript(
                            "window.__prismTestResult=function(r){${resultQuery.inject("r")}};",
                            ShellHtmlBuilder.shellUrl, 0
                        )
                        loaded.countDown()
                    }
                }, b.cefBrowser)
                frame = JFrame().apply {
                    contentPane.add(b.component)
                    setSize(800, 600)
                    isVisible = true
                }
                b.loadHTML(ShellHtmlBuilder.build(), ShellHtmlBuilder.shellUrl)
            }

            assertTrue(loaded.await(30, TimeUnit.SECONDS), "shell did not load")

            onEdt { browser.get().cefBrowser.executeJavaScript(ATTACK_SNIPPET, ShellHtmlBuilder.shellUrl, 0) }
            assertTrue(gotResult.await(30, TimeUnit.SECONDS), "no result from render pipeline")

            val json = JsonParser.parseString(result.get()).asJsonObject
            assertEquals(0, json["scripts"].asInt, "script tags must be stripped")
            assertEquals(0, json["iframes"].asInt, "iframes must be stripped")
            assertEquals(0, json["svgs"].asInt, "svg must be stripped")
            assertEquals(0, json["onerror"].asInt, "event handlers must be stripped")
            assertEquals(0, json["jsLinks"].asInt, "javascript:/data:/file: links must be neutralized")
            assertEquals(0, json["remoteImgs"].asInt, "remote images must become markers")

            onEdt { frame?.dispose() }
        } finally {
            onEdt { Disposer.dispose(disposable) }
        }
    }

    @Test
    fun `no remote resource request occurs`() {
        assumeTrue(JBCefApp.isSupported(), "JCEF unavailable on this runner")
        val disposable = Disposer.newDisposable()
        try {
            val viewRef = AtomicReference<TranscriptView>()
            var frame: JFrame? = null
            onEdt {
                val view = TranscriptView(disposable)
                viewRef.set(view)
                frame = JFrame().apply {
                    contentPane.add(view.component)
                    setSize(800, 600)
                    isVisible = true
                }
                view.loadShell()
            }
            // Give the shell time to load, then push content that references remote assets.
            Thread.sleep(4000)
            onEdt {
                viewRef.get().applyDelta(
                    TranscriptDelta(
                        0, 1,
                        listOf(
                            DeltaOp.upsert(
                                "m1",
                                BlockPayload(
                                    "assistant", "Claude",
                                    listOf(RenderBlock(kind = "text",
                                        markdown = "![x](https://evil.example/tracker.png) and " +
                                            "[link](https://evil.example)"))
                                )
                            )
                        )
                    )
                )
            }
            Thread.sleep(3000)
            assertEquals(0, viewRef.get().externalRequestCount.get(),
                "the interceptor must have blocked/observed zero external requests")
            onEdt { frame?.dispose() }
        } finally {
            onEdt { Disposer.dispose(disposable) }
        }
    }

    private fun assertTrue(cond: Boolean, msg: String) =
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg)

    companion object {
        // Renders each hostile case through the shipped pipeline and reports counts.
        private val ATTACK_SNIPPET = """
            (function(){
              var cases = [
                '<img src=x onerror=alert(1)>',
                '[click](javascript:alert(1))',
                '<iframe src="https://evil"></iframe>',
                '<svg><script>alert(1)</script></svg>',
                '![remote](https://evil.example/x.png)',
                '<a href="data:text/html,<script>alert(1)</script>">x</a>',
                '<span class="katex"><script>alert(1)</script></span>'
              ];
              var container = document.createElement('div');
              for (var i=0;i<cases.length;i++){
                var clean = window.__prismRenderMarkdown(cases[i]);
                var d = document.createElement('div'); d.innerHTML = clean;
                window.__prismHardenNode(d);
                container.appendChild(d);
              }
              function countLinks(){var n=0;var a=container.querySelectorAll('a');
                for(var k=0;k<a.length;k++){var h=a[k].getAttribute('href')||'';
                  if(/^(javascript|data|vbscript|file):/i.test(h))n++;}return n;}
              function countRemoteImgs(){var n=0;var im=container.querySelectorAll('img');
                for(var k=0;k<im.length;k++){var s=im[k].getAttribute('src')||'';
                  if(s.indexOf('data:image/')!==0)n++;}return n;}
              var res = {
                scripts: container.querySelectorAll('script').length,
                iframes: container.querySelectorAll('iframe').length,
                svgs: container.querySelectorAll('svg').length,
                onerror: container.querySelectorAll('[onerror]').length,
                jsLinks: countLinks(),
                remoteImgs: countRemoteImgs()
              };
              window.__prismTestResult(JSON.stringify(res));
            })();
        """.trimIndent()
    }
}
