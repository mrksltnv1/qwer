package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression tests for the failover traffic bug reported on a real device:
 * after WG/AWG -> WDTT the UI kept showing the previous protocol's counters, and
 * while WDTT was coming up it showed "нет данных от движка".
 *
 * Two distinct causes, both pinned here at the tracker level:
 *  1. the WDTT path never called startSession(), so no snapshot existed at all;
 *  2. performFailover never cleared the old session, so its numbers lingered.
 */
class TrafficFailoverTest {

    private var now = 1_000_000L
    private fun tracker() = TrafficSessionTracker { now }

    @Test
    fun `switching protocol starts a clean session`() {
        val t = tracker()
        t.startSession(Protocol.AMNEZIAWG)
        t.sample(0, 0)
        val awg = t.sample(5_000, 3_000) as TrafficSnapshot.Known
        assertEquals(Protocol.AMNEZIAWG, awg.protocol)
        assertEquals(5_000, awg.rxBytes)

        // Failover to WDTT: old session must end, new one must start empty.
        t.endSession()
        assertEquals(TrafficSnapshot.Unavailable, t.sample(5_000, 3_000))

        t.startSession(Protocol.WDTT_PLUS)
        val wdtt = t.sample(0, 0) as TrafficSnapshot.Known
        assertEquals(Protocol.WDTT_PLUS, wdtt.protocol)
        assertEquals("WDTT must not inherit AWG bytes", 0, wdtt.rxBytes)
        assertEquals(0, wdtt.txBytes)
        assertNotEquals(awg.protocol, wdtt.protocol)
    }

    @Test
    fun `no session means the UI shows unavailable, not stale numbers`() {
        val t = tracker()
        t.startSession(Protocol.AMNEZIAWG)
        t.sample(0, 0)
        t.sample(9_000, 9_000)
        t.endSession()
        // This is what the WDTT switch looked like before the fix.
        assertEquals(TrafficSnapshot.Unavailable, t.sample(9_000, 9_000))
    }

    @Test
    fun `wdtt session reports its own counters`() {
        val t = tracker()
        t.startSession(Protocol.WDTT_PLUS)
        t.sample(1_000, 1_000)          // baseline taken here
        val s = t.sample(2_500, 4_000) as TrafficSnapshot.Known
        assertEquals(1_500, s.rxBytes)
        assertEquals(3_000, s.txBytes)
        assertEquals(Protocol.WDTT_PLUS, s.protocol)
    }

    @Test
    fun `session duration restarts on failover`() {
        val t = tracker()
        now = 10_000L
        t.startSession(Protocol.AMNEZIAWG)
        val awg = t.sample(0, 0) as TrafficSnapshot.Known
        now = 20_000L
        t.endSession()
        t.startSession(Protocol.WDTT_PLUS)
        val wdtt = t.sample(0, 0) as TrafficSnapshot.Known
        assertEquals(10_000L, awg.sessionStartedAtMillis)
        assertEquals(20_000L, wdtt.sessionStartedAtMillis)
    }
}
