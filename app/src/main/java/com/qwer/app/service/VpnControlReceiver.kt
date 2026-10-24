package com.qwer.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qwer.app.QwerApplication
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event

/**
 * Handles the action buttons on the status notification.
 *
 * A receiver is used instead of an Activity so the action works while the screen is
 * locked, and so it never brings the UI to the foreground just to disconnect.
 *
 * Both actions are idempotent: tapping "Отключить" on an already-stopped tunnel is a
 * no-op, and "Повторить" reuses the normal connect path (which already serializes on
 * its own mutex).
 */
class VpnControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? QwerApplication ?: return
        when (intent.action) {
            ACTION_DISCONNECT -> {
                Diagnostics.event(Event.TILE_ACTION, "action" to "notif_disconnect")
                app.connectionManager.disconnect()
            }
            ACTION_RECONNECT -> {
                val last = app.repository.activeProfile()
                if (last == null) {
                    Diagnostics.event(Event.TILE_ACTION, "action" to "notif_reconnect_no_profile")
                } else {
                    Diagnostics.event(Event.TILE_ACTION, "action" to "notif_reconnect")
                    app.connectionManager.connect(last.id)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "com.qwer.app.action.DISCONNECT"
        const val ACTION_RECONNECT = "com.qwer.app.action.RECONNECT"
    }
}
