package com.qwer.app.failover

import com.qwer.app.core.Protocol
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
        assertTrue(
            "expected Retry (not a dead end) after budget exhausted",
            results.last() is FailoverDecision.Retry,
        )
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

    /**
     * FIELD REGRESSION, 2026-10-31 22:45:04.
     *
     * The active profile was the only profile, so there was no fallback to switch to.
     * The controller answered "Резервный профиль WDTT Plus не настроен", the monitor
     * treated that as terminal, the foreground service went down and the tunnel stayed
     * dead until the user noticed. The tunnel must be rebuilt instead.
     */
    @Test
    fun `missing failover target retries the current profile instead of giving up`() {
        val c = FailoverController(config)
        c.onConnected(0)
        val decisions = sustainedFailure(c, start = 2_500, count = 6, to = null)
        val last = decisions.last().second
        assertTrue("expected Retry, got $last", last is FailoverDecision.Retry)
        assertTrue(
            "reason must explain that the same profile is being rebuilt",
            (last as FailoverDecision.Retry).reason.contains("текущий"),
        )
        // Exactly one decision: the window is reset so the caller is not spammed on
        // every subsequent probe while the same outage continues.
        assertEquals(1, decisions.count { it.second is FailoverDecision.Retry })
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
        assertTrue(
            "same protocol is not a switch target, but must not be a dead end either",
            last is FailoverDecision.Retry,
        )
    }

    /**
     * Replays the exact field sequence of 2026-10-31 22:44:51-22:45:04: four failed
     * probes on the WDTT profile with two profiles imported but none flagged as a
     * fallback. Before the fix this produced a terminal FAILOVER_EXHAUSTED.
     */
    @Test
    fun `field log replay - wdtt only profile never ends in a dead end`() {
        val c = FailoverController(config)
        val connectedAt = 21_000_000L
        c.onConnected(connectedAt)
        // Probes run every 2.5 s; the log's one-line-per-minute heartbeat hides that.
        val failedAts = (0 until 6).map { 22_445_100L + it * 2_500L }
        val decisions = failedAts.map { t ->
            c.evaluate(
                HealthSample(t, probeOk = false, physicalNetworkAvailable = true, rxBytes = 0, txBytes = 0),
                currentProtocol = Protocol.WDTT_PLUS,
                failoverTarget = null,
            )
        }
        // The window is 12 s, so only the last sample is late enough to act — and the
        // action is a rebuild, never "give up".
        assertTrue(
            "no decision may be terminal: $decisions",
            decisions.none { it is FailoverDecision.SwitchTo },
        )
        assertTrue(
            "the tunnel must be rebuilt on its own: $decisions",
            decisions.last() is FailoverDecision.Retry,
        )
    }

    /**
     * The budget must still cap WG -> WDTT oscillation, but spending it is not a reason
     * to leave a live user with a dead tunnel.
     */
    @Test
    fun `exhausted budget retries the current profile instead of dying`() {
        val c = FailoverController(config)
        var roundStart = 0L
        repeat(config.maxAutoSwitches) {
            c.onConnected(roundStart)
            sustainedFailure(c, start = roundStart + 2_500, count = 6)
            roundStart += 90_000
        }
        c.onConnected(roundStart)
        val last = sustainedFailure(c, start = roundStart + 2_500, count = 6).last().second
        assertTrue("expected Retry after the budget, got $last", last is FailoverDecision.Retry)
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
