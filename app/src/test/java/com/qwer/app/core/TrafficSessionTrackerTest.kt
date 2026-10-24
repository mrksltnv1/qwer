package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traffic anchoring is the part that is easy to get subtly wrong: a naive
 * `raw - baseline` breaks on reconnect and on engine restart. These tests pin the
 * behaviour the UI depends on.
 */
class TrafficSessionTrackerTest {

    private var now = 1_000_000L
    private fun tracker() = TrafficSessionTracker { now }

    @Test
    fun `no session means unavailable, not zero`() {
        val t = tracker()
        assertEquals(TrafficSnapshot.Unavailable, t.sample(500, 600))
        assertFalse(t.isActive)
    }

    @Test
    fun `first sample establishes the baseline so startup traffic is not counted`() {
        val t = tracker()
        t.startSession(Protocol.WIREGUARD)
        // Engine already transferred 1000/2000 before we sampled.
        val s = t.sample(1000, 2000) as TrafficSnapshot.Known
        assertEquals(0, s.rxBytes)
        assertEquals(0, s.txBytes)
    }

    @Test
    fun `session reports only bytes transferred after connect`() {
        val t = tracker()
        t.startSession(Protocol.WIREGUARD)
        t.sample(1000, 2000) // baseline
        val s = t.sample(4000, 5500) as TrafficSnapshot.Known
        assertEquals(3000, s.rxBytes)
        assertEquals(3500, s.txBytes)
    }

    @Test
    fun `reconnect starts a fresh session instead of inheriting old totals`() {
        val t = tracker()
        t.startSession(Protocol.WIREGUARD)
        t.sample(0, 0)
        t.sample(10_000, 20_000)
        t.endSession()

        // New session, engine counters reset to zero (new tunnel instance).
        t.startSession(Protocol.WDTT_PLUS)
        t.sample(0, 0)
        val s = t.sample(500, 700) as TrafficSnapshot.Known
        assertEquals("must not carry over the previous 10 000", 500, s.rxBytes)
        assertEquals(700, s.txBytes)
        assertEquals(Protocol.WDTT_PLUS, s.protocol)
    }

    @Test
    fun `failover does not double count`() {
        val t = tracker()
        t.startSession(Protocol.WIREGUARD)
        t.sample(0, 0)
        val before = t.sample(1000, 1000) as TrafficSnapshot.Known
        assertEquals(1000, before.rxBytes)

        // Failover: old session ends, new protocol session begins.
        t.endSession()
        t.startSession(Protocol.WDTT_PLUS)
        t.sample(0, 0)
        val after = t.sample(300, 400) as TrafficSnapshot.Known
        assertEquals("new session counts only its own bytes", 300, after.rxBytes)
    }

    @Test
    fun `engine restart with lower counters rebases instead of showing negative`() {
        val t = tracker()
        t.startSession(Protocol.WIREGUARD)
        t.sample(0, 0)
        t.sample(50_000, 50_000)

        // Engine died and restarted: counters are now small again.
        val s = t.sample(100, 100) as TrafficSnapshot.Known
        assertTrue("rx must never be negative", s.rxBytes >= 0)
        assertTrue("tx must never be negative", s.txBytes >= 0)
        assertEquals(0, s.rxBytes)
    }

    @Test
    fun `session duration is measured from connect, not from app start`() {
        val t = tracker()
        now = 5_000_000L
        t.startSession(Protocol.WIREGUARD)
        val s = t.sample(0, 0) as TrafficSnapshot.Known
        now = 5_000_000L + 3_600_000L // one hour later
        assertEquals(3_600_000L, s.sessionDurationMillis(now))
    }

    @Test
    fun `duration never goes negative on a clock jump backwards`() {
        val t = tracker()
        now = 10_000_000L
        t.startSession(Protocol.WIREGUARD)
        val s = t.sample(0, 0) as TrafficSnapshot.Known
        assertEquals(0, s.sessionDurationMillis(1_000_000L))
    }

    @Test
    fun `byte formatting uses binary units consistently`() {
        val s = TrafficSnapshot.Known(0, 0, 0, 0, Protocol.WIREGUARD)
        assertEquals("512 B", s.formatBytes(512))
        assertEquals("1.0 KB", s.formatBytes(1024))
        assertEquals("1.0 MB", s.formatBytes(1024L * 1024))
        assertEquals("1.50 GB", s.formatBytes(1024L * 1024 * 1024 * 3 / 2))
    }

    @Test
    fun `duration formatting matches the documented hh mm ss shape`() {
        val s = TrafficSnapshot.Known(0, 0, 0, 0, Protocol.WIREGUARD)
        assertEquals("00:00:05", s.formatDuration(5_000))
        assertEquals("01:24:36", s.formatDuration(((1 * 3600 + 24 * 60 + 36) * 1000).toLong()))
        assertEquals("2d 03:00:00", s.formatDuration(((2L * 86400 + 3 * 3600) * 1000)))
    }
}
