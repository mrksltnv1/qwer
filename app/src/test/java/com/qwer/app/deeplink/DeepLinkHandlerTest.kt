package com.qwer.app.deeplink

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deep link parsing is pure logic over [Uri]; Robolectric supplies a real Uri
 * implementation without needing a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepLinkHandlerTest {

    private fun parse(raw: String) = DeepLinkHandler.parse(Uri.parse(raw))

    @Test
    fun `valid link yields an import request`() {
        val r = parse("qwer://import?token=abcDEF12345678901234567890")
        assertTrue("expected ImportRequest, got $r", r is DeepLinkHandler.Result.ImportRequest)
        assertEquals(
            "abcDEF12345678901234567890",
            (r as DeepLinkHandler.Result.ImportRequest).token,
        )
    }

    @Test
    fun `foreign scheme is not ours`() {
        assertEquals(
            DeepLinkHandler.Result.NotOurs,
            parse("https://example.com/import?token=abcDEF12345678901234567890"),
        )
    }

    @Test
    fun `foreign host is rejected`() {
        val r = parse("qwer://evil?token=abcDEF12345678901234567890")
        assertTrue(r is DeepLinkHandler.Result.Rejected)
    }

    @Test
    fun `missing token is rejected`() {
        val r = parse("qwer://import")
        assertTrue(r is DeepLinkHandler.Result.Rejected)
    }

    @Test
    fun `too short token is rejected`() {
        val r = parse("qwer://import?token=short")
        assertTrue(r is DeepLinkHandler.Result.Rejected)
    }

    @Test
    fun `token with illegal characters is rejected`() {
        val r = parse("qwer://import?token=abc<script>alert(1)</script>123456")
        assertTrue(r is DeepLinkHandler.Result.Rejected)
    }

    @Test
    fun `host comparison is case insensitive but scheme must match`() {
        val r = parse("qwer://IMPORT?token=abcDEF12345678901234567890")
        assertTrue(r is DeepLinkHandler.Result.ImportRequest)
    }

    @Test
    fun `hint is stable and does not reveal the token`() {
        val token = "abcDEF12345678901234567890"
        val hint = DeepLinkHandler.hint(token)
        assertEquals(8, hint.length)
        assertTrue(!hint.contains(token))
        assertEquals(hint, DeepLinkHandler.hint(token))
    }
}
