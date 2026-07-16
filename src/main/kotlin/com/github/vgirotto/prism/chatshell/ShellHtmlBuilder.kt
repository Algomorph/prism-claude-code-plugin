package com.github.vgirotto.prism.chatshell

import java.security.MessageDigest
import java.util.Base64

/**
 * Assembles the persistent JCEF shell HTML (design §6.1, §6.2, §6.8).
 *
 * Everything is inlined (no remote or even local network fetch is possible under the
 * CSP): KaTeX CSS with fonts rewritten to `data:` URIs, and KaTeX/marked/DOMPurify/our
 * `shell.js` as separate inline `<script>` blocks. The CSP `script-src` pins **each**
 * inline script by its SHA-256 hash — a blanket "our bootstrap" phrase would not be a
 * valid policy (§6.8). The shell is loaded exactly once; content arrives later via
 * `window.__prismApplyDelta` (executeJavaScript is not subject to this CSP, so deltas
 * still apply, while any script that reached the DOM as content is blocked).
 */
object ShellHtmlBuilder {

    private const val SHELL_URL = "https://prism.local/transcript"

    /** Order matters: dompurify + marked + katex must be defined before shell.js runs. */
    private val SCRIPT_RESOURCES = listOf(
        "/webview/dompurify/purify.min.js",
        "/webview/marked/marked.umd.js",
        "/webview/katex/katex.min.js",
        "/webview/shell.js",
    )

    val shellUrl: String get() = SHELL_URL

    /** Build the full shell document for the given theme variables. */
    fun build(theme: Map<String, String> = emptyMap()): String {
        val katexCss = inlineKatexFonts(readText("/webview/katex/katex.min.css"))
        val pageCss = readText("/webview/page.css")
        val scripts = SCRIPT_RESOURCES.map { it to readText(it) }

        val scriptHashes = scripts.joinToString(" ") { (_, body) -> "'sha256-${sha256Base64(body)}'" }
        val csp = buildString {
            append("default-src 'none'; ")
            append("img-src data:; ")
            append("font-src data:; ")
            append("style-src 'unsafe-inline'; ")
            append("script-src $scriptHashes; ")
            append("object-src 'none'; ")
            append("frame-src 'none'; ")
            append("base-uri 'none'; ")
            append("form-action 'none';")
        }

        val themeVars = theme.entries.joinToString("") { (k, v) -> "$k:$v;" }
        val scriptTags = scripts.joinToString("\n") { (_, body) -> "<script>$body</script>" }

        return buildString {
            append("<!doctype html>\n")
            append("<html><head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(csp).append("\">\n")
            append("<style>:root{").append(themeVars).append("}</style>\n")
            append("<style>").append(katexCss).append("</style>\n")
            append("<style>").append(pageCss).append("</style>\n")
            append("</head><body>\n")
            append("<div id=\"prism-content\" role=\"log\" aria-live=\"polite\" aria-label=\"Conversation transcript\"></div>\n")
            append(scriptTags).append("\n")
            append("</body></html>")
        }
    }

    /** Compute the CSP `script-src` hashes independently (used by tests). */
    fun scriptHashes(): List<String> = SCRIPT_RESOURCES.map { "sha256-${sha256Base64(readText(it))}" }

    private fun sha256Base64(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** Rewrite `url(fonts/X.woff2)` in the KaTeX CSS to inline `data:` URIs. */
    private fun inlineKatexFonts(css: String): String {
        val re = Regex("""url\(\s*(?:fonts/)?([A-Za-z0-9_\-]+\.woff2)\s*\)""")
        return re.replace(css) { m ->
            val name = m.groupValues[1]
            val bytes = readBytesOrNull("/webview/katex/fonts/$name")
            if (bytes == null) m.value
            else "url(data:font/woff2;base64,${Base64.getEncoder().encodeToString(bytes)})"
        }
    }

    private fun readText(path: String): String =
        readBytesOrNull(path)?.toString(Charsets.UTF_8)
            ?: error("Missing bundled webview resource: $path")

    private fun readBytesOrNull(path: String): ByteArray? =
        ShellHtmlBuilder::class.java.getResourceAsStream(path)?.use { it.readBytes() }
}
