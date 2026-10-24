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

    /**
     * True when the per-app routing rule must not be edited.
     *
     * WHY THE RULE IS FROZEN: per-app routing is expressed as
     * Included/ExcludedApplications inside the config handed to the engine at start, and
     * `VpnService.Builder` accepts those lists only once, while establishing the tunnel.
     * Android offers no API to add or remove an app from a live tunnel. Saving a new rule
     * while connected would therefore persist a setting that silently does nothing until
     * the next reconnect — a UI that lies about its own state.
     *
     * Editing is allowed only when there is no tunnel and no attempt in flight
     * (`Disconnected`) or when the last attempt already failed (`Failed`), because in the
     * failed case no VpnService is holding the slot.
     */
    val blocksRoutingEdit: Boolean
        get() = this !is Disconnected && this !is Failed
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
