package com.qwer.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These tests exist because the version comparison was silently wrong: the app
 * declared versionCode 820 while the checker computed 802 for tag "0.8.2", so every
 * 0.8.x patch update was invisible.
 */
class UpdateCheckerVersionTest {

    @Test
    fun `patch releases map to the shipped versionCode scheme`() {
        // Values taken from the actual app/build.gradle.kts history.
        assertEquals(800, UpdateChecker.tagToVersionCode("0.8.0"))
        assertEquals(810, UpdateChecker.tagToVersionCode("0.8.1"))
        assertEquals(820, UpdateChecker.tagToVersionCode("0.8.2"))
        assertEquals(830, UpdateChecker.tagToVersionCode("0.8.3"))
    }

    @Test
    fun `minor and major bumps stay monotonic`() {
        assertEquals(900, UpdateChecker.tagToVersionCode("0.9.0"))
        assertEquals(10000, UpdateChecker.tagToVersionCode("1.0.0"))
        assertEquals(10100, UpdateChecker.tagToVersionCode("1.1.0"))
        assertTrue(
            "0.9.0 must sort after 0.8.9",
            UpdateChecker.tagToVersionCode("0.9.0")!! > UpdateChecker.tagToVersionCode("0.8.9")!!,
        )
    }

    @Test
    fun `v prefix is tolerated`() {
        assertEquals(820, UpdateChecker.tagToVersionCode("v0.8.2"))
        assertEquals(820, UpdateChecker.tagToVersionCode("V0.8.2"))
    }

    @Test
    fun `prerelease suffix keeps the numeric part`() {
        assertEquals(820, UpdateChecker.tagToVersionCode("0.8.2-beta"))
        assertEquals(820, UpdateChecker.tagToVersionCode("0.8.2-rc1"))
    }

    @Test
    fun `the current build does not see itself as newer`() {
        // versionCode 820 ships as 0.8.2; the same tag must NOT look like an update.
        val current = 820
        assertEquals(false, UpdateChecker.tagToVersionCode("0.8.2")!! > current)
    }

    @Test
    fun `the next patch IS seen as newer`() {
        // This is the regression that mattered: 0.8.3 must be offered as an update.
        val current = 820
        assertEquals(true, UpdateChecker.tagToVersionCode("0.8.3")!! > current)
    }

    @Test
    fun `malformed tags are rejected rather than guessed`() {
        assertNull(UpdateChecker.tagToVersionCode("latest"))
        assertNull(UpdateChecker.tagToVersionCode("0"))
        assertNull(UpdateChecker.tagToVersionCode("abc.def"))
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}
