package com.myvpn.app.failover

import com.myvpn.app.core.Protocol

/**
 * Tunables for the failover decision. Every value is a plain number so the whole
 * decision logic stays deterministic and unit-testable with a fake clock.
 *
 * Defaults follow the MVP brief: probe every 2.5 s, require a 12 s sustained outage
 * before switching, and never switch twice in quick succession.
 */
data class FailoverConfig(
    /** How often the health check runs. */
    val probeIntervalMillis: Long = 2_500,
    /**
     * How long the link must stay bad before we act. A single failed probe is never
     * enough — transient packet loss and DNS hiccups must not trigger a switch.
     */
    val failureWindowMillis: Long = 12_000,
    /** Minimum consecutive failed probes inside the window. */
    val minConsecutiveFailures: Int = 3,
    /** After a successful switch, ignore new failures for this long. */
    val cooldownMillis: Long = 60_000,
    /** Maximum automatic switches per session before we stop and ask the user. */
    val maxAutoSwitches: Int = 3,
    /** A tunnel that has been up this long resets the switch budget. */
    val stabilityResetMillis: Long = 10 * 60_000,
    /**
     * RX/TX stagnation is only a *hint*, never proof. Counters legitimately freeze when
     * the user is idle, so this stays far longer than the failure window.
     */
    val trafficStagnationMillis: Long = 90_000,
) {
    init {
        require(probeIntervalMillis > 0) { "probeIntervalMillis must be positive" }
        require(failureWindowMillis >= probeIntervalMillis) { "failure window must span at least one probe" }
        require(minConsecutiveFailures >= 1)
        require(maxAutoSwitches >= 0)
    }
}

/** Immutable view of what the monitor observes on each tick. */
data class HealthSample(
    val nowMillis: Long,
    /** Result of the connectivity probe through the tunnel. */
    val probeOk: Boolean,
    /** True when a physical network (Wi-Fi/cellular) exists at all. */
    val physicalNetworkAvailable: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
)

/** What the controller wants the caller to do next. */
sealed interface FailoverDecision {
    data object None : FailoverDecision
    /** Sustained failure confirmed; switch the active tunnel to the failover profile. */
    data class SwitchTo(val target: Protocol, val reason: String) : FailoverDecision
    /** Automatic switching is exhausted; surface this to the user. */
    data class GiveUp(val reason: String) : FailoverDecision
}

/**
 * Deterministic failover state machine.
 *
 * Rules encoded here (each one is covered by a unit test):
 *  1. A healthy probe resets any accumulating failure — no switch.
 *  2. A switch requires BOTH a full failure window AND the minimum failure count.
 *  3. No physical network means "the internet is gone", not "the VPN broke" —
 *     we never blame the tunnel for a dead uplink.
 *  4. Frozen byte counters alone never trigger a switch.
 *  5. A cooldown suppresses switches right after one, preventing flapping.
 *  6. The switch budget caps WG -> WDTT -> WG -> WDTT loops; a long stable period
 *     refills it.
 */
class FailoverController(
    private val config: FailoverConfig = FailoverConfig(),
) {

    private var failureStartedAt: Long? = null
    private var consecutiveFailures: Int = 0
    private var lastSwitchAt: Long = Long.MIN_VALUE
    private var autoSwitches: Int = 0
    private var connectedSince: Long? = null
    private var lastTrafficChangeAt: Long = Long.MIN_VALUE
    private var lastRx: Long = 0
    private var lastTx: Long = 0

    /** Call when a tunnel (re)connects, so the budget/stability clock is accurate. */
    fun onConnected(nowMillis: Long) {
        connectedSince = nowMillis
        failureStartedAt = null
        consecutiveFailures = 0
        lastTrafficChangeAt = nowMillis
        lastRx = 0
        lastTx = 0
    }

    fun onDisconnected() {
        connectedSince = null
        failureStartedAt = null
        consecutiveFailures = 0
    }

    /** Current failure window progress, for UI ("проверяем 4/12 c"). */
    fun failureWindowElapsedMillis(nowMillis: Long): Long =
        failureStartedAt?.let { nowMillis - it } ?: 0

    fun evaluate(sample: HealthSample, currentProtocol: Protocol, failoverTarget: Protocol?): FailoverDecision {
        // Rule 3: a missing physical uplink is not the tunnel's fault.
        if (!sample.physicalNetworkAvailable) {
            failureStartedAt = null
            consecutiveFailures = 0
            return FailoverDecision.None
        }

        // Track traffic movement purely as a hint signal.
        if (sample.rxBytes != lastRx || sample.txBytes != lastTx) {
            lastRx = sample.rxBytes
            lastTx = sample.txBytes
            lastTrafficChangeAt = sample.nowMillis
        }

        // Rule 1: a good probe clears everything.
        if (sample.probeOk) {
            failureStartedAt = null
            consecutiveFailures = 0
            // Rule 6: long stability refills the switch budget.
            val since = connectedSince
            if (since != null && sample.nowMillis - since >= config.stabilityResetMillis) {
                autoSwitches = 0
            }
            return FailoverDecision.None
        }

        consecutiveFailures++
        if (failureStartedAt == null) failureStartedAt = sample.nowMillis
        val windowElapsed = sample.nowMillis - (failureStartedAt ?: sample.nowMillis)

        // Rule 2: need both the window and the count.
        if (windowElapsed < config.failureWindowMillis) return FailoverDecision.None
        if (consecutiveFailures < config.minConsecutiveFailures) return FailoverDecision.None

        // Rule 5: cooldown after a switch.
        if (lastSwitchAt != Long.MIN_VALUE &&
            sample.nowMillis - lastSwitchAt < config.cooldownMillis
        ) {
            return FailoverDecision.None
        }

        // Rule 6: budget.
        if (autoSwitches >= config.maxAutoSwitches) {
            return FailoverDecision.GiveUp(
                "Автопереключение остановлено: $autoSwitches попыток подряд без стабильной связи"
            )
        }

        // No usable target configured.
        if (failoverTarget == null || failoverTarget == currentProtocol) {
            return FailoverDecision.GiveUp("Резервный профиль WDTT Plus не настроен")
        }

        val stagnation = lastTrafficChangeAt != Long.MIN_VALUE &&
            sample.nowMillis - lastTrafficChangeAt >= config.trafficStagnationMillis
        val reason = buildString {
            append("нет связи ")
            append(windowElapsed / 1000)
            append(" c, неудачных проверок: ")
            append(consecutiveFailures)
            // Rule 4: stagnation is reported, never the sole cause.
            if (stagnation) append(", трафик не менялся")
        }

        autoSwitches++
        lastSwitchAt = sample.nowMillis
        failureStartedAt = null
        consecutiveFailures = 0
        return FailoverDecision.SwitchTo(failoverTarget, reason)
    }
}
