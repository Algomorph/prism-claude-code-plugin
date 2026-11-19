package com.github.vgirotto.prism.toolwindow

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy

class TerminalFileLinkFilterTest {
    @TempDir lateinit var dir: Path

    private data class Opened(val path: Path, val line: Int, val column: Int)
    private val opened = mutableListOf<Opened>()
    private val project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java),
    ) { _, method, _ -> error("Unexpected project access: ${method.name}") } as Project
    private fun filter() = TerminalFileLinkFilter(dir) { path, line, column ->
        opened += Opened(path, line, column)
    }

    private fun file(name: String): Path = dir.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "contents\n")
    }

    private fun checkLink(text: String, target: String, path: Path, row: Int = 0, column: Int = 0) {
        val result = assertNotNullResult(filter().applyFilter(text, 100 + text.length))
        val item = result.resultItems.single()
        assertEquals(100 + text.indexOf(target), item.highlightStartOffset)
        assertEquals(100 + text.indexOf(target) + target.length, item.highlightEndOffset)
        item.hyperlinkInfo!!.navigate(project)
        assertEquals(Opened(path, row, column), opened.last())
    }

    private fun assertNotNullResult(result: com.intellij.execution.filters.Filter.Result?) =
        requireNotNull(result) { "Expected a file link" }

    @Test
    fun `plain relative and absolute paths support line and column`() {
        val path = file(".temp/rotas.md")
        checkLink("see `.temp/rotas.md:12:5`", ".temp/rotas.md:12:5", path, 11, 4)
        checkLink("see $path:3", "$path:3", path, 2)
    }

    @Test
    fun `markdown destinations allow spaces and optional angle brackets`() {
        val path = file("my notes.md")
        checkLink("[notes](my notes.md:12:5)", "my notes.md:12:5", path, 11, 4)
        checkLink("[notes](<my notes.md#L12C5>)", "my notes.md#L12C5", path, 11, 4)
    }

    @Test
    fun `quoted paths allow spaces`() {
        val path = file("my notes.md")
        checkLink("open \"my notes.md:2:3\"", "my notes.md:2:3", path, 1, 2)
        checkLink("open 'my notes.md'", "my notes.md", path)
    }

    @Test
    fun `github locations and zero locations are converted to editor coordinates`() {
        val path = file("notes.md")
        checkLink("notes.md#L12", "notes.md#L12", path, 11)
        checkLink("notes.md#L12C5", "notes.md#L12C5", path, 11, 4)
        checkLink("notes.md:0:0", "notes.md:0:0", path)
    }

    @Test
    fun `file URIs decode escaped spaces`() {
        val path = file("my notes.md")
        val uri = path.toUri().toString()
        checkLink("$uri:4:2", "$uri:4:2", path, 3, 1)
    }

    @Test
    fun `home relative paths expand the user home`() {
        val path = file("notes.md")
        val home = Path.of(System.getProperty("user.home"))
        val reference = "~/" + home.relativize(path)
        checkLink(reference, reference, path)
    }

    @Test
    fun `trailing sentence punctuation is excluded from links`() {
        val path = file("notes.md")
        for (punctuation in listOf(".", ",", ";", "!", "?")) {
            checkLink("see notes.md:12$punctuation", "notes.md:12", path, 11)
        }
    }

    @Test
    fun `remote schemes nonexistent files and directories are ignored`() {
        file("notes.md")
        Files.createDirectories(dir.resolve("folder"))
        for (reference in listOf("https://example.org/notes.md", "missing.md", "folder", "bad\u0000path")) {
            assertNull(filter().applyFilter(reference, reference.length), reference)
        }
    }

    @Test
    fun `multiple links retain absolute buffer offsets and independent navigation`() {
        val first = file("first.md")
        val second = file("second.md")
        val text = "first.md:2 missing.md [second](second.md#L3C4)\n"
        val items = assertNotNullResult(filter().applyFilter(text, text.length + 80)).resultItems
        assertEquals(2, items.size)
        for ((item, target) in items.zip(listOf("first.md:2", "second.md#L3C4"))) {
            assertEquals(80 + text.indexOf(target), item.highlightStartOffset)
            assertEquals(80 + text.indexOf(target) + target.length, item.highlightEndOffset)
            item.hyperlinkInfo!!.navigate(project)
        }
        assertEquals(listOf(Opened(first, 1, 0), Opened(second, 2, 3)), opened)
    }
}
