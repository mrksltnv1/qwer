package com.qwer.app.core

/**
 * Decides whether a connectivity callback is worth acting on.
 *
 * FIELD EVIDENCE (user log from a real Android 16 device):
 * 14 NETWORK_CHANGED events in ~11 minutes, every one carrying transport=wifi:
 *   13:53:09, 13:53:10, 13:53:12   — three within 3 s
 *   13:54:06, 13:54:06             — two within the same second
 *   14:04:38, 14:04:47, 14:04:58   — three within 20 s
 * Each one started a recovery probe. The tunnel was healthy; these were radio noise
 * (signal strength, bandwidth estimate, re-association), not path changes.
 *
 * POLICY
 * ------
 *  1. Same transport as the accepted one → never interesting. This alone removes every
 *     event in the field log above.
 *  2. A different transport (real Wi-Fi ↔ cellular handover) is interesting and is
 *     never delayed. The baseline seed does not count against it.
 *  3. Only *flapping* is rate-limited: after the first real change, another
 *     different-transport change inside [minIntervalMillis] is suppressed, because no
 *     genuine handover happens twice within 5 s.
 *  4. A suppressed transition is not recorded as accepted, so if the radio keeps
 *     reporting the new transport it is acted on once the window expires.
 *
 * Extracted as a pure class so the decision is unit-testable without Android.
 */
class NetworkChangeGate(private val minIntervalMillis: Long = DEFAULT_INTERVAL_MS) {

    /** Transport we last acted on. New reports are compared against this. */
    private var acceptedTransport: String? = null

    /** Transport from the most recent callback, accepted or not (diagnostics only). */
    private var seenTransportValue: String? = null

    private var lastAcceptedAt: Long = Long.MIN_VALUE

    /**
     * Number of accepted changes *excluding* the initial baseline seed.
     * The seed is not a change: it only records where we started.
     */
    private var realChanges: Int = 0

    /**
     * @param transport current transport label ("wifi", "cellular", "ethernet", "other")
     * @param nowMillis monotonically increasing clock
     * @return true when the caller should treat this as a real connection change
     */
    fun accept(transport: String, nowMillis: Long): Boolean {
        seenTransportValue = transport

        val previous = acceptedTransport

        // Rule 1: nothing moved. Capability churn only.
        if (previous == transport) return false

        // Rule 3: flapping guard. Only applies once at least one real change has been
        // accepted; the baseline seed and the first real handover are never delayed.
        if (previous != null && realChanges > 0 &&
            nowMillis - lastAcceptedAt < minIntervalMillis
        ) {
            return false
        }

        acceptedTransport = transport
        lastAcceptedAt = nowMillis
        // The very first acceptance is the baseline, not a change.
        if (previous != null) realChanges++
        return true
    }

    /** Transport last acted on, for diagnostics. */
    fun currentTransport(): String? = acceptedTransport

    /** Transport last observed, even if suppressed. */
    fun seenTransport(): String? = seenTransportValue

    /** How many genuine changes have been acted on (excludes the baseline). */
    fun acceptedChangeCount(): Int = realChanges

    fun reset() {
        acceptedTransport = null
        seenTransportValue = null
        lastAcceptedAt = Long.MIN_VALUE
        realChanges = 0
    }

    companion object {
        /**
         * Android can report wifi → wifi(no-internet) → wifi while the radio
         * re-associates. 5 s collapses that into one decision without hiding a real
         * Wi-Fi ↔ cellular handover, which never happens twice inside 5 s.
         */
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
