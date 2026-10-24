package com.qwer.app.core

import java.util.UUID

/**
 * A stored VPN profile.
 *
 * The raw config (which contains a private key) is deliberately NOT part of this class:
 * it lives in [com.qwer.app.secure.SecureStorage] and is fetched only when a tunnel is
 * started. Keeping it out of the model means it cannot leak into UI state, logs,
 * saved instance state or crash reports.
 */
data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    /** Last handshake/tunnel endpoint, non-secret, for display only. */
    val endpointSummary: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** True if this profile is the designated failover target. */
    val isFailoverTarget: Boolean = false,
    /**
     * Per-app routing. Defaults to [AppRouting.All], so every profile created before this
     * field existed keeps behaving exactly as before — no migration needed.
     */
    val appRouting: AppRouting = AppRouting.All,
) {
    /** Non-secret description safe for logs and diagnostics. */
    fun safeLabel(): String = "$name (${protocol.displayName})"
}
