package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour that stops the NETWORK_CHANGED storm seen in the field log:
 * 14 events in ~11 minutes, several inside the same second, each costing a probe.
 * All of them carried transport=wifi, i.e. nothing actually moved.
 */
class NetworkChangeGateTest {

    @Test
    fun `first report seeds the baseline and is accepted`() {
        val g = NetworkChangeGate()
        assertTrue(g.accept("wifi", 1_000))
        assertEquals("wifi", g.currentTransport())
        assertEquals("baseline is not a change", 0, g.acceptedChangeCount())
    }

    @Test
    fun `same transport is never accepted twice`() {
        val g = NetworkChangeGate()
        g.accept("wifi", 1_000)
        assertFalse(g.accept("wifi", 2_000))
        assertFalse(g.accept("wifi", 3_000))
        assertFalse(g.accept("wifi", 60_000))
        assertEquals(0, g.acceptedChangeCount())
    }

    @Test
    fun `a real handover right after connecting is not delayed`() {
        val g = NetworkChangeGate()
        g.accept("wifi", 1_000)
        assertTrue("wifi -> cellular must act", g.accept("cellular", 2_000))
        assertEquals("cellular", g.currentTransport())
        assertEquals(1, g.acceptedChangeCount())
    }

    @Test
    fun `flapping inside the guard window is suppressed`() {
        val g = NetworkChangeGate()
        g.accept("wifi", 1_000)                  // baseline
        assertTrue(g.accept("cellular", 2_000))  // first real change: allowed
        assertFalse("flap back inside 5 s", g.accept("wifi", 3_000))
        assertFalse("another flap inside 5 s", g.accept("other", 4_000))
        assertFalse("same as accepted transport", g.accept("cellular", 5_000))
        assertTrue("new transport after the guard", g.accept("wifi", 8_000))
        assertEquals(2, g.acceptedChangeCount())
    }

    @Test
    fun `a suppressed transport is acted on once the window expires`() {
        val g = NetworkChangeGate()
        g.accept("wifi", 0)                 // baseline
        g.accept("cellular", 1_000)         // first change
        assertFalse("suppressed by guard", g.accept("other", 2_000))
        assertEquals("baseline unchanged", "cellular", g.currentTransport())
        assertEquals("but we did see it", "other", g.seenTransport())
        assertTrue("acted on after the guard", g.accept("other", 7_000))
        assertEquals("other", g.currentTransport())
    }

    @Test
    fun `the field log sequence yields exactly one decision`() {
        val g = NetworkChangeGate()
        // Replaying the user's log: all 14 events carry transport=wifi.
        val seconds = listOf(
            0L, 1L, 3L,          // 13:53:09, 10, 12
            57L, 57L,            // 13:54:06 twice
            118L,                // 13:55:07
            159L, 159L, 168L,    // 13:55:48, 48, 57
            171L,                // 13:56:00
            518L, 527L, 538L,    // 14:04:38, 47, 58
        )
        val accepted = seconds.count { sec -> g.accept("wifi", sec * 1_000) }
        assertEquals("only the baseline should pass", 1, accepted)
        assertEquals("and it is not counted as a change", 0, g.acceptedChangeCount())
    }

    @Test
    fun `reset clears state and re-seeds`() {
        val g = NetworkChangeGate()
        g.accept("wifi", 1_000)
        g.accept("cellular", 2_000)
        g.reset()
        assertTrue("baseline re-seeded", g.accept("wifi", 3_000))
        assertEquals(0, g.acceptedChangeCount())
    }

    @Test
    fun `handovers after the guard are accepted`() {
        val g = NetworkChangeGate(minIntervalMillis = 5_000)
        g.accept("wifi", 0)
        assertTrue(g.accept("cellular", 6_000))
        assertTrue(g.accept("wifi", 12_000))
        assertEquals(2, g.acceptedChangeCount())
    }

    @Test
    fun `ethernet to wifi is treated like any other handover`() {
        val g = NetworkChangeGate()
        g.accept("ethernet", 0)
        assertTrue(g.accept("wifi", 1_000))
        assertFalse("back inside the guard", g.accept("ethernet", 2_000))
    }
}
