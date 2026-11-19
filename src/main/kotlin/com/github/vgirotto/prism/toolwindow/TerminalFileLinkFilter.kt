package com.github.vgirotto.prism.toolwindow

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Links existing local files printed by an agent, relative to its project directory. */
internal class TerminalFileLinkFilter(
    private val baseDirectory: Path,
    private val openFile: (Path, Int, Int) -> Unit,
) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineOffset = entireLength - line.length
        val links = candidates.findAll(line).mapNotNull { match ->
            val target = match.groups.drop(1).firstNotNullOf { it }
            val angleWrapped = target.value.startsWith('<') && target.value.endsWith('>')
            val reference = target.value.let { if (angleWrapped) it.substring(1, it.length - 1) else it }
                .trimEnd('.', ',', ';', '!', '?')
            val position = location.find(reference)
            val path = resolveFile(position?.let { reference.substring(0, it.range.first) } ?: reference)
                ?: return@mapNotNull null
            val numbers = position?.groupValues?.drop(1)?.filter { it.isNotEmpty() }.orEmpty()
            val row = ((numbers.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            val column = ((numbers.getOrNull(1)?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            val start = lineOffset + target.range.first + if (angleWrapped) 1 else 0
            Filter.ResultItem(start, start + reference.length, HyperlinkInfo { openFile(path, row, column) })
        }.toList()
        return if (links.isEmpty()) null else Filter.Result(links)
    }

    private fun resolveFile(reference: String): Path? {
        if (reference.isEmpty()) return null
        return try {
            val path = when {
                reference.startsWith("file:") -> Path.of(URI.create(reference))
                scheme.containsMatchIn(reference) && !Path.of(reference).isAbsolute -> return null
                reference.startsWith("~/") -> Path.of(System.getProperty("user.home"), reference.substring(2))
                else -> baseDirectory.resolve(reference)
            }.normalize()
            path.takeIf { Files.isRegularFile(it) }
        } catch (_: InvalidPathException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private val candidates = Regex("""\[[^\]\r\n]*\]\((<?[^)\r\n]+>?)\)|"([^"\r\n]+)"|'([^'\r\n]+)'|([^\s`<>"'\[\](){}]+)""")
        private val location = Regex(""":(\d+)(?::(\d+))?$|#L(\d+)(?:C(\d+))?$""")
        private val scheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

        fun forProject(project: Project): TerminalFileLinkFilter = TerminalFileLinkFilter(
            Path.of(project.basePath ?: System.getProperty("user.home")),
        ) { path, line, column ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    if (file != null && !file.isDirectory) {
                        OpenFileDescriptor(project, file, line, column).navigate(true)
                    }
                }
            }
        }
    }
}
