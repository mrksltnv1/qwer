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
import com.qwer.app.wdtt.WdttClient
import com.qwer.app.wdtt.WdttLink
import com.qwer.app.wdtt.WdttParseResult
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

    /** Non-null only while an embedded WDTT client is running. */
    private var wdttClient: WdttClient? = null

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

        // WDTT is embedded now: its native client publishes a plain WireGuard config
        // pointing at 127.0.0.1, which our existing engine then hosts. No second
        // VpnService, no handover to another app.
        if (profile.protocol == Protocol.WDTT_PLUS) {
            connectWdttLocked(profile, raw)
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

    private suspend fun stopTunnelLocked(reason: String) = withLockStop(reason)

    /**
     * Starts an embedded WDTT tunnel.
     *
     * The stored "config" for a WDTT profile is the `wdtt://` link itself. We hand it to
     * [WdttClient], which spawns the native client with secrets on stdin, waits for the
     * WireGuard config it publishes on stdout, and returns it. That config targets
     * 127.0.0.1:<local>; we then bring it up with the same engine used for WG/AWG.
     */
    private suspend fun connectWdttLocked(profile: VpnProfile, link: String) {
        val parsed = when (val r = WdttLink.parse(link)) {
            is WdttParseResult.Valid -> r.link
            is WdttParseResult.Invalid -> {
                Diagnostics.warn(Event.WDTT_ENGINE, null, "stage" to "parse")
                _state.value = ConnectionState.Failed(FailureReason.IMPORT_FAILED, r.reason)
                return
            }
        }

        if (engine?.isUp() == true) stopTunnelLocked("before-start")

        _state.value = ConnectionState.Connecting
        val client = wdttClient ?: WdttClient(context).also { wdttClient = it }

        val config = when (val res = client.start(parsed)) {
            is WdttClient.StartResult.Ready -> res.config
            is WdttClient.StartResult.Failed -> {
                Diagnostics.warn(Event.WDTT_ENGINE, null, "stage" to "start")
                _state.value = ConnectionState.Failed(FailureReason.WDTT_UNAVAILABLE, res.reason)
                return
            }
        }

        // The published config is a normal WireGuard config, so the very same engine
        // that serves WG/AWG hosts it. One tunnel, one VpnService, one code path.
        val started = withContext(Dispatchers.IO) {
            runCatching {
                val eng = engine ?: engineFactory(context).also { engine = it }
                eng.up(config, tunnelName = TUNNEL_NAME)
            }
        }
        started.exceptionOrNull()?.let { err ->
            Diagnostics.warn(Event.TUNNEL_START_FAILED, err, "protocol" to profile.protocol)
            withContext(Dispatchers.IO) { runCatching { client.stop() } }
            wdttClient = null
            _state.value = ConnectionState.Failed(FailureReason.ENGINE_ERROR, humanEngineError(err))
            return
        }

        activeProfile = profile
        repository.setActive(profile.id)
        Diagnostics.event(Event.WDTT_ENGINE, "stage" to "tunnel_up", "peer" to parsed.safeLabel())

        _state.value = ConnectionState.Verifying
        val ok = withContext(Dispatchers.IO) { probe.probe() }
        val now = clock()
        if (ok) {
            failover.onConnected(now)
            _state.value = ConnectionState.Connected(profile.protocol, now, now)
            startMonitor(profile)
        } else {
            Diagnostics.warn(Event.PROBE_RESULT, null, "initial" to "failed")
            _state.value = ConnectionState.Degraded(profile.protocol, now, 1)
            startMonitor(profile)
        }
    }

    private suspend fun withLockStop(reason: String) {
        monitorJob?.cancel()
        monitorJob = null
        val had = engine != null || wdttClient != null
        withContext(Dispatchers.IO) { runCatching { engine?.down() } }
        engine = null
        // Kill the native client too, otherwise it keeps its local WireGuard port and
        // the next start would collide with a zombie.
        withContext(Dispatchers.IO) { runCatching { wdttClient?.stop() } }
        wdttClient = null
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

        // Stop the current tunnel first: Android permits a single active VpnService,
        // and the WDTT client needs its local WireGuard port free.
        withContext(Dispatchers.IO) { runCatching { engine?.down() } }
        engine = null
        withContext(Dispatchers.IO) { runCatching { wdttClient?.stop() } }
        wdttClient = null
        failover.onDisconnected()
        activeProfile = null
        repository.setActive(null)

        // Embedded switch: bring WDTT up through the same engine as any other config.
        val wdttConfig = repository.configFor(target.id)
        if (wdttConfig == null) {
            _state.value = ConnectionState.Failed(
                FailureReason.WDTT_UNAVAILABLE,
                "Секрет резервного WDTT-профиля недоступен",
            )
            return
        }
        connectWdttLocked(target, wdttConfig)
    }

    private fun hasPhysicalNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun humanEngineError(err: Throwable): String {
        // The AmneziaWG engine throws BackendException with a machine-readable Reason.
        // Showing only "engine error" is useless for support, so map every reason to a
        // concrete, actionable sentence. Reflection is avoided: the engine AAR is a
        // compile-time dependency, so we can reference the type directly.
        val reason = runCatching {
            (err as? org.amnezia.awg.backend.BackendException)?.reason?.name
        }.getOrNull()

        val byReason = when (reason) {
            "VPN_NOT_AUTHORIZED" -> "Нет разрешения Android на VPN"
            "TUNNEL_MISSING_CONFIG" -> "Конфигурация не найдена"
            "UNABLE_TO_START_VPN" -> "Не удалось поднять VPN-сервис"
            "TUN_CREATION_ERROR" -> "Не удалось создать сетевой интерфейс"
            "DNS_RESOLUTION_FAILURE" -> "Не удалось разрешить адрес сервера"
            "GO_ACTIVATION_ERROR_CODE" -> {
                val code = runCatching {
                    (err as org.amnezia.awg.backend.BackendException).format.firstOrNull()?.toString()
                }.getOrNull()
                "Движок отказал при запуске (код $code)"
            }
            "AWG_QUICK_CONFIG_ERROR_CODE" -> "awg-quick не принял конфигурацию"
            "UNKNOWN_KERNEL_MODULE_NAME" -> "Ядро не поддерживает AmneziaWG"
            else -> null
        }
        if (byReason != null) return byReason

        val msg = err.message.orEmpty()
        return when {
            msg.contains("VPN_NOT_AUTHORIZED", ignoreCase = true) ->
                "Нет разрешения Android на VPN"
            msg.contains("BadConfig", ignoreCase = true) ||
                msg.contains("Bad configuration", ignoreCase = true) ->
                "Движок отклонил конфигурацию: ${msg.take(120)}"
            msg.isNotBlank() -> "Не удалось запустить туннель: ${msg.take(120)}"
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
