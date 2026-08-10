package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliBinaryLocatorTest {

    @Test
    fun `locate prefers PATH over a candidate path when both resolve`(@TempDir tmp: Path) {
        // The user's PATH is their declared intent — a version-manager shim lives there,
        // while a stale global install can linger in a candidate location forever.
        val stale = executable(Files.createDirectory(tmp.resolve("stale")), "fake-cli")
        val onPathDir = Files.createDirectory(tmp.resolve("bin"))
        val current = executable(onPathDir, "fake-cli")

        val locator = locator(
            binaryName = "fake-cli",
            candidatePaths = listOf(stale.toString()),
            pathEntries = listOf(onPathDir.toString()),
        )
        assertEquals(current.toString(), locator.locate())
    }

    @Test
    fun `locate falls back to a candidate path when the binary is not on PATH`(@TempDir tmp: Path) {
        val fake = executable(tmp, "fake-cli")

        val locator = locator(
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

        val locator = locator(binaryName = "missing", candidatePaths = listOf(nonExec.toString()))
        assertNull(locator.locate())
        assertFalse(locator.exists())
    }

    @Test
    fun `locate finds the binary on PATH when no candidate matches`(@TempDir tmp: Path) {
        val onPath = Files.createDirectory(tmp.resolve("bin"))
        val fake = executable(onPath, "fake-cli")

        val locator = locator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/definitely/missing/fake-cli"),
            pathEntries = listOf("/definitely/missing/bin", onPath.toString()),
        )
        assertEquals(fake.toString(), locator.locate())
    }

    @Test
    fun `locate ignores a PATH entry that is a directory sharing the binary name`(@TempDir tmp: Path) {
        // A directory is executable as far as the filesystem is concerned, so a bare
        // exists() check would hand back a path that cannot be launched.
        val shadow = Files.createDirectory(tmp.resolve("bin"))
        Files.createDirectory(shadow.resolve("fake-cli"))

        val locator = locator(
            binaryName = "fake-cli",
            candidatePaths = emptyList(),
            pathEntries = listOf(shadow.toString()),
        )
        assertNull(locator.locate())
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
        val custom = executable(tmp, "custom-cli")

        val locator = locator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/definitely/missing/fake-cli"),
        )

        assertEquals(custom.toString(), locator.resolve(custom.toString()))
        assertTrue(locator.canResolve(custom.toString()))
    }

    @Test
    fun `resolve rejects a configured absolute path that doesn't exist`() {
        val locator = locator(
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
        val custom = executable(tmp, "custom-cli")

        val locator = locator(binaryName = "fake-cli", candidatePaths = emptyList())

        if (custom.toString().startsWith(home)) {
            val withTilde = "~" + custom.toString().removePrefix(home)
            assertEquals(custom.toString(), locator.resolve(withTilde))
        } else {
            assertEquals(custom.toString(), locator.resolve(custom.toString()))
        }
    }

    @Test
    fun `resolve falls back to candidate paths when configured value equals default name`(@TempDir tmp: Path) {
        val fake = executable(tmp, "fake-cli")

        val locator = locator(binaryName = "fake-cli", candidatePaths = listOf(fake.toString()))
        // configuredPath == default → locate() path returns the candidate hit.
        assertEquals(fake.toString(), locator.resolve("fake-cli"))
    }

    @Test
    fun `resolve falls back to candidate paths when configured value is blank`(@TempDir tmp: Path) {
        val fake = executable(tmp, "fake-cli")

        val locator = locator(binaryName = "fake-cli", candidatePaths = listOf(fake.toString()))
        assertEquals(fake.toString(), locator.resolve(""))
        assertEquals(fake.toString(), locator.resolve("   "))
    }

    @Test
    fun `resolve looks up a bare name override on PATH`(@TempDir tmp: Path) {
        val onPath = Files.createDirectory(tmp.resolve("bin"))
        val override = executable(onPath, "other-cli")

        val locator = locator(
            binaryName = "fake-cli",
            candidatePaths = listOf("/definitely/missing/fake-cli"),
            pathEntries = listOf(onPath.toString()),
        )
        assertEquals(override.toString(), locator.resolve("other-cli"))
    }

    @Test
    fun `an install mid-session is picked up by the next lookup`(@TempDir tmp: Path) {
        val fake = tmp.resolve("fake-cli")
        val locator = locator(binaryName = "fake-cli", candidatePaths = listOf(fake.toString()))
        assertNull(locator.locate())

        // Installed, or upgraded in place, with nothing to invalidate a cache: an
        // in-place `npm install -g` briefly unlinks the binary, and a remembered miss
        // would outlive the upgrade and report the CLI as absent.
        executable(tmp, "fake-cli")
        assertEquals(fake.toString(), locator.locate())
    }

    @Test
    fun `an uninstall mid-session stops resolving`(@TempDir tmp: Path) {
        val fake = executable(tmp, "fake-cli")
        val locator = locator(binaryName = "fake-cli", candidatePaths = listOf(fake.toString()))
        assertEquals(fake.toString(), locator.locate())

        // Handing back a remembered path here would launch a binary that is gone.
        Files.delete(fake)
        assertNull(locator.locate())
        assertFalse(locator.exists())
    }



    /**
     * PATH is always injected: the production default reads the login-shell
     * environment through the platform, which needs a running IDE, and a test that
     * leaned on the JVM's own PATH would pass or fail with the machine it runs on.
     */
    private fun locator(
        binaryName: String,
        candidatePaths: List<String>,
        pathEntries: List<String> = emptyList(),
    ) = CliBinaryLocator(
        binaryName = binaryName,
        candidatePaths = candidatePaths,
        pathEntries = { pathEntries },
    )

    private fun executable(dir: Path, name: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, "#!/bin/sh\nexit 0\n")
        file.toFile().setExecutable(true)
        return file
    }
}
