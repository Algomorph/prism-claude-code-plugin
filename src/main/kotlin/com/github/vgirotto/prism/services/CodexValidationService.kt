package com.github.vgirotto.prism.services

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Service for validating Codex CLI installation and process health.
 *
 * Mirrors [ClaudeValidationService]; both delegate path lookup to
 * [CliBinaryLocator] so the search strategy stays consistent.
 */
class CodexValidationService {

    private val log = Logger.getInstance(CodexValidationService::class.java)

    private val locator = CliBinaryLocator(
        binaryName = "codex",
        candidatePaths = listOf(
            "~/.local/bin/codex",       // pipx / user-local install on Linux/Mac
            "~/.npm-global/bin/codex",  // alternative npm global directory
            "/usr/local/bin/codex",     // Homebrew on Intel Mac
            "/opt/homebrew/bin/codex",  // Homebrew on Apple Silicon Mac
            "/usr/bin/codex",
        ),
    )

    /** True if the Codex CLI is available in known locations or on PATH. */
    fun isCodexAvailable(): Boolean = locator.exists()

    /** Full path to the Codex CLI, or null if it cannot be located. */
    fun getCodexPath(): String? = locator.locate()

    fun validateCodexVersion(): Boolean {
        return try {
            val process = ProcessBuilder("codex", "--version").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (e: Exception) {
            log.debug("Codex version validation failed", e)
            false
        }
    }

    fun getCodexNotFoundMessage(): String {
        return """
            |Codex CLI is not installed.
            |
            |Install it with:
            |  npm install -g @openai/codex
            |
            |After installation, restart the IDE.
        """.trimMargin()
    }

    companion object {
        fun getInstance(): CodexValidationService = CodexValidationService()
    }
}
