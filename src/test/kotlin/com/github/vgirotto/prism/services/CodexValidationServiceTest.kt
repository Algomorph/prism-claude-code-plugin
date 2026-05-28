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

    @Test
    fun `isCodexAvailable honors a configured absolute path`(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        val custom = tmp.resolve("custom-codex")
        java.nio.file.Files.writeString(custom, "#!/bin/sh\nexit 0\n")
        custom.toFile().setExecutable(true)

        assertTrue(
            validator.isCodexAvailable(custom.toString()),
            "Validator should accept a user-configured absolute path outside known locations",
        )
        assertEquals(custom.toString(), validator.getCodexPath(custom.toString()))
    }

    @Test
    fun `isCodexAvailable rejects a configured path that doesn't exist`() {
        assertFalse(validator.isCodexAvailable("/no/such/codex/binary"))
        assertNull(validator.getCodexPath("/no/such/codex/binary"))
    }
}
