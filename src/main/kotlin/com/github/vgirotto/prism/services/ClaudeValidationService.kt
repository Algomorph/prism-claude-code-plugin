package com.github.vgirotto.prism.services

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Service for validating Claude CLI installation and process health.
 */
class ClaudeValidationService {

    private val log = Logger.getInstance(ClaudeValidationService::class.java)

    private val locator get() = LOCATOR

    /** True if the Claude CLI is available in known locations or on PATH. */
    fun isClaudeAvailable(): Boolean = locator.exists()

    /**
     * True if [configuredPath] (typically [AgentSettingsState.claudePath])
     * resolves to a runnable binary, either directly or via PATH lookup.
     */
    fun isClaudeAvailable(configuredPath: String): Boolean = locator.canResolve(configuredPath)

    /** Full path to the Claude CLI, or null if it cannot be located. */
    fun getClaudePath(): String? = locator.locate()

    /** Resolves [configuredPath] to a runnable binary, or null if it cannot. */
    fun getClaudePath(configuredPath: String): String? = locator.resolve(configuredPath)

    /**
     * Validates Claude CLI version (basic check that it responds to help).
     * @return true if Claude responds to --version or --help
     */
    fun validateClaudeVersion(): Boolean {
        return try {
            val process = ProcessBuilder("claude", "--version").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            log.debug("Claude version validation failed", e)
            false
        }
    }

    /**
     * Runtime-compatibility gate for the hybrid chat shell (design §6.5, §9, R19). The
     * feature depends on deterministic session identity (`claude --session-id <uuid>`) and
     * the tested JSONL schema. The **capability probe is authoritative** (does `--help`
     * advertise `--session-id`?); the version string is only a fallback. When this returns
     * false the strip/terminal keep working and the transcript pane shows an explicit
     * "unavailable for this CLI version" state — never a guessed or broken file.
     */
    fun supportsDeterministicSessions(claudeCommand: String = "claude"): Boolean {
        // Primary: capability probe.
        val help = runProbe(listOf(claudeCommand, "--help"), 8)
        if (help != null && help.contains("--session-id")) {
            log.debug("Claude advertises --session-id — deterministic sessions supported")
            return true
        }
        // Fallback: version floor — probe the SAME configured command, not PATH's claude.
        return VersionGate.meetsMinimumVersion(getClaudeVersion(claudeCommand))
    }

    /** Parse the semantic version from `claude --version` (e.g. "2.1.210 (Claude Code)"). */
    fun getClaudeVersion(claudeCommand: String = "claude"): String? {
        val out = runProbe(listOf(claudeCommand, "--version"), 5) ?: return null
        return Regex("""(\d+)\.(\d+)\.(\d+)""").find(out)?.value
    }

    /**
     * Run a short-lived probe command and return its combined output, or null on failure.
     * The output is drained on a daemon thread so a child that never closes stdout cannot
     * block us past [timeoutSec]; on timeout the process is force-killed (review #11 — a
     * bare `readText()` before `waitFor` can hang indefinitely).
     */
    private fun runProbe(command: List<String>, timeoutSec: Long): String? {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val drain = Thread {
                try {
                    process.inputStream.bufferedReader().use { r -> r.forEachLine { sb.appendLine(it) } }
                } catch (_: Exception) { /* stream closed on kill */ }
            }.apply { isDaemon = true; start() }
            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                log.debug("Probe timed out: ${command.joinToString(" ")}")
                return null
            }
            drain.join(1000)
            sb.toString()
        } catch (e: Exception) {
            log.debug("Probe failed: ${command.joinToString(" ")}", e)
            null
        }
    }

    object VersionGate {
        /** Tentative floor set in the Group 0 spike (first schema carrying uuid/parentUuid). */
        const val MIN_SUPPORTED_VERSION = "2.1.193"

        fun meetsMinimumVersion(version: String?): Boolean {
            if (version == null) return false
            return compareVersions(version, MIN_SUPPORTED_VERSION) >= 0
        }

        /** Numeric dotted-version comparison; returns <0, 0, >0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".").mapNotNull { it.toIntOrNull() }
            val pb = b.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }

    /**
     * Checks if a process is still alive and reports errors if dead.
     * @return true if process is alive, false if dead
     */
    fun isProcessAlive(process: Process): Boolean {
        return try {
            process.isAlive
        } catch (e: Exception) {
            log.warn("Error checking process alive status", e)
            false
        }
    }

    /**
     * Generates a user-friendly error message for Claude not being found.
     */
    fun getClaudeNotFoundMessage(): String {
        return """
            |Claude Code is not installed.
            |
            |Install it with:
            |  npm install -g @anthropic-ai/claude-code
            |
            |Then just start a new session — no IDE restart needed.
            |
            |If it still isn't found, restart the IDE so it re-reads your
            |shell's PATH, or set the full path in Settings > Prism.
        """.trimMargin()
    }

    /**
     * Generates error message for when a session dies unexpectedly.
     */
    fun getSessionDiedMessage(): String {
        return """
            |Claude session ended unexpectedly.
            |
            |This may happen due to:
            |  • Out of memory (OOM)
            |  • Process crash
            |  • System resource limits
            |
            |Try restarting the session.
        """.trimMargin()
    }

    companion object {
        private val LOCATOR = CliBinaryLocator(
            binaryName = "claude",
            candidatePaths = listOf(
                "~/.local/bin/claude",       // npm install -g default on Linux/Mac
                "~/.npm-global/bin/claude",  // alternative npm global directory
                "/usr/local/bin/claude",     // Homebrew on Intel Mac
                "/opt/homebrew/bin/claude",  // Homebrew on Apple Silicon Mac
                "/usr/bin/claude",
            ),
        )

        private val INSTANCE = ClaudeValidationService()

        fun getInstance(): ClaudeValidationService = INSTANCE
    }
}
