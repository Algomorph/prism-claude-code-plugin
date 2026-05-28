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

    @Test
    fun `resolve accepts a custom absolute path outside the candidate list`(@TempDir tmp: Path) {
        val custom = tmp.resolve("custom-cli")
        Files.writeString(custom, "#!/bin/sh\nexit 0\n")
        custom.toFile().setExecutable(true)

        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/definitely/missing/fake-cli"),
        )

        assertEquals(custom.toString(), locator.resolve(custom.toString()))
        assertTrue(locator.canResolve(custom.toString()))
    }

    @Test
    fun `resolve rejects a configured absolute path that doesn't exist`() {
        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/definitely/missing/fake-cli"),
        )
        assertNull(locator.resolve("/no/such/binary"))
        assertFalse(locator.canResolve("/no/such/binary"))
    }

    @Test
    fun `resolve expands leading tilde in configured path`(@TempDir tmp: Path) {
        // We can only meaningfully test this if the candidate path lives under user.home;
        // fall back to a plain path-exists assertion otherwise.
        val home = System.getProperty("user.home")
        val custom = tmp.resolve("custom-cli")
        Files.writeString(custom, "#!/bin/sh\nexit 0\n")
        custom.toFile().setExecutable(true)

        val locator = CliBinaryLocator(binaryName = "fake-cli", candidatePaths = emptyList())

        if (custom.toString().startsWith(home)) {
            val withTilde = "~" + custom.toString().removePrefix(home)
            assertEquals(custom.toString(), locator.resolve(withTilde))
        } else {
            assertEquals(custom.toString(), locator.resolve(custom.toString()))
        }
    }

    @Test
    fun `resolve falls back to candidate paths when configured value equals default name`(@TempDir tmp: Path) {
        val fake = tmp.resolve("fake-cli")
        Files.writeString(fake, "#!/bin/sh\nexit 0\n")
        fake.toFile().setExecutable(true)

        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf(fake.toString()),
        )
        // configuredPath == default → locate() path returns the candidate hit.
        assertEquals(fake.toString(), locator.resolve("fake-cli"))
    }

    @Test
    fun `resolve falls back to candidate paths when configured value is blank`(@TempDir tmp: Path) {
        val fake = tmp.resolve("fake-cli")
        Files.writeString(fake, "#!/bin/sh\nexit 0\n")
        fake.toFile().setExecutable(true)

        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf(fake.toString()),
        )
        assertEquals(fake.toString(), locator.resolve(""))
        assertEquals(fake.toString(), locator.resolve("   "))
    }

    @Test
    fun `resolve looks up bare name override via PATH`() {
        // `sh` is present on every POSIX system; use it as a stand-in override
        // for a configured bare-name binary that differs from the default.
        val locator = CliBinaryLocator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/nonexistent/fake-cli"),
        )
        val resolved = locator.resolve("sh")
        assertNotNull(resolved)
        assertTrue(resolved!!.endsWith("/sh"))
    }
}
