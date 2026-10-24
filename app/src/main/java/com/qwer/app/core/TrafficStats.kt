package com.qwer.app.core

/**
 * Traffic and session statistics for the active tunnel.
 *
 * SOURCE OF TRUTH
 * ---------------
 * Numbers come from the WireGuard/AmneziaWG engine's own per-peer counters
 * (`GoBackend.getStatistics` -> `Statistics.totalRx/totalTx`). Those are cumulative
 * for the *current* tunnel instance, so:
 *
 *  - They reset when the engine restarts. That is why [sessionRxBytes] is anchored:
 *    each session keeps a baseline taken at connect time, and we always report
 *    `raw - baseline`. Without the baseline a reconnect would show a jump back to zero.
 *  - No double counting on failover: when the protocol changes the previous session is
 *    closed and a new baseline is taken, so the new session starts from its own zero
 *    instead of inheriting the old totals. The old session's numbers are kept in
 *    [previousSession] only for display history, never added to the current one.
 *
 * [Unknown] is a first-class state on purpose. Showing `0 B` when the engine cannot be
 * queried would be a lie: the user would conclude the tunnel is idle while it may be
 * dead. The UI must render "нет данных" instead.
 */
sealed interface TrafficSnapshot {
    /** Engine not running: no statistics exist. */
    data object Unavailable : TrafficSnapshot

    data class Known(
        val rxBytes: Long,
        val txBytes: Long,
        /** Epoch millis when this session's tunnel became Connected. */
        val sessionStartedAtMillis: Long,
        /** Epoch millis of the sample, for the UI to detect staleness. */
        val sampledAtMillis: Long,
        val protocol: Protocol,
    ) : TrafficSnapshot {

        /** Elapsed session time, clamped at zero so a clock change cannot show negatives. */
        fun sessionDurationMillis(nowMillis: Long): Long =
            (nowMillis - sessionStartedAtMillis).coerceAtLeast(0)

        /**
         * Human units, binary (1 KB = 1024 B) and consistent everywhere:
         * B, KB, MB, GB, TB with one decimal from MB up.
         */
        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0)
            return when {
                b < 1024 -> "$b B"
                b < 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
                b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024))
                b < 1024L * 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
                else -> String.format(java.util.Locale.US, "%.2f TB", b / (1024.0 * 1024 * 1024 * 1024))
            }
        }

        /** HH:MM:SS, or Dd HH:MM:SS past a day. */
        fun formatDuration(millis: Long): String {
            val total = millis.coerceAtLeast(0) / 1000
            val d = total / 86_400
            val h = (total % 86_400) / 3_600
            val m = (total % 3_600) / 60
            val s = total % 60
            return if (d > 0) String.format(java.util.Locale.US, "%dd %02d:%02d:%02d", d, h, m, s)
            else String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
        }
    }
}

/**
 * Owns the counter baseline for the current session.
 *
 * Kept separate from ConnectionManager so the anchoring logic is unit-testable without
 * Android: the manager feeds it raw engine counters and it returns session-relative
 * values.
 */
class TrafficSessionTracker(private val clock: () -> Long = System::currentTimeMillis) {

    private var baselineRx: Long? = null
    private var baselineTx: Long? = null
    private var lastRawRx: Long = -1
    private var lastRawTx: Long = -1
    private var startedAt: Long? = null
    private var protocol: Protocol? = null

    /** Begins a new session. Must be called exactly when a tunnel becomes Connected. */
    fun startSession(protocol: Protocol) {
        this.protocol = protocol
        startedAt = clock()
        baselineRx = null // taken on the first sample, when the engine is readable
        baselineTx = null
        lastRawRx = -1
        lastRawTx = -1
    }

    fun endSession() {
        startedAt = null
        baselineRx = null
        baselineTx = null
        lastRawRx = -1
        lastRawTx = -1
        protocol = null
    }

    val isActive: Boolean get() = startedAt != null

    /**
     * @param rawRx cumulative engine bytes since the tunnel instance started
     * @return a session-relative snapshot, or [TrafficSnapshot.Unavailable] when no
     *         session is active.
     */
    fun sample(rawRx: Long, rawTx: Long): TrafficSnapshot {
        val start = startedAt ?: return TrafficSnapshot.Unavailable
        val proto = protocol ?: return TrafficSnapshot.Unavailable

        // First sample after start defines the baseline: the engine may already report
        // non-zero bytes from its own startup traffic, which the user never asked for.
        if (baselineRx == null || baselineTx == null) {
            baselineRx = rawRx
            baselineTx = rawTx
        }

        // Detect an engine restart by comparing with the PREVIOUS raw reading, not with
        // the baseline. If the engine restarted, its counters jump back near zero while
        // our baseline may already be 0 (session just started) — comparing to the
        // baseline would miss it and report the new small value as growth.
        if (lastRawRx >= 0 && rawRx < lastRawRx) {
            baselineRx = rawRx
        }
        if (lastRawTx >= 0 && rawTx < lastRawTx) {
            baselineTx = rawTx
        }
        lastRawRx = rawRx
        lastRawTx = rawTx

        val effectiveRx = baselineRx ?: rawRx
        val effectiveTx = baselineTx ?: rawTx

        return TrafficSnapshot.Known(
            rxBytes = (rawRx - effectiveRx).coerceAtLeast(0),
            txBytes = (rawTx - effectiveTx).coerceAtLeast(0),
            sessionStartedAtMillis = start,
            sampledAtMillis = clock(),
            protocol = proto,
        )
    }
}
