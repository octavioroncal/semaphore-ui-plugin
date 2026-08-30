package com.oroncal.semaphoreui.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesSnapshotVersion() {
        val version = SemanticVersion.parse("1.2.3-SNAPSHOT")

        assertEquals(SemanticVersion(1, 2, 3, true), version)
    }

    @Test
    fun supportsShortNumericVersions() {
        val version = SemanticVersion.parse("2.4")

        assertEquals(SemanticVersion(2, 4, 0, false), version)
    }

    @Test
    fun rejectsUnsupportedQualifiers() {
        assertNull(SemanticVersion.parse("1.2.3-beta1"))
    }

    @Test
    fun proposesReleaseNumbersFromSnapshot() {
        val version = SemanticVersion.parse("1.2.3-SNAPSHOT")!!

        assertEquals("1.2.3", version.propose(ReleaseKind.FIX).toString())
        assertEquals("1.3.0", version.propose(ReleaseKind.MINOR).toString())
        assertEquals("2.0.0", version.propose(ReleaseKind.MAJOR).toString())
    }

    @Test
    fun proposesReleaseNumbersFromReleasedVersion() {
        val version = SemanticVersion.parse("1.2.3")!!

        assertEquals("1.2.4", version.propose(ReleaseKind.FIX).toString())
        assertEquals("1.3.0", version.propose(ReleaseKind.MINOR).toString())
        assertEquals("2.0.0", version.propose(ReleaseKind.MAJOR).toString())
    }
}
