package com.github.vgirotto.prism.changelog

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException

class ChangelogContentTest {
    @Test
    fun `Portuguese variants use the Brazilian Portuguese resource`() {
        val paths = mutableListOf<String>()
        val content = ChangelogContent(reader = { path ->
            paths.add(path)
            "# Alterações"
        })

        val result = content.load(Locale.forLanguageTag("pt-PT")) as ChangelogResult.Rendered

        assertEquals(listOf("/changelog/CHANGELOG.pt-BR.md"), paths)
        assertEquals("pt", result.language)
        assertEquals("<h1>Alterações</h1>\n", result.html)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `Spanish variants use the Spanish resource`() {
        val paths = mutableListOf<String>()
        val content = ChangelogContent(reader = { path ->
            paths.add(path)
            "# Cambios"
        })

        val result = content.load(Locale.forLanguageTag("es-AR")) as ChangelogResult.Rendered

        assertEquals(listOf("/changelog/CHANGELOG.es.md"), paths)
        assertEquals("es", result.language)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `English and unsupported locales select English without fallback`() {
        for (locale in listOf(Locale.ENGLISH, Locale.ROOT, Locale.JAPANESE)) {
            val paths = mutableListOf<String>()
            val content = ChangelogContent(reader = { path ->
                paths.add(path)
                "# Changelog"
            })

            val result = content.load(locale) as ChangelogResult.Rendered

            assertEquals(listOf("/changelog/CHANGELOG.md"), paths)
            assertEquals("en", result.language)
            assertFalse(result.usedFallback)
        }
    }

    @Test
    fun `missing localized content falls back to English`() {
        val paths = mutableListOf<String>()
        val content = ChangelogContent(reader = { path ->
            paths.add(path)
            if (path.endsWith(".pt-BR.md")) null else "# Changelog"
        })

        val result = content.load(Locale.forLanguageTag("pt-BR")) as ChangelogResult.Rendered

        assertEquals(listOf("/changelog/CHANGELOG.pt-BR.md", "/changelog/CHANGELOG.md"), paths)
        assertEquals("en", result.language)
        assertEquals("# Changelog", result.markdown)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `blank localized content falls back to English`() {
        val content = ChangelogContent(reader = { path ->
            if (path.endsWith(".es.md")) " \n\t" else "# Changelog"
        })

        val result = content.load(Locale.forLanguageTag("es")) as ChangelogResult.Rendered

        assertEquals("en", result.language)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `localized read failure still offers English content`() {
        val content = ChangelogContent(reader = { path ->
            if (path.endsWith(".es.md")) throw IOException("Cannot read resource")
            "# Changelog"
        })

        val result = content.load(Locale.forLanguageTag("es")) as ChangelogResult.Rendered

        assertTrue(result.usedFallback)
        assertEquals("en", result.language)
    }

    @Test
    fun `missing or unreadable English content produces unavailable state`() {
        val missing = ChangelogContent(reader = { null })
        val unreadable = ChangelogContent(reader = { throw IOException("Cannot read resource") })

        assertSame(ChangelogResult.Unavailable, missing.load(Locale.forLanguageTag("pt")))
        assertSame(ChangelogResult.Unavailable, unreadable.load(Locale.ENGLISH))
    }

    @Test
    fun `render failure preserves original Markdown and fallback information`() {
        val markdown = "# Changelog\n\n**Original** content"
        val content = ChangelogContent(
            reader = { path -> if (path.endsWith(".es.md")) null else markdown },
            renderer = { throw IllegalArgumentException("Render failure") },
        )

        val result = content.load(Locale.forLanguageTag("es")) as ChangelogResult.PlainText

        assertEquals(markdown, result.markdown)
        assertEquals("en", result.language)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `CommonMark renders the supported formatting and escapes literal HTML`() {
        val html = render(
            """
            ## [1.4.0] — 2026-10-02

            - **Added** *visibility* with `code` and <version> & text.
              - Nested item

            > A quote

            ---

            ```kotlin
            val value = "<version>"
            ```

            <script>alert('unsafe')</script>
            """.trimIndent(),
        )

        assertTrue(html.contains("<h2>[1.4.0] — 2026-10-02</h2>"))
        assertTrue(html.contains("<strong>Added</strong> <em>visibility</em>"))
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("&lt;version&gt; &amp; text."))
        assertEquals(2, Regex("<ul>").findAll(html).count())
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("<hr />"))
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `only absolute HTTP and HTTPS links remain actionable`() {
        val html = render(
            """
            [Secure](https://example.org/path?q=1&value=2 "Details")
            [Plain](http://example.org)
            [Mail](mailto:person@example.org)
            [File](file:///tmp/file)
            [Script](javascript:alert%281%29)
            [Data](data:text/plain;base64,SGk=)
            [Relative](../CHANGELOG.md)
            [Fragment](#release)
            [Protocol relative](//example.org)
            """.trimIndent(),
        )

        assertEquals(2, Regex("<a ").findAll(html).count())
        assertTrue(html.contains("href=\"https://example.org/path?q=1&amp;value=2\" title=\"Details\""))
        assertTrue(html.contains("href=\"http://example.org\""))
        assertTrue(html.contains("Mail"))
        assertTrue(html.contains("Protocol relative"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("data:"))
        assertFalse(html.contains("file:"))
    }

    @Test
    fun `image alt text is readable without image elements or embedded links`() {
        val html = render("![**Changes** with `code` & [link](https://example.org)](https://example.org/image.png)")

        assertTrue(html.contains("Changes with code &amp; link"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<a "))
        assertFalse(html.contains("image.png"))
    }

    @Test
    fun `external link validation also protects clicks outside renderer`() {
        for (destination in listOf("https://example.org", "http://localhost:8080/path", "HTTPS://example.org")) {
            assertTrue(isExternalChangelogLink(destination), destination)
        }

        val blockedDestinations = listOf(
            "", "https:", "https:///path", "https://", "#release", "//example.org",
            "mailto:a@example.org", "file:///tmp/a", "javascript:alert(1)", "https://example.org\n",
        )
        for (destination in blockedDestinations) {
            assertFalse(isExternalChangelogLink(destination), destination)
        }
    }

    @Test
    fun `cancellation propagates instead of becoming a fallback result`() {
        val readCancellation = CancellationException("Cancelled read")
        val renderCancellation = ProcessCanceledException()
        val reader = ChangelogContent(reader = { throw readCancellation })
        val renderer = ChangelogContent(reader = { "# Changelog" }, renderer = { throw renderCancellation })

        assertSame(readCancellation, assertThrows(CancellationException::class.java) { reader.load(Locale.ENGLISH) })
        assertSame(renderCancellation, assertThrows(ProcessCanceledException::class.java) { renderer.load(Locale.ENGLISH) })
    }

    private fun render(markdown: String): String =
        (ChangelogContent(reader = { markdown }).load(Locale.ENGLISH) as ChangelogResult.Rendered).html
}
