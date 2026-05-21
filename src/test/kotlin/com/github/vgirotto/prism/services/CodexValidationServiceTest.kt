package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodexValidationServiceTest {

    private val validator = CodexValidationService.getInstance()

    @Test
    fun `not-found message references npm install command`() {
        val message = validator.getCodexNotFoundMessage()
        assertTrue(message.contains("Codex CLI is not installed"))
        assertTrue(message.contains("npm install -g @openai/codex"))
    }

    @Test
    fun `isCodexAvailable returns a non-null boolean`() {
        // Depends on host state; we just want to confirm the lookup runs without throwing.
        val available = validator.isCodexAvailable()
        assertNotNull(available)
    }

    @Test
    fun `getCodexPath returns null or an existing executable path`() {
        val path = validator.getCodexPath()
        if (path != null) {
            val file = java.io.File(path)
            assertTrue(file.exists(), "Reported codex path should exist: $path")
            assertTrue(file.canExecute(), "Reported codex path should be executable: $path")
        }
    }
}
