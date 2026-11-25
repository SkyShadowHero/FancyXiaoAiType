package io.github.skyshadowhero.fancypad

import io.github.skyshadowhero.fancypad.data.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesProjectReleaseTags() {
        assertEquals(SemanticVersion(1, 1, 5), SemanticVersion.parse("Pad-1.1.5"))
        assertEquals(SemanticVersion(1, 1, 6), SemanticVersion.parse("1.1.6"))
    }

    @Test
    fun comparesNumericComponents() {
        assertTrue(SemanticVersion.parse("1.1.10")!! > SemanticVersion.parse("1.1.6")!!)
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.99.99")!!)
    }

    @Test
    fun stableVersionIsNewerThanPreRelease() {
        assertTrue(SemanticVersion.parse("1.2.0")!! > SemanticVersion.parse("1.2.0-beta.4")!!)
        assertTrue(SemanticVersion.parse("1.2.0-beta.10")!! > SemanticVersion.parse("1.2.0-beta.2")!!)
    }

    @Test
    fun rejectsUnknownTags() {
        assertNull(SemanticVersion.parse("latest"))
    }
}
