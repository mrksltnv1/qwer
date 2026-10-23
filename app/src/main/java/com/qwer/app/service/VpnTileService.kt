package com.qwer.app.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.qwer.app.QwerApplication
import com.qwer.app.core.ConnectionState
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap connects or disconnects the last used profile.
 *
 * UX rules:
 *  - The tile reflects the real [ConnectionState], not a local guess.
 *  - While a connection is in flight the tile is disabled, so a double tap cannot
 *    queue two conflicting tunnel starts.
 *  - With no saved profile it opens the main app instead of silently doing nothing.
 *  - Tapping never starts an update or changes settings — only connect/disconnect.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        startWatching()
    }

    override fun onStopListening() {
        stopWatching()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? QwerApplication ?: return
        val state = app.connectionManager.state.value

        when {
            state is ConnectionState.Connected -> {
                Diagnostics.event(Event.TILE_ACTION, "action" to "disconnect")
                app.connectionManager.disconnect()
            }
            state.isBusy -> {
                // Ignore taps mid-transition; the tile is disabled but guard anyway.
                Diagnostics.event(Event.TILE_ACTION, "action" to "ignored_busy")
            }
            else -> {
                val last = app.repository.activeProfile()
                if (last == null) {
                    // Nothing to connect: bring the user to the app rather than no-op.
                    Diagnostics.event(Event.TILE_ACTION, "action" to "no_profile")
                    unlockAndRun { openApp() }
                } else {
                    Diagnostics.event(Event.TILE_ACTION, "action" to "connect", "profile" to last.id)
                    app.connectionManager.connect(last.id)
                }
            }
        }
        updateTile()
    }

    private fun openApp() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setComponent(
                ComponentName(packageName, "com.qwer.app.ui.MainActivity")
            )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivityAndCollapseCompat(intent) }
    }

    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseCompat(intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun startWatching() {
        stopWatching()
        val app = application as? QwerApplication ?: return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        watcher = s.launch {
            app.connectionManager.state.collectLatest {
                updateTile()
            }
        }
    }

    private fun stopWatching() {
        watcher?.cancel()
        watcher = null
        scope?.cancel()
        scope = null
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val app = application as? QwerApplication ?: return
        val state = app.connectionManager.state.value
        val hasProfile = app.repository.activeProfile() != null

        tile.state = when {
            state is ConnectionState.Connected -> Tile.STATE_ACTIVE
            state.isBusy -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = "qwer"
        tile.contentDescription = when {
            state is ConnectionState.Connected -> "VPN подключён. Нажмите, чтобы отключить"
            state.isBusy -> "VPN переключается"
            !hasProfile -> "Нет профиля. Нажмите, чтобы открыть qwer"
            else -> "VPN отключён. Нажмите, чтобы подключить"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                state is ConnectionState.Connected -> state.protocol.displayName
                state is ConnectionState.Verifying -> "Проверка…"
                state is ConnectionState.Connecting -> "Подключение…"
                state is ConnectionState.Degraded -> "Нестабильно"
                state is ConnectionState.Failed -> "Ошибка"
                !hasProfile -> "Нет профиля"
                else -> "Отключено"
            }
        }

        tile.updateTile()
    }
}
