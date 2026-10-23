package com.qwer.app.core

import com.qwer.app.diagnostics.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScrubTest {

    @Test
    fun `wireguard keys are redacted`() {
        val key = "aBcD1234567890aBcD1234567890aBcD1234567890A="
        val out = Diagnostics.scrub("PrivateKey = $key here")
        assertFalse("key leaked: $out", out.contains(key))
        assertTrue(out.contains("<key>"))
    }

    @Test
    fun `long hex tokens are redacted`() {
        val token = "446c8b10c5ba00304768e6084a1ec9f18fd7eb9f23747088689aa9ea1fdf048c"
        val out = Diagnostics.scrub("authorization $token")
        assertFalse("token leaked: $out", out.contains(token))
        assertTrue(out.contains("<token>"))
    }

    @Test
    fun `token query parameter is redacted`() {
        val out = Diagnostics.scrub("qwer://import?token=abcDEF12345678901234567890")
        assertFalse(out.contains("abcDEF12345678901234567890"))
        assertTrue(out.contains("token=<redacted>"))
    }

    @Test
    fun `ordinary text is untouched`() {
        assertEquals("probe ok fails=0", Diagnostics.scrub("probe ok fails=0"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", Diagnostics.scrub(""))
    }

    @Test
    fun `shake and counter round trip`() {
        val s = com.qwer.app.failover.HealthSample(1000, true, true, 10, 20)
        assertEquals(1000L, s.nowMillis)
    }
}
