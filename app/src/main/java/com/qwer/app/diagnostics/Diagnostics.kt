package com.qwer.app.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Structured diagnostics that are safe to keep: never logs config text, keys, tokens,
 * endpoint credentials or profile payloads.
 *
 * Every event goes through [scrub] so an accidental secret in a message is redacted
 * before it can reach logcat, the in-app log view, or a copied bug report.
 *
 * The ring buffer exists so the user can open the Logs screen and copy what happened
 * without adb and without a PC. It is bounded (see [MAX_LINES]) so a long-running
 * session cannot grow memory without limit, and it lives only in memory — nothing is
 * written to disk.
 */
object Diagnostics {

    private const val TAG = "qwer"

    /** Enough history to cover a connect attempt and the failover that followed. */
    private const val MAX_LINES = 500

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Events are intentionally coarse — enough to debug, not enough to profile a user. */
    enum class Event {
        APP_START,
        APP_UPDATE,
        PROFILE_IMPORTED,
        PROFILE_IMPORT_REJECTED,
        TUNNEL_START,
        TUNNEL_START_FAILED,
        TUNNEL_STOP,
        PROBE_RESULT,
        FAILOVER_TRIGGERED,
        FAILOVER_SUPPRESSED,
        FAILOVER_EXHAUSTED,
        FAILOVER_TARGET,
        WDTT_HANDOVER,
        WDTT_ENGINE,
        DEEPLINK_RECEIVED,
        DEEPLINK_REJECTED,
        VPN_PERMISSION,
        TILE_ACTION,
        DEVICE_DIAGNOSTICS,
        BACKUP,
        FOREGROUND_SERVICE,
        NETWORK_EVENT,
        SLEEP_RECOVERY,
        TRAFFIC_SAMPLE,
    }

    fun event(event: Event, vararg fields: Pair<String, Any?>) {
        val rendered = fields.joinToString(" ") { (k, v) -> "$k=${scrub(v?.toString() ?: "-")}" }
        val line = "${event.name} $rendered"
        Log.i(TAG, line)
        record(line)
    }

    fun warn(event: Event, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val rendered = fields.joinToString(" ") { (k, v) -> "$k=${scrub(v?.toString() ?: "-")}" }
        // Only the exception type and message are recorded, and the message is scrubbed:
        // engine stack traces can embed config fragments.
        //
        // The exception part is OMITTED when there is no exception. It used to be
        // rendered as "err=null: " on every non-error warning, which read like a
        // crash with an empty message and made real errors harder to spot.
        val err = throwable?.let {
            " err=${it.javaClass.simpleName}: ${scrub(it.message ?: "")}"
        } ?: ""
        val line = "${event.name}$err $rendered".replace(Regex(" +"), " ")
        Log.w(TAG, line)
        record("! $line")
    }

    private fun record(line: String) {
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast("${timeFormat.format(Date())}  $line")
        }
    }

    /** Oldest-first snapshot for display and copying. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** Full text for "copy to clipboard", newest last. */
    fun exportText(header: String): String {
        val lines = snapshot()
        return buildString {
            appendLine(header)
            appendLine("-".repeat(40))
            if (lines.isEmpty()) {
                appendLine("(пусто)")
            } else {
                lines.forEach { appendLine(it) }
            }
        }
    }

    /** Redacts anything that looks like a key, token or long opaque blob. */
    fun scrub(value: String): String {
        if (value.isEmpty()) return value
        var out = value
        // WireGuard/AmneziaWG keys: 44-char base64 ending with '='.
        out = KEY_PATTERN.replace(out, "<key>")
        // Bearer/hex tokens of 32+ chars.
        out = LONG_HEX.replace(out, "<token>")
        // qwer://import?token=***
        out = TOKEN_PARAM.replace(out, "token=<redacted>")
        // wdtt://connect?...&password=***
        out = WDTT_PASSWORD.replace(out, "password=<redacted>")
        return out
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9+/]{43}=")
    private val LONG_HEX = Regex("\\b[A-Fa-f0-9]{32,}\\b")
    private val TOKEN_PARAM = Regex("([?&]token=)[^&\\s]+")
    private val WDTT_PASSWORD = Regex("([?&]password=)[^&\\s]+")
}
