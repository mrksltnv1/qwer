package com.qwer.app.engine

import android.content.Context
import android.os.Build
import com.qwer.app.config.ConfigNormalizer
import com.qwer.app.core.ConnectionState
import com.qwer.app.core.Protocol
import com.qwer.app.diagnostics.Diagnostics
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * Wraps the AmneziaWG userspace backend.
 *
 * One engine instance drives BOTH WireGuard and AmneziaWG profiles: the AmneziaWG config
 * parser accepts a plain WireGuard config and leaves the obfuscation fields empty
 * (verified against the built AAR: plain WG parses with jc/s1/h1 = Optional.empty,
 * while an AWG config yields jc=4/h4=4). This is what lets a single APK ship one
 * native engine instead of two colliding sets of libwg-go.so.
 */
class TunnelEngine(private val context: Context) : AutoCloseable {

    private val backend: Backend = GoBackend(context)

    /** Tunnel identity handed to the backend; only the name is meaningful here. */
    private class EngineTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    private var current: EngineTunnel? = null

    fun isUp(): Boolean = current?.let { backend.getState(it) == Tunnel.State.UP } == true

    /** Directives dropped from the last started config, for user-facing diagnostics. */
    var lastRemovedKeys: List<String> = emptyList()
        private set

    /**
     * Brings a tunnel up from raw config text.
     *
     * The config is sanitised first: the engine rejects the ENTIRE config when it meets
     * a key outside its allow-list, and server-side exports routinely carry wg-quick
     * directives (PostUp/PostDown/Table/SaveConfig/FwMark) that are meaningless inside a
     * VpnService tunnel. Without this step a valid tunnel simply refuses to start.
     *
     * @throws IllegalStateException when the engine refuses the config or the user has not
     *         granted the VPN permission (the caller must run VpnService.prepare first).
     */
    fun up(rawConfig: String, tunnelName: String = DEFAULT_NAME) {
        val normalized = ConfigNormalizer.sanitize(rawConfig)
        lastRemovedKeys = normalized.removedKeys
        if (normalized.hadChanges) {
            Diagnostics.event(
                Diagnostics.Event.TUNNEL_START,
                "dropped_wgquick_keys" to normalized.removedKeys.joinToString(","),
            )
        }
        val parsed: Config = Config.parse(BufferedReader(StringReader(normalized.config)))
        val tunnel = EngineTunnel(tunnelName)
        backend.setState(tunnel, Tunnel.State.UP, parsed)
        current = tunnel
    }

    fun down() {
        val tunnel = current ?: return
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        current = null
    }

    /** Byte counters for the active tunnel; both zero when nothing is running. */
    data class Counters(val rxBytes: Long, val txBytes: Long)

    fun counters(): Counters = runCatching {
        val tunnel = current ?: return Counters(0, 0)
        val stats = backend.getStatistics(tunnel)
        Counters(stats.totalRx(), stats.totalTx())
    }.getOrElse { Counters(0, 0) }

    /** Epoch millis of the last successful handshake, or null when unknown. */
    fun lastHandshakeMillis(): Long? = runCatching {
        val tunnel = current ?: return null
        val secs = backend.getLastHandshake(tunnel)
        if (secs > 0) secs * 1000 else null
    }.getOrNull()

    override fun close() = down()

    private companion object {
        const val DEFAULT_NAME = "qwer0"
    }
}
