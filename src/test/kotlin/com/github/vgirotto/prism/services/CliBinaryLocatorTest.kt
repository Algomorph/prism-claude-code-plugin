package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliBinaryLocatorTest {

    @Test
    fun `locate returns first existing candidate path`(@TempDir tmp: Path) {
        val fake = tmp.resolve("fake-cli")
        Files.writeString(fake, "#!/bin/sh\nexit 0\n")
        fake.toFile().setExecutable(true)

        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf(
                "/definitely/missing/fake-cli",
                fake.toString(),
            ),
        )
        assertEquals(fake.toString(), locator.locate())
        assertTrue(locator.exists())
    }

    @Test
    fun `locate skips non-executable candidate paths`(@TempDir tmp: Path) {
        val nonExec = tmp.resolve("not-exec")
        Files.writeString(nonExec, "")
        nonExec.toFile().setExecutable(false)

        val locator = CliBinaryLocator(
            binaryName = "missing",
            candidatePaths = listOf(nonExec.toString()),
        )
        // No PATH fallback hit since binary name shouldn't exist.
        assertNull(locator.locate())
        assertFalse(locator.exists())
    }

    @Test
    fun `locate falls back to which on PATH for sh`() {
        // `sh` is present on every POSIX system; reuse it as a stand-in binary.
        val locator = CliBinaryLocator(
            binaryName = "sh",
            candidatePaths = listOf("/nonexistent/sh"),
        )
        val path = locator.locate()
        assertNotNull(path)
        assertTrue(path!!.endsWith("/sh"))
    }

    @Test
    fun `expandHome replaces leading tilde with user home`() {
        val expanded = CliBinaryLocator.expandHome("~/foo/bar")
        assertEquals("${System.getProperty("user.home")}/foo/bar", expanded)
    }

    @Test
    fun `expandHome leaves non-tilde paths untouched`() {
        assertEquals("/abs/path", CliBinaryLocator.expandHome("/abs/path"))
        assertEquals("relative/path", CliBinaryLocator.expandHome("relative/path"))
    }
}
