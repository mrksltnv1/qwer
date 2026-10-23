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

    override fun onCreate() {
        super.onCreate()
        repository = ProfileRepository(this)
        wdtt = WdttPlusAdapter(this)
        connectionManager = ConnectionManager(this, repository, wdtt)
        createNotificationChannel()
        Diagnostics.event(
            Event.APP_START,
            "sdk" to Build.VERSION.SDK_INT,
            "profiles" to repository.all().size,
            "wdttInstalled" to wdtt.availability().installed,
        )
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
