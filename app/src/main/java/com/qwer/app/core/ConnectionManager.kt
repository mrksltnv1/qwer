package com.qwer.app.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.engine.TunnelEngine
import com.qwer.app.engine.WdttPlusAdapter
import com.qwer.app.failover.FailoverConfig
import com.qwer.app.failover.FailoverController
import com.qwer.app.failover.FailoverDecision
import com.qwer.app.failover.HealthSample
import com.qwer.app.health.ConnectivityProbe
import com.qwer.app.health.TcpProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single owner of tunnel state.
 *
 * Invariants this class guarantees:
 *  - At most ONE tunnel is ever active (Android allows one VpnService; we never even
 *    attempt a second start while one is up).
 *  - [ConnectionState.Connected] is only published after a probe through the tunnel
 *    succeeded. Starting the engine alone yields [ConnectionState.Verifying].
 *  - The monitor loop is the only place that reacts to health, and it is serialized
 *    by a mutex so a manual action and a failover can never interleave.
 *  - The failover decision itself lives in [FailoverController] (pure, unit-tested);
 *    this class only executes the decision.
 */
class ConnectionManager(
    private val context: Context,
    private val repository: ProfileRepository,
    private val wdtt: WdttPlusAdapter,
    private val engineFactory: (Context) -> TunnelEngine = { TunnelEngine(it) },
    private val probe: ConnectivityProbe = TcpProbe(),
    private val failoverConfig: FailoverConfig = FailoverConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _autoFailoverEnabled = MutableStateFlow(true)
    val autoFailoverEnabled: StateFlow<Boolean> = _autoFailoverEnabled.asStateFlow()

    private var engine: TunnelEngine? = null
    private var monitorJob: Job? = null
    private var activeProfile: VpnProfile? = null
    private val failover = FailoverController(failoverConfig)

    /** Non-null when the OS still needs the user's one-time VPN consent. */
    fun vpnPermissionIntent(): Intent? = VpnService.prepare(context)

    // ---------------------------------------------------------------- user actions

    fun connect(profileId: String) {
        scope.launch { connectInternal(profileId) }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock { stopTunnelLocked("user") }
        }
    }

    fun reconnect() {
        val id = activeProfile?.id ?: repository.activeProfile()?.id ?: return
        scope.launch {
            mutex.withLock { stopTunnelLocked("reconnect") }
            connectInternal(id)
        }
    }

    fun setAutoFailover(enabled: Boolean) {
        _autoFailoverEnabled.value = enabled
        if (!enabled) failover.onDisconnected()
    }

    // ---------------------------------------------------------------- internals

    private suspend fun connectInternal(profileId: String) = mutex.withLock {
        val profile = repository.find(profileId) ?: run {
            _state.value = ConnectionState.Failed(FailureReason.NO_PROFILE)
            return@withLock
        }
        val raw = repository.configFor(profileId) ?: run {
            Diagnostics.warn(Event.TUNNEL_START_FAILED, null, "why" to "config_missing")
            _state.value = ConnectionState.Failed(
                FailureReason.IMPORT_FAILED,
                "Секрет профиля недоступен — импортируйте заново",
            )
            return@withLock
        }

        // WDTT is not an embeddable engine; it is a handover (see WdttPlusAdapter).
        if (profile.protocol == Protocol.WDTT_PLUS) {
            handOverToWdtt(profile)
            return@withLock
        }

        // One tunnel at a time: never start over a live one.
        if (engine?.isUp() == true) stopTunnelLocked("before-start")

        _state.value = ConnectionState.Connecting
        val started = withContext(Dispatchers.IO) {
            runCatching {
                val eng = engine ?: engineFactory(context).also { engine = it }
                eng.up(raw, tunnelName = TUNNEL_NAME)
            }
        }
        started.exceptionOrNull()?.let { err ->
            Diagnostics.warn(Event.TUNNEL_START_FAILED, err, "protocol" to profile.protocol)
            _state.value = ConnectionState.Failed(
                FailureReason.ENGINE_ERROR,
                humanEngineError(err),
            )
            return@withLock
        }

        activeProfile = profile
        repository.setActive(profile.id)
        Diagnostics.event(Event.TUNNEL_START, "protocol" to profile.protocol, "profile" to profile.id)

        // Up, but not yet proven. Verify before showing "Connected".
        _state.value = ConnectionState.Verifying
        val ok = withContext(Dispatchers.IO) { probe.probe() }
        val now = clock()
        if (ok) {
            failover.onConnected(now)
            _state.value = ConnectionState.Connected(profile.protocol, now, now)
            startMonitor(profile)
        } else {
            // Tunnel is up but useless: enter the degraded path and let the monitor
            // decide, rather than lying about being connected.
            Diagnostics.warn(Event.PROBE_RESULT, null, "initial" to "failed")
            _state.value = ConnectionState.Degraded(profile.protocol, now, 1)
            startMonitor(profile)
        }
    }

    private fun handOverToWdtt(profile: VpnProfile) {
        val handedOver = wdtt.handover().isSuccess
        if (handedOver) {
            // Honest state: we are not connected, the other app owns the tunnel now.
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_HANDOVER_REQUIRED,
                "Открыт WDTT Plus. Подключение выполняйте в нём — два VPN одновременно Android не разрешает.",
            )
            activeProfile = profile
            repository.setActive(profile.id)
        } else {
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_UNAVAILABLE,
                "WDTT Plus не установлен на устройстве",
            )
        }
    }

    private suspend fun stopTunnelLocked(reason: String) = withLockStop(reason)

    private suspend fun withLockStop(reason: String) {
        monitorJob?.cancel()
        monitorJob = null
        val had = engine != null
        withContext(Dispatchers.IO) { runCatching { engine?.down() } }
        engine = null
        failover.onDisconnected()
        activeProfile = null
        repository.setActive(null)
        if (had) Diagnostics.event(Event.TUNNEL_STOP, "reason" to reason)
        _state.value = ConnectionState.Disconnected
    }

    private fun startMonitor(profile: VpnProfile) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var consecutive = 0
            while (isActive) {
                delay(failoverConfig.probeIntervalMillis)
                val eng = engine ?: break
                if (!eng.isUp()) {
                    _state.value = ConnectionState.Failed(
                        FailureReason.ENGINE_ERROR,
                        "Туннель неожиданно остановлен",
                    )
                    break
                }
                val ok = withContext(Dispatchers.IO) { probe.probe() }
                val counters = withContext(Dispatchers.IO) { eng.counters() }
                val now = clock()

                if (!ok) consecutive++ else consecutive = 0
                Diagnostics.event(Event.PROBE_RESULT, "ok" to ok, "fails" to consecutive)

                if (ok) {
                    val current = _state.value
                    if (current !is ConnectionState.Connected || current.protocol != profile.protocol) {
                        val since = (current as? ConnectionState.Connected)?.connectedAtMillis ?: now
                        _state.value = ConnectionState.Connected(profile.protocol, since, now)
                        failover.onConnected(since)
                    } else {
                        _state.value = current.copy(lastProbeMillis = now)
                    }
                    continue
                }

                val degradedSince = (_state.value as? ConnectionState.Degraded)?.sinceMillis ?: now
                _state.value = ConnectionState.Degraded(profile.protocol, degradedSince, consecutive)

                if (!_autoFailoverEnabled.value) {
                    Diagnostics.event(Event.FAILOVER_SUPPRESSED, "why" to "disabled_by_user")
                    continue
                }

                val target = repository.failoverTarget()
                val decision = failover.evaluate(
                    HealthSample(
                        nowMillis = now,
                        probeOk = false,
                        physicalNetworkAvailable = hasPhysicalNetwork(),
                        rxBytes = counters.rxBytes,
                        txBytes = counters.txBytes,
                    ),
                    currentProtocol = profile.protocol,
                    failoverTarget = target?.protocol,
                )
                when (decision) {
                    is FailoverDecision.None -> Unit
                    is FailoverDecision.GiveUp -> {
                        Diagnostics.event(Event.FAILOVER_EXHAUSTED, "why" to decision.reason)
                        _state.value = ConnectionState.Failed(
                            FailureReason.PROBE_FAILED,
                            decision.reason,
                        )
                        break
                    }
                    is FailoverDecision.SwitchTo -> {
                        Diagnostics.event(
                            Event.FAILOVER_TRIGGERED,
                            "from" to profile.protocol,
                            "to" to decision.target,
                            "why" to decision.reason,
                        )
                        performFailover(profile, decision)
                        break
                    }
                }
            }
        }
    }

    private suspend fun performFailover(from: VpnProfile, decision: FailoverDecision.SwitchTo) {
        _state.value = ConnectionState.Switching(from.protocol, decision.target)
        val target = repository.failoverTarget()
        if (target == null) {
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_UNAVAILABLE,
                "Резервный профиль WDTT не настроен",
            )
            return
        }
        // Stop first: Android permits a single active VpnService.
        withContext(Dispatchers.IO) { runCatching { engine?.down() } }
        engine = null
        failover.onDisconnected()

        if (wdtt.handover().isSuccess) {
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_HANDOVER_REQUIRED,
                "Основной туннель остановлен. Открыт WDTT Plus — подключитесь в нём.",
            )
        } else {
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_UNAVAILABLE,
                "WDTT Plus не установлен: переключение невозможно",
            )
        }
    }

    private fun hasPhysicalNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun humanEngineError(err: Throwable): String {
        val msg = err.message.orEmpty()
        return when {
            msg.contains("VPN_NOT_AUTHORIZED", ignoreCase = true) ->
                "Нет разрешения Android на VPN"
            msg.contains("BadConfig", ignoreCase = true) ->
                "Конфигурация отклонена движком"
            msg.contains("TUNNEL_MISSING_CONFIG", ignoreCase = true) ->
                "Конфигурация не найдена"
            else -> "Не удалось запустить туннель"
        }
    }

    /** Called from Application.onTerminate is unreliable; used by tests and shutdown. */
    fun shutdown() {
        scope.launch { mutex.withLock { withLockStop("shutdown") } }
        scope.cancel()
    }

    private companion object {
        const val TUNNEL_NAME = "qwer0"
    }
}
