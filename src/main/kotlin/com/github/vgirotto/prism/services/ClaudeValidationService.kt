package com.github.vgirotto.prism.services

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Service for validating Claude CLI installation and process health.
 */
class ClaudeValidationService {

    private val log = Logger.getInstance(ClaudeValidationService::class.java)

    private val locator = CliBinaryLocator(
        binaryName = "claude",
        candidatePaths = listOf(
            "~/.local/bin/claude",       // npm install -g default on Linux/Mac
            "~/.npm-global/bin/claude",  // alternative npm global directory
            "/usr/local/bin/claude",     // Homebrew on Intel Mac
            "/opt/homebrew/bin/claude",  // Homebrew on Apple Silicon Mac
            "/usr/bin/claude",
        ),
    )

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
            |After installation, restart the IDE.
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
        fun getInstance(): ClaudeValidationService = ClaudeValidationService()
    }
}
