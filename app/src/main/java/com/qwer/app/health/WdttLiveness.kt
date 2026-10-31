package com.qwer.app.health

/**
 * Decides whether a WDTT tunnel is still carrying traffic.
 *
 * WHY WDTT NEEDS ITS OWN VERDICT
 * ------------------------------
 * A socket probe is the right health check for WireGuard and AmneziaWG, but it is a lie
 * for WDTT. The app excludes its own package from the tunnel (that exclusion is what
 * stops the native client's TURN traffic from looping into itself), so any socket opened
 * by this process leaves through the DIRECT path. The probe therefore measures the
 * carrier, not the tunnel: it keeps reporting "ok" while the TURN relay is dead, and it
 * reports failure when the carrier hiccups even though the tunnel is healthy.
 *
 * The honest signal is the tunnel's own byte counters. Keepalive is injected at 25 s, so
 * a live relay always moves a counter well inside a 45 s window; a fully frozen counter
 * for longer than that means the relay stopped carrying traffic.
 *
 * The first quiet tick must not condemn the tunnel: a genuinely idle session looks
 * exactly the same for a few seconds. Hence a window, not a single sample — and the
 * window is measured from the LAST time the counters moved, so a tunnel that moved 44 s
 * ago and is quiet now is closer to failing than one that just went quiet.
 *
 * Extracted as a pure class because this rule decides whether a user's tunnel is torn
 * down, and it must be testable without Android, sockets or sleeps.
 */
class WdttLiveness(
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
) {

    /**
     * When traffic was last seen, or when observation started if it never moved.
     *
     * Counting from the last movement (not from the first frozen sample) matters: a
     * tunnel that stopped at t=20 s must fail at t=65 s, not be granted a fresh 45 s
     * window that only starts when someone first notices the silence.
     */
    private var lastMovedAt: Long? = null

    /** Clears the window, e.g. when a reconnect starts a new session. */
    fun reset() {
        lastMovedAt = null
    }

    /**
     * @param moved true when either counter advanced since the previous sample
     * @return true while the tunnel may still be considered alive
     */
    fun observe(moved: Boolean, nowMillis: Long): Boolean {
        if (moved) {
            lastMovedAt = nowMillis
            return true
        }
        val since = lastMovedAt ?: nowMillis.also { lastMovedAt = it }
        return nowMillis - since < windowMillis
    }

    /** True once the silence has outlived the window. */
    fun isSilentTooLong(nowMillis: Long): Boolean {
        val since = lastMovedAt ?: return false
        return nowMillis - since >= windowMillis
    }

    companion object {
        /**
         * Longer than the probe failure window on purpose: keepalive ticks every 25 s, so
         * a live relay always moves the counter well inside this, while an idle-but-alive
         * tunnel is not mistaken for a dead one.
         */
        const val DEFAULT_WINDOW_MS = 45_000L
    }
}
