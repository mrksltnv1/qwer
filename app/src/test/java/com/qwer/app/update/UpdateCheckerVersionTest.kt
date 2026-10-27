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
    fun `current scheme maps tags to shipped versionCodes`() {
        // 0.9.0 and later: major*10000 + minor*100 + patch (patch increments by 1).
        assertEquals(800, UpdateChecker.tagToVersionCode("0.8.0"))
        assertEquals(900, UpdateChecker.tagToVersionCode("0.9.0"))
        assertEquals(907, UpdateChecker.tagToVersionCode("0.9.7"))
        assertEquals(908, UpdateChecker.tagToVersionCode("0.9.8"))
        assertEquals(10000, UpdateChecker.tagToVersionCode("1.0.0"))
        assertEquals(10100, UpdateChecker.tagToVersionCode("1.1.0"))
    }

    @Test
    fun `versionCode alone cannot compare across the scheme change`() {
        // Documents WHY the comparison moved to version names: 0.8.2 shipped as 820 in
        // the old scheme, while 0.9.8 ships as 908. Any single formula mismatches half
        // the history, which is what produced "update to the build you already run".
        assertEquals(802, UpdateChecker.tagToVersionCode("0.8.2"))
        assertEquals(908, UpdateChecker.tagToVersionCode("0.9.8"))
    }

    // ------------------------------------------------------------------ name comparison

    @Test
    fun `the running version is never newer than itself`() {
        // The reported bug: 0.9.7 and 0.9.8 offered an update to themselves.
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.7", "0.9.7"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.8", "0.9.8"))
        assertEquals(false, UpdateChecker.isNewerVersion("v0.9.8", "0.9.8"))
    }

    @Test
    fun `a real patch release is offered`() {
        assertEquals(true, UpdateChecker.isNewerVersion("0.9.9", "0.9.8"))
        assertEquals(true, UpdateChecker.isNewerVersion("0.10.0", "0.9.8"))
        assertEquals(true, UpdateChecker.isNewerVersion("1.0.0", "0.9.8"))
    }

    @Test
    fun `an older release is not offered`() {
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.7", "0.9.8"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.8.9", "0.9.0"))
    }

    @Test
    fun `minor version sorts numerically not lexically`() {
        // "0.10.0" > "0.9.8" numerically, but lexically "0.10.0" < "0.9.8".
        assertEquals(true, UpdateChecker.isNewerVersion("0.10.0", "0.9.8"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.8", "0.10.0"))
    }

    @Test
    fun `missing components are treated as zero`() {
        assertEquals(false, UpdateChecker.isNewerVersion("0.9", "0.9.0"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.0", "0.9"))
        assertEquals(true, UpdateChecker.isNewerVersion("0.9.1", "0.9"))
    }

    @Test
    fun `prerelease suffix does not change the comparison`() {
        assertEquals(true, UpdateChecker.isNewerVersion("0.9.9-beta", "0.9.8"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.8-rc1", "0.9.8"))
    }

    @Test
    fun `unparseable versions never report an update`() {
        assertEquals(false, UpdateChecker.isNewerVersion("latest", "0.9.8"))
        assertEquals(false, UpdateChecker.isNewerVersion("0.9.9", "garbage"))
    }

    @Test
    fun `parseVersion extracts numeric components`() {
        assertEquals(listOf(0, 9, 8), UpdateChecker.parseVersion("0.9.8"))
        assertEquals(listOf(0, 9, 8), UpdateChecker.parseVersion("v0.9.8"))
        assertEquals(listOf(0, 9, 8), UpdateChecker.parseVersion("0.9.8-beta"))
        assertNull(UpdateChecker.parseVersion("latest"))
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
