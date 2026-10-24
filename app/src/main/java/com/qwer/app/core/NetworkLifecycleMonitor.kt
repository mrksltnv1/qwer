package com.qwer.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Emits the moments when the tunnel must be re-validated.
 *
 * WHY THIS EXISTS
 * ---------------
 * Version 0.8.4 had no awareness of the device lifecycle at all: no screen-on/off
 * handling, no ConnectivityManager callback, no boot handling. The health loop kept
 * probing every 2.5 s, but after a long sleep Android freezes the process, the socket
 * state goes stale and — critically — nothing forced a re-probe on wake. The result was
 * the reported "соединение не восстанавливается после сна": the UI kept showing the
 * pre-sleep state until the next scheduled probe, and if the tunnel had silently died
 * the counters never moved but the state stayed `Connected`.
 *
 * This class only *reports* events. All decisions stay in ConnectionManager, so sleep
 * recovery, failover and manual actions share one state machine instead of racing.
 */
class NetworkLifecycleMonitor(private val context: Context) {

    enum class Trigger {
        /** Device woke up; tunnel sockets may be dead even if the UI says Connected. */
        DEVICE_WAKE,
        /** Screen turned on: cheap moment to re-validate before the user notices. */
        SCREEN_ON,
        /** Default network changed (Wi-Fi <-> cellular, or a new SSID). */
        NETWORK_CHANGED,
        /** Connectivity came back after an outage. */
        NETWORK_AVAILABLE,
        /** Connectivity dropped; not an error of the tunnel itself. */
        NETWORK_LOST,
        /** A retry scheduled after a failed recovery. */
        RETRY,
    }

    private val _triggers = MutableSharedFlow<Trigger>(extraBufferCapacity = 4)
    val triggers: SharedFlow<Trigger> = _triggers.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var retryJob: Job? = null

    /**
     * Filters connectivity callbacks down to real connection changes.
     *
     * onCapabilitiesChanged fires for ANY capability change — signal strength,
     * bandwidth estimate, validation state — not only when the transport switches.
     * On a real device that produced bursts of three NETWORK_CHANGED events within
     * three seconds, each triggering a recovery probe. The gate is a pure class with
     * its own tests, including a replay of that exact field-log sequence.
     */
    private val changeGate = NetworkChangeGate()

    /** True while the device is in an interactive (awake) state as far as we can tell. */
    @Volatile
    var screenInteractive: Boolean = true
        private set

    fun start() {
        registerNetworkCallback()
        registerScreenReceiver()
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
        unregisterNetworkCallback()
        unregisterScreenReceiver()
        scope.cancel()
    }

    // ---------------------------------------------------------------- network

    private fun registerNetworkCallback() {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Diagnostics.event(Event.NETWORK_EVENT, "kind" to "available")
                _triggers.tryEmit(Trigger.NETWORK_AVAILABLE)
            }

            override fun onLost(network: Network) {
                Diagnostics.event(Event.NETWORK_EVENT, "kind" to "lost")
                _triggers.tryEmit(Trigger.NETWORK_LOST)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                // A transport switch (Wi-Fi -> cellular) arrives here, not as onLost.
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }

                val previous = changeGate.currentTransport()
                if (!changeGate.accept(transport, System.currentTimeMillis())) {
                    // Same transport, or a transition inside the guard window: the
                    // tunnel path did not really change, so no probe is needed.
                    return
                }

                Diagnostics.event(
                    Event.NETWORK_EVENT,
                    "kind" to "changed",
                    "transport" to transport,
                    "from" to (previous ?: "none"),
                )
                _triggers.tryEmit(Trigger.NETWORK_CHANGED)
            }
        }

        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
            .onFailure { Diagnostics.warn(Event.NETWORK_EVENT, it, "stage" to "register") }
    }

    private fun unregisterNetworkCallback() {
        val cb = callback ?: return
        callback = null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    // ---------------------------------------------------------------- screen

    private fun registerScreenReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenInteractive = true
                        Diagnostics.event(Event.NETWORK_EVENT, "kind" to "screen_on")
                        _triggers.tryEmit(Trigger.SCREEN_ON)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenInteractive = false
                        Diagnostics.event(Event.NETWORK_EVENT, "kind" to "screen_off")
                    }
                    // The definitive "we were asleep" signal. ACTION_SCREEN_ON can be
                    // missed while the process is frozen; USER_PRESENT is delivered on
                    // unlock and therefore after every real wake the user notices.
                    Intent.ACTION_USER_PRESENT -> {
                        screenInteractive = true
                        Diagnostics.event(Event.NETWORK_EVENT, "kind" to "user_present")
                        _triggers.tryEmit(Trigger.DEVICE_WAKE)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
        }.onFailure { Diagnostics.warn(Event.NETWORK_EVENT, it, "stage" to "receiver") }
    }

    private fun unregisterScreenReceiver() {
        val r = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(r) }
    }

    // ---------------------------------------------------------------- retry

    /**
     * Schedules a bounded, jittered retry after a failed recovery.
     *
     * Exponential backoff with jitter: without it, several recovery paths (wake,
     * network-changed, monitor tick) can fire together and hammer the engine with
     * simultaneous starts — which is exactly the "конфликтующие процедуры" the brief
     * forbids. A single scheduled retry job replaces any previous one.
     *
     * @param attempt 1-based attempt number; beyond [MAX_BACKOFF_ATTEMPT] the delay caps.
     */
    fun scheduleRetry(attempt: Int) {
        retryJob?.cancel()
        val base = RETRY_BASE_MS * (1L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_ATTEMPT))
        val capped = base.coerceAtMost(RETRY_MAX_MS)
        // +/-30% jitter so a fleet of devices never retries in lockstep.
        val jitter = (capped * 0.3).toLong().coerceAtLeast(1)
        val delayMs = capped + Random.nextLong(-jitter, jitter + 1)
        Diagnostics.event(Event.SLEEP_RECOVERY, "stage" to "retry_scheduled", "attempt" to attempt, "delay_ms" to delayMs)
        retryJob = scope.launch {
            delay(delayMs.coerceAtLeast(500))
            if (isActive) _triggers.tryEmit(Trigger.RETRY)
        }
    }

    fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    companion object {
        /** 2 s, 4 s, 8 s, 16 s … */
        const val RETRY_BASE_MS = 2_000L
        const val MAX_BACKOFF_ATTEMPT = 5
        /** Never wait longer than this between recovery attempts. */
        const val RETRY_MAX_MS = 60_000L

    }
}
