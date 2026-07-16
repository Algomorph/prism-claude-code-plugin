package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.services.ClaudeValidationService.VersionGate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeRuntimeGateTest {

    @Test
    fun `version comparison orders numerically not lexically`() {
        assertTrue(VersionGate.compareVersions("2.1.210", "2.1.99") > 0)
        assertTrue(VersionGate.compareVersions("2.1.193", "2.1.193") == 0)
        assertTrue(VersionGate.compareVersions("2.0.0", "2.1.0") < 0)
        assertTrue(VersionGate.compareVersions("10.0.0", "9.9.9") > 0)
    }

    @Test
    fun `minimum version floor is enforced`() {
        assertTrue(VersionGate.meetsMinimumVersion("2.1.210"))
        assertTrue(VersionGate.meetsMinimumVersion("2.1.193"))
        assertFalse(VersionGate.meetsMinimumVersion("2.1.100"))
        assertFalse(VersionGate.meetsMinimumVersion("1.9.0"))
        assertFalse(VersionGate.meetsMinimumVersion(null))
    }
}
