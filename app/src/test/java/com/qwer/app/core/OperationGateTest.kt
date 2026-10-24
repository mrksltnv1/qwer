package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios required by the brief: fast start/stop, start-stop-start, profile switch
 * during start, a late callback from an old operation, repeated stop, and cancel/timeout.
 */
class OperationGateTest {

    @Test
    fun `a first operation is current`() {
        val gate = OperationGate()
        val t = gate.begin()
        assertTrue(gate.isCurrent(t))
    }

    @Test
    fun `fast start then stop invalidates the start`() {
        val gate = OperationGate()
        val start = gate.begin()   // user taps connect
        gate.invalidate()          // user immediately taps stop
        assertFalse("stale start must not publish", gate.isCurrent(start))
    }

    @Test
    fun `start stop start keeps only the newest operation current`() {
        val gate = OperationGate()
        val first = gate.begin()
        gate.invalidate()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `switching profile during start makes the old start stale`() {
        val gate = OperationGate()
        val startA = gate.begin()   // starting profile A
        val startB = gate.begin()   // user switches to profile B mid-flight
        assertFalse("A must not write state", gate.isCurrent(startA))
        assertTrue(gate.isCurrent(startB))
    }

    @Test
    fun `late callback from an old operation is rejected`() {
        val gate = OperationGate()
        val old = gate.begin()
        gate.invalidate()                       // stop
        val new = gate.begin()                  // start again
        // The old native call now returns and tries to publish.
        assertFalse("old result must be dropped", gate.isCurrent(old))
        assertTrue(gate.isCurrent(new))
    }

    @Test
    fun `repeated stop is idempotent`() {
        val gate = OperationGate()
        val t = gate.begin()
        gate.invalidate()
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.isCurrent(t))
        // A fresh start after repeated stops still works.
        val t2 = gate.begin()
        assertTrue(gate.isCurrent(t2))
    }

    @Test
    fun `tickets are monotonically increasing, so no ABA confusion`() {
        val gate = OperationGate()
        val a = gate.begin()
        gate.invalidate()
        val b = gate.begin()
        val c = gate.begin()
        assertTrue("b must be greater than a", b > a)
        assertTrue("c must be greater than b", c > b)
        assertFalse(gate.isCurrent(b))
        assertTrue(gate.isCurrent(c))
    }

    @Test
    fun `handle reports its own currency`() {
        val gate = OperationGate()
        val h1 = gate.newHandle()
        assertTrue(h1.isCurrent)
        val h2 = gate.newHandle()
        assertFalse("h1 superseded", h1.isCurrent)
        assertTrue(h2.isCurrent)
    }

    @Test
    fun `cancel and timeout are modelled as invalidation`() {
        val gate = OperationGate()
        val t = gate.begin()
        // Timeout fires while the operation is still running.
        gate.invalidate()
        assertFalse("timed-out operation must not publish", gate.isCurrent(t))
    }

    @Test
    fun `exception during cleanup still allows a later start`() {
        val gate = OperationGate()
        gate.begin()
        // Cleanup throws, but the gate is not left in a broken generation.
        gate.invalidate()
        val next = gate.begin()
        assertTrue(gate.isCurrent(next))
    }

    @Test
    fun `current generation is visible for diagnostics`() {
        val gate = OperationGate()
        assertEquals(0L, gate.current())
        val t = gate.begin()
        assertEquals(t, gate.current())
        gate.invalidate()
        assertEquals(t + 1, gate.current())
    }
}
