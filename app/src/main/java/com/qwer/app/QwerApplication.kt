package com.qwer.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.qwer.app.core.ConnectionManager
import com.qwer.app.core.ProfileRepository
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.engine.WdttPlusAdapter
import com.qwer.app.service.ConnectionForegroundService
import com.qwer.app.service.VpnNotification
import kotlinx.coroutines.launch

/**
 * Holds the single [ConnectionManager] instance.
 *
 * The manager lives in the Application (not an Activity) so that a rotating screen or a
 * recreated Activity does not tear down a live tunnel. The tunnel itself is held by the
 * engine's VpnService, which outlives any Activity.
 */
class QwerApplication : Application() {

    lateinit var repository: ProfileRepository
        private set
    lateinit var connectionManager: ConnectionManager
        private set
    lateinit var wdtt: WdttPlusAdapter
        private set

    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override fun onCreate() {
        super.onCreate()
        repository = ProfileRepository(this)
        wdtt = WdttPlusAdapter(this)
        connectionManager = ConnectionManager(this, repository, wdtt)
        // Screen-on / wake / network-change watchers. Without this the app had no
        // awareness of device lifecycle at all (root cause of "не восстанавливается
        // после сна" in 0.8.4).
        connectionManager.startLifecycleMonitoring()
        createNotificationChannel()

        // The status notification is now owned by ConnectionForegroundService, which is
        // the only component allowed to call startForeground(). Posting it here as well
        // would duplicate the shade entry, so this observer only keeps the state in
        // sync when the FGS is not running (e.g. after a cold start).
        applicationScope.launch {
            connectionManager.state.collect { st ->
                if (!ConnectionForegroundService.isRunning) {
                    VpnNotification.update(this@QwerApplication, st)
                }
            }
        }
        Diagnostics.event(
            Event.APP_START,
            "sdk" to Build.VERSION.SDK_INT,
            "profiles" to repository.all().size,
            "wdttInstalled" to wdtt.availability().installed,
        )
    }

    /**
     * Called by [ConnectionForegroundService.onDestroy].
     *
     * If the system killed the foreground service the process is on borrowed time: a
     * tunnel without a foreground guarantee will be frozen and its sockets will die.
     * Rather than leave a half-alive tunnel that the UI still calls "Connected", we
     * stop it and let the user (or a later wake) reconnect cleanly.
     */
    fun onForegroundServiceLost(intentional: Boolean = false) {
        if (intentional) {
            // Expected teardown: the tunnel is already down. Logging this as a warning
            // trained the reader to ignore the one line that signals a real loss.
            Diagnostics.event(Event.FOREGROUND_SERVICE, "stage" to "stopped")
            return
        }
        Diagnostics.warn(Event.FOREGROUND_SERVICE, null, "stage" to "lost")
        val st = connectionManager.state.value
        if (st is com.qwer.app.core.ConnectionState.Connected ||
            st is com.qwer.app.core.ConnectionState.Degraded ||
            st is com.qwer.app.core.ConnectionState.Verifying
        ) {
            connectionManager.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Состояние VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Показывает, что VPN-туннель активен"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "vpn_status"
    }
}
