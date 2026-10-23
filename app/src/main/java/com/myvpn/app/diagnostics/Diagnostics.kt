package com.myvpn.app.diagnostics

import android.util.Log

/**
 * Structured diagnostics that are safe to keep: never logs config text, keys, tokens,
 * endpoint credentials or profile payloads.
 *
 * Every event goes through [scrub] so an accidental secret in a message is redacted
 * before it can reach logcat or a bug report.
 */
object Diagnostics {

    private const val TAG = "MyVPN"

    /** Events are intentionally coarse — enough to debug, not enough to profile a user. */
    enum class Event {
        APP_START,
        PROFILE_IMPORTED,
        PROFILE_IMPORT_REJECTED,
        TUNNEL_START,
        TUNNEL_START_FAILED,
        TUNNEL_STOP,
        PROBE_RESULT,
        FAILOVER_TRIGGERED,
        FAILOVER_SUPPRESSED,
        FAILOVER_EXHAUSTED,
        WDTT_HANDOVER,
        DEEPLINK_RECEIVED,
        DEEPLINK_REJECTED,
        VPN_PERMISSION,
    }

    fun event(event: Event, vararg fields: Pair<String, Any?>) {
        val rendered = fields.joinToString(" ") { (k, v) -> "$k=${scrub(v?.toString() ?: "-")}" }
        Log.i(TAG, "${event.name} $rendered")
    }

    fun warn(event: Event, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val rendered = fields.joinToString(" ") { (k, v) -> "$k=${scrub(v?.toString() ?: "-")}" }
        // Only the exception type and message are recorded, and the message is scrubbed:
        // engine stack traces can embed config fragments.
        Log.w(TAG, "${event.name} $rendered err=${throwable?.javaClass?.simpleName}: ${scrub(throwable?.message ?: "")}")
    }

    /** Redacts anything that looks like a key, token or long opaque blob. */
    fun scrub(value: String): String {
        if (value.isEmpty()) return value
        var out = value
        // WireGuard/AmneziaWG keys: 44-char base64 ending with '='.
        out = KEY_PATTERN.replace(out, "<key>")
        // Bearer/hex tokens of 32+ chars.
        out = LONG_HEX.replace(out, "<token>")
        // myvpn://import?token=...
        out = TOKEN_PARAM.replace(out, "token=<redacted>")
        return out
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9+/]{43}=")
    private val LONG_HEX = Regex("\\b[A-Fa-f0-9]{32,}\\b")
    private val TOKEN_PARAM = Regex("([?&]token=)[^&\\s]+")
}
