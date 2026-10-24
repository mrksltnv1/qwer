package com.qwer.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.qwer.app.QwerApplication
import com.qwer.app.core.ConnectionState
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a tunnel is up.
 *
 * WHY THIS IS THE CRITICAL FIX
 * ----------------------------
 * Android only treats an app as long-running if it has a *foreground* service. Until
 * this class existed, qwer started the engine's VpnService with a plain startService
 * and never called startForeground(). The consequence chain was:
 *
 *   no foreground service
 *     -> process is frozen/killed soon after the screen goes off
 *     -> ACTION_USER_PRESENT / SCREEN_ON / NetworkCallback are never delivered
 *     -> the sleep-recovery code never runs, the tunnel stays dead
 *     -> the status notification is never posted either (it was posted from a
 *        background coroutine that stops running with the process)
 *
 * That single gap explains three of the five reported problems: no recovery after
 * sleep, no notification, and poor connection retention in bad conditions.
 *
 * The engine's VpnService is a compiled AAR dependency and cannot be subclassed to add
 * startForeground, so this separate service exists purely to hold the process in the
 * foreground and own the status notification. It observes the same ConnectionState the
 * UI uses, so the shade can never disagree with the screen.
 */
class ConnectionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var startedAt: Long = 0

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        isRunning = true
        Diagnostics.event(Event.FOREGROUND_SERVICE, "stage" to "created")

        // Must enter the foreground within a few seconds of start, otherwise the system
        // kills us with a RemoteServiceException.
        goForeground(ConnectionState.Connecting)

        watcher = scope.launch {
            val app = application as? QwerApplication ?: return@launch
            app.connectionManager.state.collectLatest { state ->
                goForeground(state)
                if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
                    // Do not drop the foreground immediately: a failover or a retry can
                    // follow within a second, and tearing down/up the FGS on every
                    // transient state would itself cause churn.
                    delay(STOP_GRACE_MS)
                    val latest = app.connectionManager.state.value
                    if (latest is ConnectionState.Disconnected || latest is ConnectionState.Failed) {
                        Diagnostics.event(Event.FOREGROUND_SERVICE, "stage" to "stopping")
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // STICKY: if the system does kill us, restart and re-attach to whatever state
        // the ConnectionManager is in. Returning NOT_STICKY would silently end the
        // foreground guarantee for the rest of the session.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        isRunning = false
        Diagnostics.event(
            Event.FOREGROUND_SERVICE,
            "stage" to "destroyed",
            "lifetime_ms" to (System.currentTimeMillis() - startedAt),
        )
        // Tell ConnectionManager the foreground guarantee is gone so it can decide
        // whether the tunnel should be torn down rather than left half-alive.
        // A stop we asked for is not a loss: the tunnel is already down by then.
        (application as? QwerApplication)?.onForegroundServiceLost(intentional = intentionalStop)
        intentionalStop = false
        super.onDestroy()
    }

    private fun goForeground(state: ConnectionState) {
        val notification = VpnNotification.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                VpnNotification.ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } else {
            startForeground(VpnNotification.ID, notification)
        }
    }

    companion object {
        private const val STOP_GRACE_MS = 4_000L

        /** True while the foreground guarantee is held; read by the Application. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * True between our own [stop] call and the actual onDestroy.
         *
         * WHY: onDestroy is called for BOTH "the system killed us" and "we stopped on
         * purpose". The field log showed a false alarm on every normal disconnect:
         *
         *   FOREGROUND_SERVICE stage=destroyed lifetime_ms=47347
         *   ! FOREGROUND_SERVICE stage=lost        <- normal teardown, reported as a loss
         *
         * A real loss must be distinguishable from an expected stop, otherwise the one
         * warning that matters gets lost in noise.
         */
        @Volatile
        private var intentionalStop: Boolean = false

        /** Idempotent: safe to call on every connect attempt. */
        fun start(context: Context) {
            val intent = Intent(context, ConnectionForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Diagnostics.warn(Event.FOREGROUND_SERVICE, it, "stage" to "start_failed")
            }
        }

        fun stop(context: Context) {
            // Set before stopService: onDestroy runs on the main thread right after.
            intentionalStop = true
            runCatching {
                context.stopService(Intent(context, ConnectionForegroundService::class.java))
            }
        }
    }
}
