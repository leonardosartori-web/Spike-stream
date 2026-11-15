package com.leonardos.spikestream.utils

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun disabledPolicyAllowsExistingVersions() {
        assertFalse(UpdatePolicy.requiresUpdate(59, UpdatePolicy.minimumVersion("0")))
    }
    @Test fun minimumIsInclusive() {
        assertFalse(UpdatePolicy.requiresUpdate(59, UpdatePolicy.minimumVersion("59")))
        assertTrue(UpdatePolicy.requiresUpdate(58, UpdatePolicy.minimumVersion("59")))
        assertFalse(UpdatePolicy.requiresUpdate(60, UpdatePolicy.minimumVersion("59")))
    }
    @Test fun invalidValuesDoNotLockUsersOut() {
        listOf("", "oops", "-1", "59.5", "999999999999999999999999", "2100000001").forEach {
            assertEquals(it, 0L, UpdatePolicy.minimumVersion(it))
        }
    }
    @Test fun rollbackReleasesAnUnsupportedBuild() {
        assertTrue(UpdatePolicy.requiresUpdate(59, UpdatePolicy.minimumVersion("60")))
        assertFalse(UpdatePolicy.requiresUpdate(59, UpdatePolicy.minimumVersion("59")))
        assertFalse(UpdatePolicy.requiresUpdate(59, UpdatePolicy.minimumVersion("0")))
    }
    @Test fun acceptsWhitespaceAndPlayLimit() {
        assertEquals(59L, UpdatePolicy.minimumVersion(" 59 "))
        assertEquals(2100000000L, UpdatePolicy.minimumVersion("2100000000"))
    }
}
