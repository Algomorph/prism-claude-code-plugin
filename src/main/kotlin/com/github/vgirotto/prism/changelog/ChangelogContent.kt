package com.github.vgirotto.prism.changelog

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.CancellationException

internal sealed class ChangelogResult {
    data class Rendered(
        val markdown: String,
        val html: String,
        val language: String,
        val usedFallback: Boolean,
    ) : ChangelogResult()

    data class PlainText(
        val markdown: String,
        val language: String,
        val usedFallback: Boolean,
    ) : ChangelogResult()

    object Unavailable : ChangelogResult()
}

internal class ChangelogContent(
    private val reader: (String) -> String? = ::readChangelogResource,
    private val renderer: (String) -> String = ::renderChangelogMarkdown,
) {
    private val log = Logger.getInstance(ChangelogContent::class.java)

    fun load(locale: Locale): ChangelogResult {
        val requestedLanguage = when (locale.language) {
            "pt", "es" -> locale.language
            else -> "en"
        }
        val localizedMarkdown = read(requestedLanguage)
        val usedFallback = localizedMarkdown == null && requestedLanguage != "en"
        val markdown = localizedMarkdown ?: if (usedFallback) read("en") else null

        if (markdown == null) return ChangelogResult.Unavailable

        val language = if (usedFallback) "en" else requestedLanguage
        return try {
            ChangelogResult.Rendered(markdown, renderer(markdown), language, usedFallback)
        } catch (exception: Exception) {
            preserveCancellation(exception)
            log.warn("Failed to render the Prism changelog; showing Markdown instead", exception)
            ChangelogResult.PlainText(markdown, language, usedFallback)
        }
    }

    private fun read(language: String): String? {
        val resourcePath = when (language) {
            "pt" -> "/changelog/CHANGELOG.pt-BR.md"
            "es" -> "/changelog/CHANGELOG.es.md"
            else -> "/changelog/CHANGELOG.md"
        }

        return try {
            reader(resourcePath)?.takeIf(String::isNotBlank).also { markdown ->
                if (markdown == null) log.warn("Prism changelog resource is missing or empty: $resourcePath")
            }
        } catch (exception: Exception) {
            preserveCancellation(exception)
            log.warn("Failed to read Prism changelog resource: $resourcePath", exception)
            null
        }
    }
}

internal fun isExternalChangelogLink(destination: String): Boolean = try {
    val uri = URI(destination)
    uri.isAbsolute && !uri.isOpaque && !uri.host.isNullOrBlank() &&
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true))
} catch (_: URISyntaxException) {
    false
}

private fun readChangelogResource(resourcePath: String): String? =
    ChangelogContent::class.java.getResourceAsStream(resourcePath)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    }

private fun renderChangelogMarkdown(markdown: String): String {
    val document = Parser.builder().build().parse(markdown)
    val renderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .nodeRendererFactory { context -> ChangelogLinkRenderer(context) }
        .build()

    return renderer.render(document)
}

private class ChangelogLinkRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Link::class.java, Image::class.java)

    override fun render(node: Node) {
        when (node) {
            is Link -> renderLink(node)
            is Image -> renderImageText(node)
        }
    }

    private fun renderLink(link: Link) {
        val allowed = isExternalChangelogLink(link.destination)
        if (allowed) {
            val attributes = linkedMapOf("href" to context.encodeUrl(link.destination))
            link.title?.let { attributes["title"] = it }
            context.writer.tag("a", attributes)
        }

        var child = link.firstChild
        while (child != null) {
            context.render(child)
            child = child.next
        }

        if (allowed) context.writer.tag("/a")
    }

    private fun renderImageText(image: Image) {
        val alternativeText = StringBuilder()
        image.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                alternativeText.append(text.literal)
            }

            override fun visit(code: Code) {
                alternativeText.append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                alternativeText.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                alternativeText.append(' ')
            }
        })
        context.writer.text(alternativeText.toString())
    }
}

private fun preserveCancellation(exception: Exception) {
    if (exception is ProcessCanceledException || exception is CancellationException) throw exception
}
