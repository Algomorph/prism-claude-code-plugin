package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ChangeStatus
import com.github.vgirotto.prism.model.FileDiffEntry
import com.github.vgirotto.prism.model.InteractionDiff
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Snapshot service using temp files on disk instead of in-memory content.
 *
 * All snapshot/diff operations are serialized through a single-thread executor
 * to prevent race conditions when multiple sessions operate concurrently.
 */
@Service(Service.Level.PROJECT)
class FileSnapshotService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(FileSnapshotService::class.java)

    /**
     * Single-thread executor serializes all snapshot/diff operations.
     * Prevents ConcurrentModificationException and file corruption
     * when multiple sessions trigger snapshots simultaneously.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClaudeSnapshotWorker").apply { isDaemon = true }
    }

    /** Hash index: relativePath → sha256. Kept in memory (~100 bytes/entry) */
    private var snapshotHashes: Map<String, String> = emptyMap()

    /** Temp directory containing copies of files at snapshot time */
    private var snapshotDir: File? = null

    /** Files changed during current interaction (from VFS listener) */
    private val changedPaths = mutableSetOf<String>()

    private val diffHistory = mutableListOf<InteractionDiff>()
    private var interactionCounter = 0

    /** Name of the session that triggered the last snapshot */
    @Volatile
    var lastSessionName: String = ""

    private val excludeFilePatterns = listOf(
        Regex(".*\\.iml$"),
        Regex(".*\\.class$"),
        Regex(".*\\.jar$"),
        Regex(".*\\.pyc$"),
        Regex("\\.DS_Store"),
    )

    private val mandatoryExcludePatterns = listOf(
        ".git",
    )

    private fun getExcludePatterns(): List<String> =
        ClaudeSettingsState.getInstance().getExcludedPatterns()

    private fun getMaxFileSize(): Long =
        ClaudeSettingsState.getInstance().maxFileSizeKb.toLong() * 1024

    /**
     * Takes a snapshot, serialized through the executor.
     * Safe to call from any thread — blocks until complete.
     */
    fun takeSnapshot() {
        submitAndWait { takeSnapshotInternal() }
    }

    /**
     * Computes diff, serialized through the executor.
     * Safe to call from any thread — blocks until complete.
     */
    fun computeDiff(): InteractionDiff {
        return submitAndGet { computeDiffInternal() } ?: emptyDiff()
    }

    fun recordChange(path: String) {
        val basePath = project.basePath ?: return
        if (path.startsWith(basePath)) {
            val relativePath = path.removePrefix(basePath).removePrefix("/")
            if (!isExcluded(relativePath)) {
                synchronized(changedPaths) {
                    changedPaths.add(relativePath)
                }
            }
        }
    }

    fun saveOriginalContent(absolutePath: String) { /* no-op */ }

    fun getDiffHistory(): List<InteractionDiff> = synchronized(diffHistory) { diffHistory.toList() }
    fun getLatestDiff(): InteractionDiff? = synchronized(diffHistory) { diffHistory.lastOrNull() }
    fun getDiff(index: Int): InteractionDiff? = synchronized(diffHistory) { diffHistory.getOrNull(index - 1) }

    /**
     * Forces a full re-snapshot of the current project state without incrementing interactionCounter
     * or touching diffHistory. Called when a new session is created so its baseline reflects the
     * current project state (including changes from previous sessions), preventing the idle listener
     * from computing a spurious diff that duplicates the previous session's interactions.
     */
    fun resetSnapshot() {
        submitAndWait { resetSnapshotInternal() }
    }

    /**
     * Clears the diff history and resets the interaction counter.
     * Called by the "Clear Interactions" button in the UI.
     */
    fun clearHistory() {
        submitAndWait {
            synchronized(diffHistory) {
                diffHistory.clear()
                interactionCounter = 0
            }
            synchronized(changedPaths) { changedPaths.clear() }
        }
    }

    fun revertEntry(entry: FileDiffEntry): Boolean {
        val basePath = project.basePath ?: return false
        val fullPath = "$basePath/${entry.path}"
        return try {
            val action = Runnable {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        when (entry.status) {
                            ChangeStatus.MODIFIED -> {
                                val content = entry.originalContent ?: return@runWriteAction
                                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                                    ?.setBinaryContent(content)
                            }
                            ChangeStatus.ADDED -> {
                                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                                    ?.delete(this)
                            }
                            ChangeStatus.DELETED -> {
                                val content = entry.originalContent ?: return@runWriteAction
                                val parentDir = VfsUtil.createDirectoryIfMissing(File(fullPath).parent)
                                parentDir?.createChildData(this, File(fullPath).name)
                                    ?.setBinaryContent(content)
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("Revert failed: ${entry.path}", e)
                    }
                }
            }
            if (ApplicationManager.getApplication().isDispatchThread) action.run()
            else ApplicationManager.getApplication().invokeAndWait(action)
            true
        } catch (e: Exception) {
            log.warn("Revert failed: ${entry.path}", e)
            false
        }
    }

    fun revertInteraction(diff: InteractionDiff): Int {
        var count = 0
        for (entry in diff.changes) if (revertEntry(entry)) count++
        project.basePath?.let {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(it)?.refresh(true, true)
        }
        return count
    }

    override fun dispose() {
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            executor.shutdownNow()
        }
        cleanupSnapshotDir()
    }

    // ── Internal operations (run on executor thread) ──

    private fun resetSnapshotInternal() {
        cleanupSnapshotDir()
        synchronized(changedPaths) { changedPaths.clear() }
        val basePath = project.basePath ?: return
        val baseDir = File(basePath)
        if (!baseDir.isDirectory) return
        val tempDir = Files.createTempDirectory("claude-snapshot-").toFile()
        val hashes = mutableMapOf<String, String>()
        fullCopy(baseDir, basePath, tempDir, hashes)
        snapshotDir = tempDir
        snapshotHashes = hashes
        log.info("Snapshot reset (full): ${hashes.size} files → ${tempDir.name}")
    }

    private fun takeSnapshotInternal() {
        val basePath = project.basePath ?: return
        val baseDir = File(basePath)
        if (!baseDir.isDirectory) return

        interactionCounter++

        try {
            LocalHistory.getInstance().putSystemLabel(
                project, "Claude Code — before interaction #$interactionCounter"
            )
        } catch (e: Exception) {
            log.debug("Failed to place Local History label", e)
        }

        if (snapshotDir == null || !snapshotDir!!.exists()) {
            val tempDir = Files.createTempDirectory("claude-snapshot-").toFile()
            val hashes = mutableMapOf<String, String>()
            fullCopy(baseDir, basePath, tempDir, hashes)
            snapshotDir = tempDir
            snapshotHashes = hashes
            log.info("Snapshot #$interactionCounter (full): ${hashes.size} files → ${tempDir.name}")
        } else {
            val updated = incrementalUpdate(basePath)
            log.info("Snapshot #$interactionCounter (incremental): $updated files updated, ${snapshotHashes.size} total")
        }

        synchronized(changedPaths) {
            changedPaths.clear()
        }
    }

    private fun computeDiffInternal(): InteractionDiff {
        val basePath = project.basePath ?: return emptyDiff()
        val tempDir = snapshotDir ?: return emptyDiff()
        if (snapshotHashes.isEmpty()) return emptyDiff()

        val changes = mutableListOf<FileDiffEntry>()

        val currentChangedPaths = synchronized(changedPaths) { changedPaths.toSet() }

        val pathsToCheck = pathsToCheck(currentChangedPaths)

        val checked = mutableSetOf<String>()
        for (relativePath in pathsToCheck) {
            if (!checked.add(relativePath) || isExcluded(relativePath)) continue

            val projectFile = File(basePath, relativePath)
            val tempFile = File(tempDir, relativePath)
            val oldHash = snapshotHashes[relativePath]

            if (!projectFile.exists()) {
                if (oldHash != null && tempFile.isFile) {
                    changes.add(FileDiffEntry(
                        relativePath, ChangeStatus.DELETED,
                        tempFile.readBytes(), null
                    ))
                }
            } else if (projectFile.isFile && projectFile.length() < 1_000_000) {
                try {
                    val currentBytes = projectFile.readBytes()
                    val currentHash = sha256(currentBytes)

                    if (oldHash == null) {
                        changes.add(FileDiffEntry(
                            relativePath, ChangeStatus.ADDED,
                            null, currentBytes
                        ))
                    } else if (oldHash != currentHash && tempFile.isFile) {
                        changes.add(FileDiffEntry(
                            relativePath, ChangeStatus.MODIFIED,
                            tempFile.readBytes(), currentBytes
                        ))
                    }
                } catch (e: Exception) {
                    log.debug("Failed to read: $relativePath", e)
                }
            }
        }

        for (relativePath in currentChangedPaths) {
            if (checked.contains(relativePath) || isExcluded(relativePath)) continue
            val file = File(basePath, relativePath)
            if (file.isFile && file.length() < 1_000_000 && snapshotHashes[relativePath] == null) {
                changes.add(FileDiffEntry(relativePath, ChangeStatus.ADDED, null, file.readBytes()))
            }
        }

        if (changes.isEmpty()) return emptyDiff()

        // Sync snapshot: remove deleted entries so they don't reappear in future diffs
        val deletedPaths = changes.filter { it.status == ChangeStatus.DELETED }.map { it.path }
        if (deletedPaths.isNotEmpty()) {
            val mutableHashes = snapshotHashes.toMutableMap()
            for (path in deletedPaths) {
                mutableHashes.remove(path)
                File(tempDir, path).let { if (it.exists()) it.delete() }
            }
            snapshotHashes = mutableHashes
            log.info("Pruned ${deletedPaths.size} deleted entries from snapshot index")
        }

        val diff = InteractionDiff(
            interactionIndex = diffHistory.size + 1,
            timestamp = System.currentTimeMillis(),
            changes = changes.sortedBy { it.path },
            sessionName = lastSessionName,
        )
        synchronized(diffHistory) {
            diffHistory.add(diff)
        }
        log.info("Diff: ${changes.size} changes (interaction #${diff.interactionIndex})")
        return diff
    }

    private fun fullCopy(
        dir: File, basePath: String, tempDir: File,
        hashes: MutableMap<String, String>,
    ) {
        val children = dir.listFiles() ?: return
        for (file in children) {
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")
            if (file.isDirectory) {
                if (isExcluded(relativePath)) continue
                fullCopy(file, basePath, tempDir, hashes)
                continue
            }
            if (!file.isFile || file.length() > getMaxFileSize()) continue
            if (isExcluded(relativePath)) continue

            try {
                val content = file.readBytes()
                hashes[relativePath] = sha256(content)
                val tempFile = File(tempDir, relativePath)
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(content)
            } catch (e: Exception) {
                log.debug("Failed to snapshot: $relativePath", e)
            }
        }
    }

    private fun incrementalUpdate(basePath: String): Int {
        val tempDir = snapshotDir ?: return 0
        val hashes = snapshotHashes.toMutableMap()
        var updated = 0

        val currentChangedPaths = synchronized(changedPaths) { changedPaths.toSet() }

        for (relativePath in currentChangedPaths) {
            if (isExcluded(relativePath)) continue

            val projectFile = File(basePath, relativePath)
            val tempFile = File(tempDir, relativePath)

            if (!projectFile.exists()) {
                if (tempFile.exists()) tempFile.delete()
                hashes.remove(relativePath)
                updated++
            } else if (projectFile.isFile && projectFile.length() < 1_000_000) {
                try {
                    val content = projectFile.readBytes()
                    val newHash = sha256(content)
                    val oldHash = hashes[relativePath]

                    if (oldHash != newHash) {
                        tempFile.parentFile?.mkdirs()
                        tempFile.writeBytes(content)
                        hashes[relativePath] = newHash
                        updated++
                    }
                } catch (e: Exception) {
                    log.debug("Failed to update: $relativePath", e)
                }
            }
        }

        for (relativePath in currentChangedPaths) {
            if (isExcluded(relativePath)) continue
            if (hashes.containsKey(relativePath)) continue

            val projectFile = File(basePath, relativePath)
            if (projectFile.isFile && projectFile.length() < 1_000_000) {
                try {
                    val content = projectFile.readBytes()
                    hashes[relativePath] = sha256(content)
                    val tempFile = File(tempDir, relativePath)
                    tempFile.parentFile?.mkdirs()
                    tempFile.writeBytes(content)
                    updated++
                } catch (e: Exception) {
                    log.debug("Failed to add new file: $relativePath", e)
                }
            }
        }

        snapshotHashes = hashes
        return updated
    }

    // ── Executor helpers ──

    private fun submitAndWait(task: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.submit(task).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn("Snapshot operation failed: ${e.message}", e)
        }
    }

    private fun <T> submitAndGet(task: () -> T): T? {
        if (executor.isShutdown) return null
        return try {
            executor.submit(task).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.warn("Snapshot operation failed: ${e.message}", e)
            null
        }
    }

    private fun cleanupSnapshotDir() {
        snapshotDir?.let { dir ->
            try {
                if (dir.exists()) dir.deleteRecursively()
            } catch (e: Exception) {
                log.debug("Failed to cleanup snapshot dir: ${dir.path}", e)
            }
        }
        snapshotDir = null
    }

    private fun isExcluded(path: String): Boolean {
        val patterns = getExcludePatterns()
        return ExclusionPatternMatcher.matches(path, mandatoryExcludePatterns) ||
            ExclusionPatternMatcher.matches(path, patterns) ||
            excludeFilePatterns.any { it.matches(path) }
    }

    private fun pathsToCheck(currentChangedPaths: Set<String>): Set<String> {
        if (currentChangedPaths.isEmpty()) {
            return snapshotHashes.keys.toSet()
        }

        val paths = currentChangedPaths.toMutableSet()
        val basePath = project.basePath ?: return paths
        for (relativePath in currentChangedPaths) {
            val projectFile = File(basePath, relativePath)
            if (projectFile.exists() && !projectFile.isDirectory) continue

            val prefix = relativePath.trimEnd('/') + "/"
            snapshotHashes.keys
                .asSequence()
                .filter { it.startsWith(prefix) }
                .forEach { paths.add(it) }
        }
        return paths
    }

    private fun emptyDiff() = InteractionDiff(0, System.currentTimeMillis(), emptyList())

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    companion object {
        fun getInstance(project: Project): FileSnapshotService =
            project.getService(FileSnapshotService::class.java)
    }
}
