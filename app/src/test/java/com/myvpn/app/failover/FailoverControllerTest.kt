package com.myvpn.app.failover

import com.myvpn.app.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests: no real clocks, no real sockets, no sleeps.
 * Every rule stated in [FailoverController] has a test here.
 *
 * TIMING MODEL (important, keeps the tests honest):
 * the controller starts the failure window at the moment it *first observes* a failed
 * probe, not at the moment the link actually died. With a 2.5 s probe interval and a
 * 12 s window, the earliest possible switch is at t = 15 s (sixth consecutive failure),
 * not at 12.5 s. Erring later is the safe direction: we would rather switch late than
 * flap on a transient hiccup.
 */
class FailoverControllerTest {

    private val config = FailoverConfig(
        probeIntervalMillis = 2_500,
        failureWindowMillis = 12_000,
        minConsecutiveFailures = 3,
        cooldownMillis = 60_000,
        maxAutoSwitches = 3,
        stabilityResetMillis = 600_000,
    )

    private fun sample(
        now: Long,
        probeOk: Boolean,
        physical: Boolean = true,
        rx: Long = 0,
        tx: Long = 0,
    ) = HealthSample(now, probeOk, physical, rx, tx)

    /**
     * Feeds [count] consecutive failures starting at [start] spaced by the probe
     * interval, returning every decision so a test can assert on the exact transition.
     */
    private fun sustainedFailure(
        c: FailoverController,
        start: Long,
        count: Int,
        from: Protocol = Protocol.WIREGUARD,
        to: Protocol? = Protocol.WDTT_PLUS,
    ): List<Pair<Long, FailoverDecision>> = (0 until count).map { i ->
        val now = start + i * config.probeIntervalMillis
        now to c.evaluate(sample(now, probeOk = false), from, to)
    }

    @Test
    fun `single failed probe does not switch`() {
        val c = FailoverController(config)
        c.onConnected(0)
        val d = c.evaluate(sample(2_500, probeOk = false), Protocol.WIREGUARD, Protocol.WDTT_PLUS)
        assertEquals(FailoverDecision.None, d)
    }

    @Test
    fun `switch happens only after the full failure window is observed`() {
        val c = FailoverController(config)
        c.onConnected(0)
        // Failures at 2.5s .. 15s. Window measured from the first observed failure (2.5s),
        // so 12 s elapses at 14.5s -> the first possible switch is the 15 s tick.
        val decisions = sustainedFailure(c, start = 2_500, count = 6)

        decisions.dropLast(1).forEach { (now, d) ->
            assertEquals("unexpected switch at ${now}ms", FailoverDecision.None, d)
        }
        val (switchAt, last) = decisions.last()
        assertEquals(15_000L, switchAt)
        assertTrue("expected SwitchTo, got $last", last is FailoverDecision.SwitchTo)
        assertEquals(Protocol.WDTT_PLUS, (last as FailoverDecision.SwitchTo).target)
    }

    @Test
    fun `window alone is not enough without the minimum failure count`() {
        val strict = config.copy(minConsecutiveFailures = 10)
        val c = FailoverController(strict)
        c.onConnected(0)
        // Six failures span 12.5 s -> window satisfied, but only 6 < 10 failures.
        sustainedFailure(c, start = 2_500, count = 6).forEach { (now, d) ->
            assertEquals("switched too early at ${now}ms", FailoverDecision.None, d)
        }
    }

    @Test
    fun `recovery inside the window cancels the switch`() {
        val c = FailoverController(config)
        c.onConnected(0)
        c.evaluate(sample(2_500, false), Protocol.WIREGUARD, Protocol.WDTT_PLUS)
        c.evaluate(sample(5_000, false), Protocol.WIREGUARD, Protocol.WDTT_PLUS)
        // Link comes back: the accumulated failure must be discarded.
        assertEquals(
            FailoverDecision.None,
            c.evaluate(sample(7_500, true), Protocol.WIREGUARD, Protocol.WDTT_PLUS),
        )
        assertEquals(0L, c.failureWindowElapsedMillis(7_500))
        // The window restarts, so shortly after recovery nothing happens yet.
        assertEquals(
            FailoverDecision.None,
            c.evaluate(sample(12_500, false), Protocol.WIREGUARD, Protocol.WDTT_PLUS),
        )
    }

    @Test
    fun `no physical network never blames the tunnel`() {
        val c = FailoverController(config)
        c.onConnected(0)
        repeat(20) { i ->
            val d = c.evaluate(
                sample(i * 2_500L, probeOk = false, physical = false),
                Protocol.WIREGUARD,
                Protocol.WDTT_PLUS,
            )
            assertEquals("switched with no physical uplink at tick $i", FailoverDecision.None, d)
        }
    }

    @Test
    fun `frozen counters alone never trigger a switch`() {
        val c = FailoverController(config)
        c.onConnected(0)
        // Byte counters never move (idle user) but every probe succeeds.
        repeat(40) { i ->
            val d = c.evaluate(
                sample(i * 2_500L, probeOk = true, rx = 1_000, tx = 2_000),
                Protocol.WIREGUARD,
                Protocol.WDTT_PLUS,
            )
            assertEquals("switched while probes were healthy", FailoverDecision.None, d)
        }
    }

    @Test
    fun `stagnant traffic is reported but is not the deciding factor`() {
        // Use a short stagnation threshold so the flag is reachable inside the failure
        // window; with the 90 s default it could never be observed in a 15 s outage.
        val c = FailoverController(config.copy(trafficStagnationMillis = 5_000))
        c.onConnected(0)
        // Probe fails persistently AND counters never move.
        val decisions = sustainedFailure(c, start = 2_500, count = 6)
        val last = decisions.last().second
        assertTrue(last is FailoverDecision.SwitchTo)
        assertTrue(
            "reason should note stagnant traffic: ${(last as FailoverDecision.SwitchTo).reason}",
            last.reason.contains("трафик не менялся"),
        )
    }

    @Test
    fun `cooldown suppresses a second switch right after the first`() {
        val c = FailoverController(config)
        c.onConnected(0)
        val first = sustainedFailure(c, start = 2_500, count = 6).last().second
        assertTrue(first is FailoverDecision.SwitchTo)

        // Reconnect immediately, fail again inside the 60 s cooldown.
        c.onConnected(16_000)
        sustainedFailure(c, start = 18_500, count = 6).forEach { (now, d) ->
            assertEquals("second switch inside cooldown at ${now}ms", FailoverDecision.None, d)
        }
    }

    @Test
    fun `switch budget stops endless oscillation`() {
        val c = FailoverController(config)
        val results = mutableListOf<FailoverDecision>()
        // Each round: connect, fail long enough to switch. Rounds are spaced 90 s apart so
        // the cooldown is satisfied and only the budget can stop us.
        var roundStart = 0L
        repeat(5) {
            c.onConnected(roundStart)
            results += sustainedFailure(c, start = roundStart + 2_500, count = 6).last().second
            roundStart += 90_000
        }
        val switches = results.count { it is FailoverDecision.SwitchTo }
        assertEquals("expected exactly maxAutoSwitches switches", config.maxAutoSwitches, switches)
        assertTrue("expected GiveUp after budget exhausted", results.last() is FailoverDecision.GiveUp)
    }

    @Test
    fun `long stability refills the switch budget`() {
        val c = FailoverController(config)
        // Burn the whole budget.
        var roundStart = 0L
        repeat(config.maxAutoSwitches) {
            c.onConnected(roundStart)
            sustainedFailure(c, start = roundStart + 2_500, count = 6)
            roundStart += 90_000
        }
        // Stay connected long enough for the stability reset, then fail again.
        val stableAt = roundStart
        c.onConnected(stableAt)
        assertEquals(
            FailoverDecision.None,
            c.evaluate(sample(stableAt + 2_500, true), Protocol.WIREGUARD, Protocol.WDTT_PLUS),
        )
        val afterStability = stableAt + config.stabilityResetMillis + 2_500
        assertEquals(
            FailoverDecision.None,
            c.evaluate(sample(afterStability, true), Protocol.WIREGUARD, Protocol.WDTT_PLUS),
        )
        // Budget was refilled, so a new sustained outage switches again instead of giving up.
        val d = sustainedFailure(c, start = afterStability + 2_500, count = 6).last().second
        assertTrue("expected SwitchTo after budget refill, got $d", d is FailoverDecision.SwitchTo)
    }

    @Test
    fun `missing failover target gives up instead of switching`() {
        val c = FailoverController(config)
        c.onConnected(0)
        val last = sustainedFailure(c, start = 2_500, count = 6, to = null).last().second
        assertTrue("expected GiveUp, got $last", last is FailoverDecision.GiveUp)
    }

    @Test
    fun `never switches to the protocol already active`() {
        val c = FailoverController(config)
        c.onConnected(0)
        val last = sustainedFailure(
            c,
            start = 2_500,
            count = 6,
            from = Protocol.WDTT_PLUS,
            to = Protocol.WDTT_PLUS,
        ).last().second
        assertTrue("expected GiveUp, got $last", last is FailoverDecision.GiveUp)
    }

    @Test
    fun `disconnect clears accumulating failure state`() {
        val c = FailoverController(config)
        c.onConnected(0)
        c.evaluate(sample(2_500, false), Protocol.WIREGUARD, Protocol.WDTT_PLUS)
        c.evaluate(sample(5_000, false), Protocol.WIREGUARD, Protocol.WDTT_PLUS)
        c.onDisconnected()
        assertEquals(0L, c.failureWindowElapsedMillis(10_000))
    }

    @Test
    fun `default config matches the documented MVP thresholds`() {
        val d = FailoverConfig()
        assertEquals(2_500L, d.probeIntervalMillis)
        assertEquals(12_000L, d.failureWindowMillis)
        assertTrue("window must stay inside the 10-15 s brief", d.failureWindowMillis in 10_000..15_000)
        assertTrue(d.minConsecutiveFailures >= 3)
        assertTrue(d.trafficStagnationMillis > d.failureWindowMillis)
    }
}
