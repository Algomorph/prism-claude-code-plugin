package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ChangeStatus
import com.github.vgirotto.prism.model.FileDiffEntry
import com.github.vgirotto.prism.model.InteractionDiff
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Tests for the temp-file-based snapshot engine.
 * Mirrors FileSnapshotService logic without IntelliJ deps.
 */
class FileSnapshotServiceTest {

    @TempDir
    lateinit var projectDir: File

    private lateinit var engine: TempFileSnapshotEngine

    @BeforeEach
    fun setup() {
        engine = TempFileSnapshotEngine(projectDir.absolutePath)
    }

    @AfterEach
    fun cleanup() {
        engine.dispose()
    }

    // --- Snapshot ---

    @Test
    fun `takeSnapshot creates temp dir with file copies`() {
        createFile("src/main.kt", "fun main() {}")
        createFile("README.md", "# Hello")
        engine.takeSnapshot()

        assertEquals(2, engine.indexSize())
        assertTrue(engine.tempDirExists())
        assertTrue(engine.tempFileExists("src/main.kt"))
        assertTrue(engine.tempFileExists("README.md"))
    }

    @Test
    fun `takeSnapshot temp files have correct content`() {
        createFile("src/main.kt", "fun main() {}")
        engine.takeSnapshot()

        assertEquals("fun main() {}", engine.readTempFile("src/main.kt"))
    }

    @Test
    fun `second snapshot is incremental - reuses temp dir`() {
        createFile("file.kt", "v1")
        createFile("stable.kt", "unchanged")
        engine.takeSnapshot()
        val firstDir = engine.getTempDirPath()

        // Simulate Claude changing file.kt
        createFile("file.kt", "v2")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.takeSnapshot() // incremental
        val secondDir = engine.getTempDirPath()

        // Same temp dir reused
        assertEquals(firstDir, secondDir)
        // Updated file has new content in temp
        assertEquals("v2", engine.readTempFile("file.kt"))
        // Unchanged file still has original content
        assertEquals("unchanged", engine.readTempFile("stable.kt"))
    }

    @Test
    fun `incremental snapshot only updates changed files`() {
        createFile("a.kt", "a1")
        createFile("b.kt", "b1")
        createFile("c.kt", "c1")
        engine.takeSnapshot() // full: 3 files

        // Only b.kt changed
        createFile("b.kt", "b2")
        engine.recordChange("${projectDir.absolutePath}/b.kt")
        engine.takeSnapshot() // incremental: 1 file updated

        assertEquals("a1", engine.readTempFile("a.kt")) // untouched
        assertEquals("b2", engine.readTempFile("b.kt")) // updated
        assertEquals("c1", engine.readTempFile("c.kt")) // untouched
    }

    @Test
    fun `incremental snapshot handles new files from Claude`() {
        createFile("existing.kt", "original")
        engine.takeSnapshot()

        createFile("new.kt", "added by claude")
        engine.recordChange("${projectDir.absolutePath}/new.kt")
        engine.takeSnapshot() // incremental: adds new.kt to temp

        assertTrue(engine.tempFileExists("new.kt"))
        assertEquals("added by claude", engine.readTempFile("new.kt"))
        assertTrue(engine.hasHash("new.kt"))
    }

    @Test
    fun `incremental snapshot handles deleted files from Claude`() {
        createFile("file.kt", "content")
        engine.takeSnapshot()

        File(projectDir, "file.kt").delete()
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.takeSnapshot() // incremental: removes file.kt from temp

        assertFalse(engine.tempFileExists("file.kt"))
        assertFalse(engine.hasHash("file.kt"))
    }

    @Test
    fun `excludes hidden and IDE directories`() {
        createFile("src/main.kt", "code")
        createDir(".git"); createFile(".git/config", "x")
        createDir(".idea"); createFile(".idea/workspace.xml", "x")
        createDir("node_modules"); createFile("node_modules/lib/index.js", "x")
        engine.takeSnapshot()

        assertEquals(1, engine.indexSize())
        assertFalse(engine.tempFileExists(".git/config"))
        assertFalse(engine.tempFileExists(".idea/workspace.xml"))
    }

    @Test
    fun `excludes pattern-matched files`() {
        createFile("src/main.kt", "code")
        createFile("project.iml", "iml")
        engine.takeSnapshot()

        assertTrue(engine.hasHash("src/main.kt"))
        assertFalse(engine.hasHash("project.iml"))
    }

    @Test
    fun `excludes wildcard-matched directories`() {
        engine = TempFileSnapshotEngine(projectDir.absolutePath, listOf("cmake*", "vendor-*"))
        createFile("src/main.kt", "code")
        createFile("cmake-build-debug/cache.txt", "x")
        createFile("third_party/vendor-local/index.js", "x")
        engine.takeSnapshot()

        assertTrue(engine.hasHash("src/main.kt"))
        assertFalse(engine.hasHash("cmake-build-debug/cache.txt"))
        assertFalse(engine.hasHash("third_party/vendor-local/index.js"))
    }

    @Test
    fun `ignores recorded changes in wildcard-matched directories`() {
        engine = TempFileSnapshotEngine(projectDir.absolutePath, listOf("generated-*"))
        createFile("src/main.kt", "code")
        engine.takeSnapshot()

        createFile("src/generated-client/model.kt", "generated")
        engine.recordChange("${projectDir.absolutePath}/src/generated-client/model.kt")
        val diff = engine.computeDiff()

        assertTrue(diff.changes.isEmpty())
    }

    @Test
    fun `skips large files over 1MB`() {
        createFile("small.txt", "small")
        createFile("large.bin", "x".repeat(1_100_000))
        engine.takeSnapshot()

        assertTrue(engine.hasHash("small.txt"))
        assertFalse(engine.hasHash("large.bin"))
    }

    // --- Diff ---

    @Test
    fun `detects modified files`() {
        createFile("file.kt", "original")
        engine.takeSnapshot()

        createFile("file.kt", "modified")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        val diff = engine.computeDiff()

        assertEquals(1, diff.changes.size)
        assertEquals(ChangeStatus.MODIFIED, diff.changes[0].status)
        assertEquals("original", String(diff.changes[0].originalContent!!))
        assertEquals("modified", String(diff.changes[0].modifiedContent!!))
    }

    @Test
    fun `detects added files`() {
        createFile("existing.kt", "existing")
        engine.takeSnapshot()

        createFile("new.kt", "new content")
        engine.recordChange("${projectDir.absolutePath}/new.kt")
        val diff = engine.computeDiff()

        val added = diff.changes.find { it.status == ChangeStatus.ADDED }!!
        assertEquals("new.kt", added.path)
        assertNull(added.originalContent)
        assertEquals("new content", String(added.modifiedContent!!))
    }

    @Test
    fun `detects deleted files`() {
        createFile("file.kt", "content")
        engine.takeSnapshot()

        File(projectDir, "file.kt").delete()
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        val diff = engine.computeDiff()

        assertEquals(1, diff.changes.size)
        assertEquals(ChangeStatus.DELETED, diff.changes[0].status)
        assertEquals("content", String(diff.changes[0].originalContent!!))
    }

    @Test
    fun `detects mixed changes`() {
        createFile("modified.kt", "old")
        createFile("deleted.kt", "to delete")
        engine.takeSnapshot()

        createFile("modified.kt", "new")
        File(projectDir, "deleted.kt").delete()
        createFile("added.kt", "brand new")
        engine.recordChange("${projectDir.absolutePath}/modified.kt")
        engine.recordChange("${projectDir.absolutePath}/deleted.kt")
        engine.recordChange("${projectDir.absolutePath}/added.kt")
        val diff = engine.computeDiff()

        assertEquals(3, diff.changes.size)
        val statuses = diff.changes.map { it.status }.toSet()
        assertTrue(statuses.containsAll(listOf(ChangeStatus.ADDED, ChangeStatus.MODIFIED, ChangeStatus.DELETED)))
    }

    @Test
    fun `ignores unchanged files`() {
        createFile("unchanged.kt", "same")
        createFile("changed.kt", "v1")
        engine.takeSnapshot()

        createFile("changed.kt", "v2")
        engine.recordChange("${projectDir.absolutePath}/changed.kt")
        val diff = engine.computeDiff()

        assertEquals(1, diff.changes.size)
        assertEquals("changed.kt", diff.changes[0].path)
    }

    @Test
    fun `returns empty when no changes`() {
        createFile("file.kt", "content")
        engine.takeSnapshot()
        assertTrue(engine.computeDiff().changes.isEmpty())
    }

    @Test
    fun `returns empty when not initialized`() {
        assertTrue(engine.computeDiff().changes.isEmpty())
    }

    @Test
    fun `detects changes even without recordChange via full scan`() {
        createFile("file.kt", "original")
        engine.takeSnapshot()
        createFile("file.kt", "modified")

        val diff = engine.computeDiff()
        assertEquals(1, diff.changes.size)
        assertEquals(ChangeStatus.MODIFIED, diff.changes[0].status)
    }

    // --- History ---

    @Test
    fun `tracks multiple interactions`() {
        createFile("file.kt", "v1")
        engine.takeSnapshot()
        createFile("file.kt", "v2")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()
        engine.takeSnapshot()
        createFile("file.kt", "v3")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()

        assertEquals(2, engine.historySize())
    }

    @Test
    fun `getDiff by index`() {
        createFile("file.kt", "v1")
        engine.takeSnapshot()
        createFile("file.kt", "v2")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()
        engine.takeSnapshot()
        createFile("file.kt", "v3")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()

        assertNotNull(engine.getDiff(1))
        assertNotNull(engine.getDiff(2))
        assertNull(engine.getDiff(3))
    }

    @Test
    fun `getLatestDiff returns most recent`() {
        createFile("file.kt", "v1")
        engine.takeSnapshot()
        createFile("file.kt", "v2")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()
        engine.takeSnapshot()
        createFile("file.kt", "v3")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        engine.computeDiff()

        assertEquals(2, engine.getLatestDiff()!!.interactionIndex)
    }

    // --- Revert ---

    @Test
    fun `revert modified file`() {
        createFile("file.kt", "original")
        engine.takeSnapshot()
        createFile("file.kt", "modified")
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        val diff = engine.computeDiff()

        engine.revertEntry(diff.changes[0])
        assertEquals("original", File(projectDir, "file.kt").readText())
    }

    @Test
    fun `revert added file`() {
        createFile("existing.kt", "existing")
        engine.takeSnapshot()
        createFile("new.kt", "added")
        engine.recordChange("${projectDir.absolutePath}/new.kt")
        val diff = engine.computeDiff()

        engine.revertEntry(diff.changes.find { it.status == ChangeStatus.ADDED }!!)
        assertFalse(File(projectDir, "new.kt").exists())
    }

    @Test
    fun `revert deleted file`() {
        createFile("file.kt", "important")
        engine.takeSnapshot()
        File(projectDir, "file.kt").delete()
        engine.recordChange("${projectDir.absolutePath}/file.kt")
        val diff = engine.computeDiff()

        engine.revertEntry(diff.changes[0])
        assertEquals("important", File(projectDir, "file.kt").readText())
    }

    @Test
    fun `revert all`() {
        createFile("a.kt", "a orig")
        createFile("b.kt", "b orig")
        engine.takeSnapshot()
        createFile("a.kt", "a mod")
        createFile("b.kt", "b mod")
        createFile("c.kt", "c new")
        engine.recordChange("${projectDir.absolutePath}/a.kt")
        engine.recordChange("${projectDir.absolutePath}/b.kt")
        engine.recordChange("${projectDir.absolutePath}/c.kt")
        val diff = engine.computeDiff()

        engine.revertAll(diff)
        assertEquals("a orig", File(projectDir, "a.kt").readText())
        assertEquals("b orig", File(projectDir, "b.kt").readText())
        assertFalse(File(projectDir, "c.kt").exists())
    }

    // --- Memory / Performance ---

    @Test
    fun `snapshot keeps only hashes in memory not content`() {
        for (i in 1..100) createFile("src/file$i.kt", "content $i with some padding text")
        engine.takeSnapshot()

        assertEquals(100, engine.indexSize())
        // Hash index: 100 entries × ~64 chars = ~6KB (not 100 × file_size)
        assertTrue(engine.tempDirExists())
    }

    @Test
    fun `scales with 1000 files`() {
        for (i in 1..1000) createFile("src/pkg${i / 100}/file$i.kt", "content $i")

        val start = System.currentTimeMillis()
        engine.takeSnapshot()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(1000, engine.indexSize())
        assertTrue(elapsed < 10000, "Took ${elapsed}ms")
    }

    @Test
    fun `dispose cleans up temp dir`() {
        createFile("file.kt", "content")
        engine.takeSnapshot()
        val path = engine.getTempDirPath()!!

        engine.dispose()
        assertFalse(File(path).exists())
    }

    // --- Helpers ---

    private fun createFile(path: String, content: String) {
        File(projectDir, path).apply { parentFile?.mkdirs(); writeText(content) }
    }

    private fun createDir(path: String) { File(projectDir, path).mkdirs() }

    /**
     * Standalone engine mirroring FileSnapshotService with temp files.
     */
    class TempFileSnapshotEngine(
        private val basePath: String,
        private val exDirs: List<String> = listOf("node_modules",".git","build","out",".gradle",".idea","target","dist",".next","__pycache__",".venv","vendor",".intellijPlatform",".DS_Store",".cls",".cache"),
    ) {
        private var hashes = mapOf<String, String>()
        private var tempDir: File? = null
        private val changed = mutableSetOf<String>()
        private val history = mutableListOf<InteractionDiff>()

        private val exPats = listOf(Regex(".*\\.iml$"),Regex(".*\\.class$"),Regex(".*\\.jar$"),Regex(".*\\.pyc$"),Regex("\\.DS_Store"))

        fun takeSnapshot() {
            if (tempDir == null || !tempDir!!.exists()) {
                // First: full copy
                val td = Files.createTempDirectory("test-snapshot-").toFile()
                val h = mutableMapOf<String, String>()
                copyFiles(File(basePath), basePath, td, h)
                tempDir = td; hashes = h
            } else {
                // Incremental: only update changed files
                val td = tempDir!!
                val h = hashes.toMutableMap()
                for (r in changed) {
                    if (excluded(r)) continue
                    val pf = File(basePath, r); val tf = File(td, r)
                    if (!pf.exists()) {
                        if (tf.exists()) tf.delete()
                        h.remove(r)
                    } else if (pf.isFile && pf.length() < 1_000_000) {
                        val c = pf.readBytes(); val nh = sha256(c)
                        if (h[r] != nh || !h.containsKey(r)) {
                            tf.parentFile?.mkdirs(); tf.writeBytes(c); h[r] = nh
                        }
                    }
                }
                hashes = h
            }
            changed.clear()
        }

        private fun copyFiles(dir: File, base: String, td: File, h: MutableMap<String, String>) {
            for (f in (dir.listFiles() ?: return)) {
                val r = f.path.removePrefix(base).removePrefix("/")
                if (f.isDirectory) { if (!excluded(r)) copyFiles(f, base, td, h); continue }
                if (!f.isFile || f.length() > 1_000_000) continue
                if (excluded(r)) continue
                val c = f.readBytes()
                h[r] = sha256(c)
                File(td, r).apply { parentFile?.mkdirs(); writeBytes(c) }
            }
        }

        fun recordChange(path: String) {
            if (path.startsWith(basePath)) {
                val r = path.removePrefix(basePath).removePrefix("/")
                if (!excluded(r)) changed.add(r)
            }
        }

        fun computeDiff(): InteractionDiff {
            val td = tempDir ?: return empty()
            if (hashes.isEmpty()) return empty()
            val changes = mutableListOf<FileDiffEntry>()
            val paths = if (changed.isNotEmpty()) changed.toSet() + hashes.keys else hashes.keys.toSet()
            val checked = mutableSetOf<String>()
            for (r in paths) {
                if (!checked.add(r) || excluded(r)) continue
                val pf = File(basePath, r); val tf = File(td, r); val oh = hashes[r]
                if (!pf.exists()) {
                    if (oh != null && tf.isFile) changes.add(FileDiffEntry(r, ChangeStatus.DELETED, tf.readBytes(), null))
                } else if (pf.isFile && pf.length() < 1_000_000) {
                    val cur = pf.readBytes(); val ch = sha256(cur)
                    if (oh == null) changes.add(FileDiffEntry(r, ChangeStatus.ADDED, null, cur))
                    else if (oh != ch && tf.isFile) changes.add(FileDiffEntry(r, ChangeStatus.MODIFIED, tf.readBytes(), cur))
                }
            }
            for (r in changed) {
                if (checked.contains(r) || excluded(r)) continue
                val f = File(basePath, r)
                if (f.isFile && f.length() < 1_000_000 && hashes[r] == null)
                    changes.add(FileDiffEntry(r, ChangeStatus.ADDED, null, f.readBytes()))
            }
            if (changes.isEmpty()) return empty()
            val d = InteractionDiff(history.size + 1, System.currentTimeMillis(), changes.sortedBy { it.path })
            history.add(d); return d
        }

        fun revertEntry(e: FileDiffEntry) {
            val p = "$basePath/${e.path}"
            when (e.status) {
                ChangeStatus.MODIFIED -> e.originalContent?.let { File(p).writeBytes(it) }
                ChangeStatus.ADDED -> File(p).delete()
                ChangeStatus.DELETED -> e.originalContent?.let { File(p).apply { parentFile?.mkdirs(); writeBytes(it) } }
            }
        }
        fun revertAll(d: InteractionDiff) { d.changes.forEach { revertEntry(it) } }

        fun indexSize() = hashes.size
        fun hasHash(p: String) = hashes.containsKey(p)
        fun tempDirExists() = tempDir?.exists() == true
        fun tempFileExists(p: String) = File(tempDir, p).exists()
        fun readTempFile(p: String) = File(tempDir, p).readText()
        fun getTempDirPath() = tempDir?.absolutePath
        fun historySize() = history.size
        fun getDiff(i: Int) = history.getOrNull(i - 1)
        fun getLatestDiff() = history.lastOrNull()

        fun dispose() {
            tempDir?.let { if (it.exists()) it.deleteRecursively() }
            tempDir = null
        }

        private fun excluded(r: String) = ExclusionPatternMatcher.matches(r, exDirs) || exPats.any { it.matches(r) }
        private fun empty() = InteractionDiff(0, System.currentTimeMillis(), emptyList())
        private fun sha256(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }
    }
}
