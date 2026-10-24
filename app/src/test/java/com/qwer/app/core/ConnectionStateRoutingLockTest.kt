package com.qwer.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-app routing must not be editable while a tunnel is up.
 *
 * WHY THIS RULE EXISTS: the rule becomes Included/ExcludedApplications inside the config
 * handed to the engine at start, and `VpnService.Builder` takes those lists only during
 * establish(). Android has no API to add or remove an app from a live tunnel, so saving
 * a new rule while connected would persist a setting that silently does nothing until
 * the next reconnect.
 *
 * These tests pin the exact boundary, because "locked" that is off by one state is worse
 * than no lock at all: it either blocks a legitimate edit or allows a no-op one.
 */
class ConnectionStateRoutingLockTest {

    private val protocol = Protocol.WIREGUARD

    @Test
    fun `disconnected allows editing`() {
        assertFalse(ConnectionState.Disconnected.blocksRoutingEdit)
    }

    @Test
    fun `failed allows editing because no tunnel holds the slot`() {
        assertFalse(
            ConnectionState.Failed(FailureReason.ENGINE_ERROR, "boom").blocksRoutingEdit,
        )
    }

    @Test
    fun `connecting locks editing`() {
        assertTrue(ConnectionState.Connecting.blocksRoutingEdit)
    }

    @Test
    fun `verifying locks editing`() {
        assertTrue(ConnectionState.Verifying.blocksRoutingEdit)
    }

    @Test
    fun `connected locks editing`() {
        assertTrue(
            ConnectionState.Connected(protocol, 1_000L, 2_000L).blocksRoutingEdit,
        )
    }

    @Test
    fun `degraded locks editing`() {
        assertTrue(
            ConnectionState.Degraded(protocol, 1_000L, 3).blocksRoutingEdit,
        )
    }

    @Test
    fun `switching locks editing`() {
        assertTrue(
            ConnectionState.Switching(Protocol.WIREGUARD, Protocol.WDTT_PLUS).blocksRoutingEdit,
        )
    }

    @Test
    fun `only disconnected and failed unlock`() {
        val all: List<ConnectionState> = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Verifying,
            ConnectionState.Connected(protocol, 0L, 0L),
            ConnectionState.Degraded(protocol, 0L, 1),
            ConnectionState.Switching(protocol, Protocol.WDTT_PLUS),
            ConnectionState.Failed(FailureReason.NO_PROFILE),
        )
        val unlocked = all.filter { !it.blocksRoutingEdit }
        assertTrue(
            "only Disconnected and Failed may unlock, got $unlocked",
            unlocked.size == 2 &&
                unlocked.any { it is ConnectionState.Disconnected } &&
                unlocked.any { it is ConnectionState.Failed },
        )
    }
}
