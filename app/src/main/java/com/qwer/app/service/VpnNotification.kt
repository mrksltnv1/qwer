package com.qwer.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qwer.app.QwerApplication
import com.qwer.app.R
import com.qwer.app.core.ConnectionState
import com.qwer.app.core.FailureReason
import com.qwer.app.core.Protocol
import com.qwer.app.ui.MainActivity

/**
 * Builds the ongoing status notification.
 *
 * Rules this file enforces (the 0.8.4 build had no status notification at all):
 *  - The status text is derived from [ConnectionState] only. There is no second source
 *    of truth that could disagree with the UI.
 *  - A protocol name is shown only when a tunnel of that protocol is actually up.
 *    "Подключено · WireGuard" is impossible while the state is Degraded.
 *  - Transient states are visible: connecting, verifying, switching, stopping.
 *  - Nothing is shown as "Подключено" after connectivity is known to be lost.
 */
object VpnNotification {

    const val ID = 1001

    /** Maps a state to (title, text, ongoing). Pure function, unit-tested. */
    data class Content(val title: String, val text: String, val ongoing: Boolean)

    fun content(state: ConnectionState): Content = when (state) {
        ConnectionState.Disconnected -> Content(
            title = "qwer",
            text = "Отключено",
            ongoing = false,
        )
        ConnectionState.Connecting -> Content(
            title = "qwer",
            text = "Подключение…",
            ongoing = true,
        )
        ConnectionState.Verifying -> Content(
            title = "qwer",
            text = "Проверка соединения…",
            ongoing = true,
        )
        is ConnectionState.Connected -> Content(
            title = "qwer · ${state.protocol.displayName}",
            text = "Подключено",
            ongoing = true,
        )
        is ConnectionState.Degraded -> Content(
            title = "qwer · ${state.protocol.displayName}",
            text = "Связь нестабильна, проверяем…",
            ongoing = true,
        )
        is ConnectionState.Switching -> Content(
            title = "qwer",
            text = "Переключение: ${state.from.shortLabel()} → ${state.to.shortLabel()}",
            ongoing = true,
        )
        is ConnectionState.Failed -> Content(
            title = "qwer",
            text = failureText(state),
            ongoing = false,
        )
    }

    fun failureText(state: ConnectionState.Failed): String = when (state.reason) {
        FailureReason.NO_PROFILE -> "Нет профиля"
        FailureReason.VPN_PERMISSION_DENIED -> "Нет разрешения VPN"
        FailureReason.ENGINE_ERROR -> "Ошибка подключения"
        FailureReason.NO_NETWORK -> "Нет сети"
        FailureReason.PROBE_FAILED -> "Нет доступа в сеть"
        FailureReason.WDTT_UNAVAILABLE -> "WDTT недоступен"
        FailureReason.WDTT_HANDOVER_REQUIRED -> "Нужен WDTT Plus"
        FailureReason.IMPORT_FAILED -> "Ошибка импорта"
        FailureReason.COOLDOWN_ACTIVE -> "Подождите…"
    }

    private fun Protocol.shortLabel(): String = when (this) {
        Protocol.WIREGUARD -> "WireGuard"
        Protocol.AMNEZIAWG -> "AmneziaWG"
        Protocol.WDTT_PLUS -> "WDTT"
    }

    /**
     * Builds the notification for [state] WITHOUT posting it.
     *
     * Needed by [ConnectionForegroundService]: startForeground() requires a
     * Notification object, and posting it separately would duplicate the entry.
     */
    fun build(context: Context, state: ConnectionState): Notification {
        val c = content(state)
        val builder = NotificationCompat.Builder(context, QwerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_vpn)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setOngoing(c.ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))

        // Android 12+ defers FGS notifications by 10 s by default, which made the
        // status invisible for the first seconds of every connection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        when (state) {
            is ConnectionState.Connected -> builder.addAction(
                0, "Отключить", disconnectIntent(context),
            )
            ConnectionState.Disconnected -> builder.addAction(
                0, "Подключить", reconnectIntent(context),
            )
            is ConnectionState.Failed -> builder.addAction(
                0, "Повторить", reconnectIntent(context),
            )
            else -> Unit
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        }

        return builder.build()
    }

    /** Posts or updates the notification to match [state]. */
    fun update(context: Context, state: ConnectionState) {
        runCatching {
            NotificationManagerCompat.from(context).notify(ID, build(context, state))
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag(),
        )
    }

    private fun disconnectIntent(context: Context): PendingIntent {
        val intent = Intent(context, VpnControlReceiver::class.java)
            .setAction(VpnControlReceiver.ACTION_DISCONNECT)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag(),
        )
    }

    private fun reconnectIntent(context: Context): PendingIntent {
        val intent = Intent(context, VpnControlReceiver::class.java)
            .setAction(VpnControlReceiver.ACTION_RECONNECT)
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlag(),
        )
    }

    private fun pendingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
