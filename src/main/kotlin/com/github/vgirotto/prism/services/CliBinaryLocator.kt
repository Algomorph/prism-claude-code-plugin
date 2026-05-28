package com.github.vgirotto.prism.services

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates a CLI executable on the host machine by checking a list of
 * candidate paths, then falling back to `which <binary>` on PATH.
 *
 * Shared between per-CLI validation services (Claude, Codex, ...) so the
 * lookup behavior stays uniform across agents.
 */
class CliBinaryLocator(
    private val binaryName: String,
    private val candidatePaths: List<String>,
) {

    private val log = Logger.getInstance(CliBinaryLocator::class.java)

    /** Full path to the binary, or null if it is not found in any candidate or on PATH. */
    fun locate(): String? {
        for (path in candidatePaths) {
            val expanded = expandHome(path)
            val file = File(expanded)
            if (file.exists() && file.canExecute()) {
                log.debug("Found $binaryName at known path: $expanded")
                return expanded
            }
        }
        return whichOnPath(binaryName)
    }

    /** True if [locate] would return a non-null path. */
    fun exists(): Boolean = locate() != null

    /**
     * Resolves a user-configured CLI path entry from settings:
     *   - blank or equal to the default [binaryName] → fall back to [locate].
     *   - contains a `/` (or `~`) → treat as a filesystem path and check
     *     directly (after expanding `~`).
     *   - bare name (different from the default) → `which` lookup using
     *     that name; candidate paths are skipped since they're tied to
     *     the default binary.
     */
    fun resolve(configuredPath: String): String? {
        val trimmed = configuredPath.trim()
        if (trimmed.isEmpty() || trimmed == binaryName) return locate()
        val expanded = expandHome(trimmed)
        if (expanded.contains('/')) {
            val file = File(expanded)
            return if (file.exists() && file.canExecute()) expanded else null
        }
        return whichOnPath(trimmed)
    }

    /** True if [resolve] would return a non-null path for [configuredPath]. */
    fun canResolve(configuredPath: String): Boolean = resolve(configuredPath) != null

    private fun whichOnPath(name: String): String? {
        return try {
            val process = ProcessBuilder("which", name).start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                val path = process.inputStream.bufferedReader().readText().trim()
                path.takeIf { it.isNotEmpty() }?.also {
                    log.debug("Found $name via which: $it")
                }
            } else null
        } catch (e: Exception) {
            log.debug("which lookup failed for $name", e)
            null
        }
    }

    companion object {
        fun expandHome(path: String): String =
            if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
    }
}
