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
        return whichOnPath()
    }

    /** True if [locate] would return a non-null path. */
    fun exists(): Boolean = locate() != null

    private fun whichOnPath(): String? {
        return try {
            val process = ProcessBuilder("which", binaryName).start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                val path = process.inputStream.bufferedReader().readText().trim()
                path.takeIf { it.isNotEmpty() }?.also {
                    log.debug("Found $binaryName via which: $it")
                }
            } else null
        } catch (e: Exception) {
            log.debug("which lookup failed for $binaryName", e)
            null
        }
    }

    companion object {
        fun expandHome(path: String): String =
            if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
    }
}
