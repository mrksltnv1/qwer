package com.qwer.app.core

/**
 * User-visible connection state. The UI is driven *only* by this type, so there is a
 * single place that decides whether the tunnel may be shown as connected.
 *
 * [Connected] is never reached from a mere service start: it requires a proven
 * end-to-end probe through the tunnel (see HealthMonitor).
 */
sealed interface ConnectionState {
    /** No tunnel, no attempt in flight. */
    data object Disconnected : ConnectionState

    /** Tunnel start requested; engine is coming up. */
    data object Connecting : ConnectionState

    /** Tunnel is up, but connectivity through it is not yet proven. */
    data object Verifying : ConnectionState

    /** Tunnel up AND a probe through the tunnel succeeded recently. */
    data class Connected(
        val protocol: Protocol,
        val connectedAtMillis: Long,
        val lastProbeMillis: Long,
    ) : ConnectionState

    /** Healthy tunnel lost its link; waiting out the failure window before acting. */
    data class Degraded(
        val protocol: Protocol,
        val sinceMillis: Long,
        val failedProbes: Int,
    ) : ConnectionState

    /** Failover in progress. */
    data class Switching(val from: Protocol, val to: Protocol) : ConnectionState

    /** Terminal failure with a user-retryable explanation. */
    data class Failed(val reason: FailureReason, val detail: String? = null) : ConnectionState

    val isBusy: Boolean
        get() = this is Connecting || this is Verifying || this is Switching
}

enum class FailureReason {
    NO_PROFILE,
    VPN_PERMISSION_DENIED,
    ENGINE_ERROR,
    NO_NETWORK,
    PROBE_FAILED,
    WDTT_UNAVAILABLE,
    WDTT_HANDOVER_REQUIRED,
    IMPORT_FAILED,
    COOLDOWN_ACTIVE,
}
