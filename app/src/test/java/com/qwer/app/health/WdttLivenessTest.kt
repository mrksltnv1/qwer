package com.qwer.app.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WDTT cannot be judged by a socket probe: the app excludes itself from its own tunnel,
 * so the probe measures the carrier. These tests pin the counter-based rule that
 * replaces it, including the case that must NOT kill a healthy idle tunnel.
 */
class WdttLivenessTest {

    private val window = 45_000L

    @Test
    fun `advancing counters keep the tunnel alive forever`() {
        val l = WdttLiveness(window)
        var now = 0L
        repeat(100) {
            assertTrue("tick $it must stay alive", l.observe(moved = true, nowMillis = now))
            now += 2_500
        }
        assertFalse(l.isSilentTooLong(now))
    }

    @Test
    fun `an idle tunnel is not condemned by the first quiet tick`() {
        val l = WdttLiveness(window)
        // 2026-10-31 field log: a healthy WDTT tunnel logged PROBE_RESULT ok=true for
        // hours because it was never judged by its own counters. A single quiet sample
        // must not start tearing it down.
        assertTrue(l.observe(moved = false, nowMillis = 1_000))
        assertFalse(l.isSilentTooLong(1_000))
    }

    @Test
    fun `silence for the whole window is a failure`() {
        val l = WdttLiveness(window)
        l.observe(moved = false, nowMillis = 0)
        assertTrue("still inside the window", l.observe(moved = false, nowMillis = window - 1))
        assertFalse("the window has elapsed", l.observe(moved = false, nowMillis = window))
        assertTrue(l.isSilentTooLong(window))
    }

    @Test
    fun `one moving sample resets the silence window`() {
        val l = WdttLiveness(window)
        l.observe(moved = false, nowMillis = 0)
        assertTrue("keepalive pushes traffic through", l.observe(moved = true, nowMillis = 20_000))
        assertFalse(l.isSilentTooLong(20_000))
        // Silence is counted from the LAST movement (20 s), not from the first frozen
        // sample ever seen (0 s), so the window closes at 65 s and not at 45 s.
        assertTrue("inside the window", l.observe(moved = false, nowMillis = 64_000))
        assertFalse("window elapsed since the last movement", l.observe(moved = false, nowMillis = 65_000))
    }

    @Test
    fun `reset clears a pending failure`() {
        val l = WdttLiveness(window)
        l.observe(moved = false, nowMillis = 0)
        l.observe(moved = false, nowMillis = window)
        assertTrue(l.isSilentTooLong(window))
        l.reset()
        assertFalse(l.isSilentTooLong(window))
        assertTrue(l.observe(moved = false, nowMillis = window))
    }
}
