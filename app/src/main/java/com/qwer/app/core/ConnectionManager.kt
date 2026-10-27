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
import com.qwer.app.service.ConnectionForegroundService
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
import kotlinx.coroutines.flow.collect
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

    /** Session-relative traffic counters; anchored at every successful connect. */
    private val traffic = TrafficSessionTracker(clock)
    private val _traffic = MutableStateFlow<TrafficSnapshot>(TrafficSnapshot.Unavailable)
    val trafficState: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()
    private var trafficJob: Job? = null

    /** Sleep / network-change / retry events. Decisions stay below, in this class. */
    private val lifecycle = NetworkLifecycleMonitor(context)
    private var lifecycleJob: Job? = null

    /** Bounded recovery attempts since the last healthy connection. */
    private var recoveryAttempt = 0

    /** Last time the tunnel was rebuilt because the transport changed. */
    private var lastNetworkRestartAt: Long = 0L

    /** Consecutive automatic restarts after the engine died; reset on success. */
    private var engineRestarts: Int = 0

    /** When the current tunnel became active; guards against restarting a fresh one. */
    private var tunnelStartedAt: Long = 0L

    /** Profile that was active before a failover, used to return after a failed switch. */
    private var preFailoverProfile: VpnProfile? = null

    /** Attempts made to get back to the pre-failover profile. */
    private var returnAttempts: Int = 0

    /** Last successfully read engine counters, used when a sample is unreadable. */
    private var lastCounters = com.qwer.app.engine.TunnelEngine.Counters(0, 0)

    /**
     * Serializes lifecycle recovery work.
     *
     * FIELD EVIDENCE: a burst of NETWORK_CHANGED events (14:27:41-14:27:56) ran
     * overlapping probes, each one resetting the retry counter and cancelling the
     * previous retry job. The log shows `retry_scheduled attempt=1` four times in
     * twelve seconds, then the tunnel was torn down. One trigger at a time, with the
     * latest state re-read inside the lock, removes the race.
     */
    private val recoveryMutex = Mutex()

    /**
     * Generation guard against stale operations.
     *
     * Coroutine cancellation is cooperative: the engine call may already be running on a
     * native thread and return later. Without this, an old start could write `Connected`
     * after a newer stop, or resurrect a tunnel the user closed.
     */
    private val opGate = OperationGate()

    /** Non-null only while an embedded WDTT client is running. */
    private var wdttClient: WdttClient? = null

    // Probe logging state. The monitor ticks every ~2.5 s, which is far too often to
    // write a log line per result.
    private var lastProbeOk: Boolean? = null
    private var lastProbeLogAt: Long = 0L

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

    /**
     * Starts the lifecycle watchers. Called once from the Application.
     *
     * Separated from the constructor so unit tests can build a ConnectionManager
     * without registering Android receivers.
     */
    fun startLifecycleMonitoring() {
        lifecycle.start()
        lifecycleJob?.cancel()
        lifecycleJob = scope.launch {
            lifecycle.triggers.collect { trigger ->
                onLifecycleTrigger(trigger)
            }
        }
    }

    /**
     * Reacts to wake / network changes without fighting the failover state machine.
     *
     * The rule: only *re-validate* here. Actual switching stays in the monitor loop, so
     * a wake event cannot race a failover decision.
     */
    private suspend fun onLifecycleTrigger(trigger: NetworkLifecycleMonitor.Trigger) {
        // Drop overlapping triggers instead of queueing them: the newest state is what
        // matters, and a queue would just replay stale bursts.
        if (!recoveryMutex.tryLock()) {
            Diagnostics.event(Event.SLEEP_RECOVERY, "trigger" to trigger.name, "skipped" to "busy")
            return
        }
        try {
        val current = _state.value
        if (current !is ConnectionState.Connected && current !is ConnectionState.Degraded) {
            // Not connected: nothing to recover. A pending connect is already in flight.
            return
        }
        Diagnostics.event(Event.SLEEP_RECOVERY, "trigger" to trigger.name)

        /*
         * A REAL TRANSPORT CHANGE NEEDS A NEW TUNNEL, NOT A PROBE.
         *
         * The userspace WireGuard socket is bound to the address of the network it was
         * created on. When the device moves from Wi-Fi to cellular (or between cells
         * with a new IP), the kernel keeps sending on that dead path: the tunnel still
         * reports "up", counters stop moving, and every probe times out. A probe cannot
         * fix that, and neither can keepalive — the packets go nowhere.
         *
         * The only reliable recovery is to tear the tunnel down and bring it up again so
         * the socket is created on the new default network. That is what this branch
         * does, and it is why roaming used to leave a "hung" tunnel.
         *
         * It is rate-limited by [MIN_RESTART_INTERVAL_MS] because carriers emit several
         * transport changes during one handover; restarting on each would thrash the
         * engine instead of healing it.
         */
        if (trigger == NetworkLifecycleMonitor.Trigger.NETWORK_CHANGED) {
            forceRestartOnNetworkChange()
            return
        }

        val ok = withContext(Dispatchers.IO) { probe.probe() }
        if (ok) {
            recoveryAttempt = 0
            lifecycle.cancelRetry()
            if (current is ConnectionState.Degraded) {
                val since = current.sinceMillis
                _state.value = ConnectionState.Connected(current.protocol, since, clock())
                failover.onConnected(since)
            }
            return
        }

        // Still no connectivity. Do NOT tear the tunnel down on a single failed probe:
        // the monitor window handles sustained failure. Just schedule a bounded retry.
        recoveryAttempt++
        if (recoveryAttempt <= MAX_RECOVERY_ATTEMPTS) {
            lifecycle.scheduleRetry(recoveryAttempt)
        } else {
            Diagnostics.warn(
                Event.SLEEP_RECOVERY,
                null,
                "stage" to "gave_up",
                "attempts" to recoveryAttempt,
            )
        }
        } finally {
            recoveryMutex.unlock()
        }
    }

    /**
     * Rebuilds the tunnel after a genuine transport change.
     *
     * Runs OUTSIDE [recoveryMutex] for the restart itself: connectInternal takes [mutex],
     * and holding both would deadlock against a concurrent user action.
     */
    private suspend fun forceRestartOnNetworkChange() {
        val profile = activeProfile ?: return
        val now = clock()
        /*
         * A tunnel younger than the settle window already has its socket on the current
         * network: it was created after the transport changed. Restarting it is pure
         * churn. The field log shows exactly that at 14:43:52 -> 14:43:58: a fresh
         * WireGuard tunnel was torn down 6 seconds after start, and the following WDTT
         * failover then had to compete with that teardown.
         */
        if (now - tunnelStartedAt < MIN_SETTLE_MS) {
            Diagnostics.event(
                Event.SLEEP_RECOVERY,
                "stage" to "restart_skipped",
                "why" to "fresh_tunnel",
                "age_ms" to (now - tunnelStartedAt),
            )
            return
        }
        if (now - lastNetworkRestartAt < MIN_RESTART_INTERVAL_MS) {
            Diagnostics.event(
                Event.SLEEP_RECOVERY,
                "stage" to "restart_skipped",
                "why" to "cooldown",
                "since_ms" to (now - lastNetworkRestartAt),
            )
            return
        }
        lastNetworkRestartAt = now
        Diagnostics.event(
            Event.SLEEP_RECOVERY,
            "stage" to "restart_on_network_change",
            "protocol" to profile.protocol,
        )
        // Release the guard before touching [mutex]; connectInternal needs it.
        recoveryMutex.unlock()
        try {
            mutex.withLock { stopTunnelLocked("network-change") }
            recoveryAttempt = 0
            lifecycle.cancelRetry()
            connectInternal(profile.id)
        } finally {
            // Re-acquire so the caller's `finally { unlock() }` stays balanced.
            recoveryMutex.lock()
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun connectInternal(profileId: String) = mutex.withLock {
        val ticket = opGate.begin()
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
        if (engine?.isUp() == true) stopForRestart()

        _state.value = ConnectionState.Connecting
        // Foreground guarantee: without it Android freezes the process after screen-off
        // and neither sleep recovery nor the status notification can ever run.
        withContext(Dispatchers.Main) { ConnectionForegroundService.start(context) }
        // Per-app routing is expressed as ExcludedApplications/IncludedApplications
        // lines in the [Interface] section. Applying it here keeps the engine untouched
        // and preserves the raw config stored in the Keystore.
        val routingLines = AppRouting.configLines(profile.appRouting)
        val routedConfig = if (routingLines.isEmpty()) raw else RoutingInjector.apply(raw, routingLines)
        if (routingLines.isNotEmpty()) {
            Diagnostics.event(
                Event.TUNNEL_START,
                "routing_apps" to AppRouting.selectedCount(profile.appRouting),
            )
        }

        val started = withContext(Dispatchers.IO) {
            runCatching {
                val eng = engine ?: engineFactory(context).also { engine = it }
                eng.up(routedConfig, tunnelName = TUNNEL_NAME)
            }
        }
        started.exceptionOrNull()?.let { err ->
            Diagnostics.warn(Event.TUNNEL_START_FAILED, err, "protocol" to profile.protocol)
            _state.value = ConnectionState.Failed(
                FailureReason.ENGINE_ERROR,
                engineFailure(err).userMessage(),
            )
            return@withLock
        }

        // A stale start must not claim the tunnel. If a newer command (stop or another
        // profile) ran while the engine was coming up, we tear down what we started.
        if (!opGate.isCurrent(ticket)) {
            Diagnostics.event(Event.TUNNEL_STOP, "reason" to "stale_start", "profile" to profile.id)
            withContext(Dispatchers.IO) { runCatching { engine?.down() } }
            engine = null
            withContext(Dispatchers.Main) { ConnectionForegroundService.stop(context) }
            return@withLock
        }

        activeProfile = profile
        repository.setActive(profile.id)
        tunnelStartedAt = clock()
        // A working tunnel clears the engine-restart ladder, so a failure hours later
        // still gets the full set of retries instead of an exhausted counter.
        engineRestarts = 0
        // Reaching the primary again means the failed-switch recovery worked.
        returnAttempts = 0
        if (profile.id != preFailoverProfile?.id) preFailoverProfile = null
        Diagnostics.event(Event.TUNNEL_START, "protocol" to profile.protocol, "profile" to profile.id)

        // A new tunnel instance means fresh counter baselines.
        traffic.startSession(profile.protocol)
        startTrafficSampling()

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
        val ticket = opGate.begin()
        val parsed = when (val r = WdttLink.parse(link)) {
            is WdttParseResult.Valid -> r.link
            is WdttParseResult.Invalid -> {
                Diagnostics.warn(Event.WDTT_ENGINE, null, "stage" to "parse")
                _state.value = ConnectionState.Failed(FailureReason.IMPORT_FAILED, r.reason)
                return
            }
        }

        if (engine?.isUp() == true) stopForRestart()

        _state.value = ConnectionState.Connecting
        withContext(Dispatchers.Main) { ConnectionForegroundService.start(context) }
        val client = wdttClient ?: WdttClient(context).also { wdttClient = it }

        val config = when (val res = client.start(parsed)) {
            is WdttClient.StartResult.Ready -> res.config
            is WdttClient.StartResult.Failed -> {
                Diagnostics.warn(Event.WDTT_ENGINE, null, "stage" to "start")
                _state.value = ConnectionState.Failed(FailureReason.WDTT_UNAVAILABLE, res.reason)
                // A failed switch must not leave the user with nothing. The field log
                // shows the failover ending here twice in a row with no further attempt:
                // the app sat in Failed while the WireGuard server was reachable again.
                scheduleReturnToPrimary()
                return
            }
        }

        // The published config is a normal WireGuard config, so the very same engine
        // that serves WG/AWG hosts it. One tunnel, one VpnService, one code path.
        //
        // CRITICAL: libclient.so is a child process of THIS app, so it shares our UID.
        // Its TURN traffic to <host>:<dtls> would be captured by our own 0.0.0.0/0
        // route and fed back into the tunnel — a self-amplifying loop. On a real device
        // that showed up as "174 969 packets sent, 0 bytes received" and 122 MB in 16 s.
        // Excluding our own package breaks the loop; libclient reaches TURN directly.
        //
        // This used to call withSelfExclusion(), which meant the user's per-app routing
        // was silently DROPPED for WDTT — the picker appeared to do nothing whenever the
        // active profile was WDTT. Routing is now applied here too, with our package
        // passed as an always-excluded entry so the loop guard survives every mode.
        val wdttRouting = AppRouting.configLines(
            profile.appRouting,
            alwaysExcluded = setOf(context.packageName),
        )
        val hostedConfig = if (wdttRouting.isEmpty()) {
            withSelfExclusion(config)
        } else {
            RoutingInjector.apply(config, wdttRouting)
        }
        if (wdttRouting.isNotEmpty()) {
            Diagnostics.event(
                Event.TUNNEL_START,
                "routing_apps" to AppRouting.selectedCount(profile.appRouting),
                "protocol" to profile.protocol,
            )
        }

        val started = withContext(Dispatchers.IO) {
            runCatching {
                val eng = engine ?: engineFactory(context).also { engine = it }
                eng.up(hostedConfig, tunnelName = TUNNEL_NAME)
            }
        }
        started.exceptionOrNull()?.let { err ->
            Diagnostics.warn(Event.TUNNEL_START_FAILED, err, "protocol" to profile.protocol)
            withContext(Dispatchers.IO) { runCatching { client.stop() } }
            wdttClient = null
            _state.value = ConnectionState.Failed(FailureReason.ENGINE_ERROR, engineFailure(err).userMessage())
            return
        }

        if (!opGate.isCurrent(ticket)) {
            Diagnostics.event(Event.TUNNEL_STOP, "reason" to "stale_start_wdtt")
            withContext(Dispatchers.IO) { runCatching { engine?.down() } }
            engine = null
            withContext(Dispatchers.IO) { runCatching { client.stop() } }
            wdttClient = null
            withContext(Dispatchers.Main) { ConnectionForegroundService.stop(context) }
            return
        }

        activeProfile = profile
        repository.setActive(profile.id)
        tunnelStartedAt = clock()
        Diagnostics.event(Event.WDTT_ENGINE, "stage" to "tunnel_up", "peer" to parsed.safeLabel())

        // A new tunnel instance means fresh counter baselines. This was missing for
        // WDTT: the switch to WDTT left the UI showing the previous protocol's numbers,
        // and after a failover the WDTT tunnel reported "нет данных от движка" because
        // no sampler was ever started for it.
        traffic.startSession(profile.protocol)
        startTrafficSampling()

        _state.value = ConnectionState.Verifying
        // We excluded ourselves from the tunnel, so a plain TCP probe would test the
        // DIRECT path and always succeed — a false "Connected". The only honest signal
        // for WDTT is the tunnel's own receive counter: if the TURN relay works, bytes
        // come back from 127.0.0.1:9000 through wg-go.
        val ok = awaitWdttTraffic()
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

    private suspend fun withLockStop(
        reason: String,
        /**
         * False when the caller IS the operation that owns the current ticket.
         *
         * This is the "стоп перед стартом" case: connectInternal takes a ticket, notices
         * a live engine, and tears it down before starting the new one. Invalidating
         * there cancels the ticket that is doing the work, so the very next
         * `opGate.isCurrent(ticket)` returns false and the fresh tunnel is destroyed.
         * The field log showed exactly that:
         *
         *   TUNNEL_START protocol=AMNEZIAWG
         *   TUNNEL_STOP  reason=before-start
         *   WDTT_ENGINE  stage=started
         *   TUNNEL_STOP  reason=stale_start_wdtt     <- self-inflicted
         *
         * A real user stop still invalidates; it arrives on a separate call.
         */
        invalidateTicket: Boolean = true,
        /**
         * Keep the foreground service alive across a restart. Stopping and immediately
         * re-starting it made the log read
         * `created -> destroyed 47s -> lost -> created` on every profile switch, and each
         * teardown briefly removed the foreground guarantee that keeps the process alive.
         */
        keepForeground: Boolean = false,
    ) {
        // Invalidate first: any operation still in flight becomes stale immediately and
        // will roll back its own resources instead of publishing state.
        if (invalidateTicket) opGate.invalidate()
        monitorJob?.cancel()
        monitorJob = null
        val had = engine != null || wdttClient != null
        withContext(Dispatchers.IO) { runCatching { engine?.down() } }
        engine = null
        // Kill the native client too, otherwise it keeps its local WireGuard port and
        // the next start would collide with a zombie.
        withContext(Dispatchers.IO) { runCatching { wdttClient?.stop() } }
        wdttClient = null
        // Release the traffic session and any scheduled recovery in one place, so no
        // timer survives a disconnect and turns into a phantom reconnect later.
        stopTrafficSampling()
        lifecycle.cancelRetry()
        recoveryAttempt = 0
        failover.onDisconnected()
        activeProfile = null
        repository.setActive(null)
        if (had) Diagnostics.event(Event.TUNNEL_STOP, "reason" to reason)
        _state.value = ConnectionState.Disconnected
        if (!keepForeground) {
            withContext(Dispatchers.Main) { ConnectionForegroundService.stop(context) }
        }
    }

    /**
     * Tears down a live tunnel on behalf of the operation that is about to replace it.
     * Deliberately does not invalidate the current ticket and does not drop the
     * foreground service: the caller continues in the same operation.
     */
    private suspend fun stopForRestart() = withLockStop(
        reason = "before-start",
        invalidateTicket = false,
        keepForeground = true,
    )

    /**
     * Adds `ExcludedApplications = <our package>` to the [Interface] section.
     *
     * Supported by the engine: Interface.java allow-list -> GoBackend calls
     * VpnService.Builder.addDisallowedApplication(). This is exactly how upstream
     * WDTT Plus keeps its own TURN sockets out of its tunnel.
     */
    private fun withSelfExclusion(config: String): String {
        if (config.contains("ExcludedApplications", ignoreCase = true)) return config
        val lines = config.lines().toMutableList()
        val idx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (idx < 0) return config
        lines.add(idx + 1, "ExcludedApplications = ${context.packageName}")
        Diagnostics.event(Event.WDTT_ENGINE, "stage" to "self_excluded", "pkg" to context.packageName)
        return lines.joinToString("\n")
    }

    /**
     * Waits until the tunnel actually RECEIVES data.
     *
     * Because the app is excluded from its own tunnel, a TCP probe would test the
     * direct path and always succeed — a false "Connected". The tunnel's own RX counter
     * is the only honest signal for WDTT: bytes arriving from 127.0.0.1:9000 mean the
     * TURN relay is actually carrying traffic.
     */
    private suspend fun awaitWdttTraffic(timeoutMillis: Long = 25_000L): Boolean {
        val eng = engine ?: return false
        val deadline = clock() + timeoutMillis
        var baseline = -1L
        while (clock() < deadline) {
            // A null reading means the engine could not be queried. Treating it as
            // zero would look like "tunnel is up but idle" and delay a real diagnosis,
            // so we simply skip this tick and keep waiting for a readable sample.
            val c = withContext(Dispatchers.IO) { eng.countersOrNull() }
                ?: run { delay(700); continue }
            if (baseline < 0) {
                baseline = c.rxBytes
            } else if (c.rxBytes - baseline >= 256) {
                Diagnostics.event(Event.WDTT_ENGINE, "stage" to "traffic_ok", "rx" to c.rxBytes)
                return true
            }
            delay(700)
        }
        Diagnostics.warn(Event.WDTT_ENGINE, null, "stage" to "no_traffic")
        return false
    }

    /**
     * Samples engine counters every [TRAFFIC_INTERVAL_MS] while a session is active.
     *
     * Deliberately runs off the UI thread and only while connected: a 2 s tick with no
     * session would wake the engine for nothing and drain battery.
     */
    private fun startTrafficSampling() {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            while (isActive) {
                val eng = engine
                if (eng == null) break
                val ok = runCatching { eng.isUp() }.getOrDefault(false)
                if (!ok) break
                val c = withContext(Dispatchers.IO) { eng.countersOrNull() }
                if (c == null) {
                    // Engine is up but statistics are unreadable: publish Unavailable so
                    // the UI says "нет данных" instead of showing a fake 0 B.
                    _traffic.value = com.qwer.app.core.TrafficSnapshot.Unavailable
                } else {
                    _traffic.value = traffic.sample(c.rxBytes, c.txBytes)
                }
                delay(TRAFFIC_INTERVAL_MS)
            }
        }
    }

    private fun stopTrafficSampling() {
        trafficJob?.cancel()
        trafficJob = null
        traffic.endSession()
        _traffic.value = TrafficSnapshot.Unavailable
    }

    private fun startMonitor(profile: VpnProfile) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var consecutive = 0
            while (isActive) {
                delay(failoverConfig.probeIntervalMillis)
                val eng = engine ?: break
                if (!eng.isUp()) {
                    /*
                     * THE 11:32 FIELD INCIDENT.
                     *
                     * The engine died, this branch set Failed and broke out of the loop.
                     * Nothing was logged, and no restart was scheduled, so the tunnel
                     * stayed dead until the user pressed reconnect 63 s later. The log
                     * only showed the FGS teardown that followed, which looked like the
                     * cause but was the consequence.
                     *
                     * Now: the death is recorded with its protocol, and a bounded
                     * restart is scheduled so a transient engine failure heals itself.
                     */
                    Diagnostics.warn(
                        Event.TUNNEL_START_FAILED,
                        null,
                        "stage" to "engine_died",
                        "protocol" to profile.protocol,
                        "profile" to profile.id,
                    )
                    _state.value = ConnectionState.Failed(
                        FailureReason.ENGINE_ERROR,
                        "Туннель неожиданно остановлен",
                    )
                    scheduleEngineRecovery(profile)
                    break
                }
                val ok = withContext(Dispatchers.IO) { probe.probe() }
                // The failover heuristic compares counter deltas; a null reading must
                // not be mistaken for "traffic stopped", so we fall back to the last
                // known values and let the probe drive the decision.
                val counters = withContext(Dispatchers.IO) { eng.countersOrNull() }
                    ?: lastCounters
                val now = clock()

                lastCounters = counters
                if (!ok) consecutive++ else consecutive = 0
                // A line every probe interval (~2.5 s) filled the whole 500-line buffer
                // with "ok=true" and pushed out the events that actually matter.
                // Log transitions and failures only, plus one heartbeat per minute so
                // the log still proves the monitor is alive.
                val transitioned = lastProbeOk != ok
                if (transitioned || !ok || now - lastProbeLogAt >= PROBE_HEARTBEAT_MS) {
                    Diagnostics.event(Event.PROBE_RESULT, "ok" to ok, "fails" to consecutive)
                    lastProbeLogAt = now
                }
                lastProbeOk = ok

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

    /**
     * Brings the tunnel back after the engine died on its own.
     *
     * Bounded and jitter-free on purpose: the engine death is a local failure, not a
     * network handover, so the retry ladder is short (2 s, 4 s, 8 s …). The counter is
     * reset on every successful connect, so a tunnel that works for hours and then
     * fails once still gets the full ladder.
     *
     * Runs in [scope], not in the monitor coroutine: the monitor has already broken out
     * of its loop and cannot restart itself.
     */
    private fun scheduleEngineRecovery(profile: VpnProfile) {
        if (engineRestarts >= MAX_ENGINE_RESTARTS) {
            Diagnostics.warn(
                Event.SLEEP_RECOVERY,
                null,
                "stage" to "engine_restart_gave_up",
                "attempts" to engineRestarts,
            )
            return
        }
        engineRestarts++
        val attempt = engineRestarts
        val delayMs = (RETRY_BASE_MS shl (attempt - 1).coerceIn(0, 4))
            .coerceAtMost(MAX_ENGINE_RESTART_DELAY_MS)
        Diagnostics.event(
            Event.SLEEP_RECOVERY,
            "stage" to "engine_restart_scheduled",
            "attempt" to attempt,
            "delay_ms" to delayMs,
        )
        scope.launch {
            delay(delayMs)
            Diagnostics.event(
                Event.SLEEP_RECOVERY,
                "stage" to "engine_restart",
                "attempt" to attempt,
                "profile" to profile.id,
            )
            // Tear down what is left, then reconnect through the normal path so the
            // operation gate, the FGS and the traffic session all behave as on a
            // manual reconnect.
            mutex.withLock { runCatching { stopTunnelLocked("engine-died") } }
            connectInternal(profile.id)
        }
    }

    private suspend fun performFailover(from: VpnProfile, decision: FailoverDecision.SwitchTo) {
        // Remembered so a failed switch can hand the user back to a working tunnel
        // instead of leaving the app in Failed with no way forward.
        preFailoverProfile = from
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
        // Also drop the old session's counters here: without this the UI kept rendering
        // the previous protocol's traffic while the switch was in progress.
        stopTrafficSampling()
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

    /**
     * Returns to the tunnel that was active before a failed failover.
     *
     * Bounded ladder (2 s, 4 s, 8 s, 16 s): the switch failed locally, and the primary
     * may simply need the WDTT client wound down before its port is free again.
     */
    private fun scheduleReturnToPrimary() {
        val primary = preFailoverProfile ?: return
        if (returnAttempts >= MAX_RETURN_ATTEMPTS) {
            Diagnostics.warn(
                Event.FAILOVER_EXHAUSTED,
                null,
                "stage" to "return_gave_up",
                "attempts" to returnAttempts,
            )
            return
        }
        returnAttempts++
        val attempt = returnAttempts
        val delayMs = (RETRY_BASE_MS shl (attempt - 1).coerceIn(0, 4))
            .coerceAtMost(MAX_ENGINE_RESTART_DELAY_MS)
        Diagnostics.event(
            Event.FAILOVER_TRIGGERED,
            "stage" to "return_to_primary",
            "attempt" to attempt,
            "delay_ms" to delayMs,
            "profile" to primary.id,
        )
        scope.launch {
            delay(delayMs)
            mutex.withLock { runCatching { stopTunnelLocked("return-to-primary") } }
            connectInternal(primary.id)
        }
    }

    private fun hasPhysicalNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Converts an engine exception into a structured failure.
     *
     * The engine AAR is a compile-time dependency, so `BackendException.Reason` is read
     * directly instead of by reflection. The reason NAME (a stable string) is what gets
     * classified, which keeps EngineErrorMapper pure and unit-testable.
     */
    private fun engineFailure(err: Throwable): EngineFailure {
        val backend = err as? org.amnezia.awg.backend.BackendException
        val reasonName = backend?.reason?.name
        val formatArg = backend?.format?.firstOrNull()?.toString()
        val failure = EngineErrorMapper.fromReasonCode(
            reasonCode = reasonName,
            rawMessage = err.message,
            formatArg = formatArg,
        )
        // The whole failure is logged once, with its stage, so a support ticket can be
        // matched to a code even when the user only sends a screenshot.
        Diagnostics.warn(
            Event.TUNNEL_START_FAILED,
            err,
            "stage" to failure.stage.name.lowercase(),
            "code" to failure.code,
        )
        return failure
    }


    /** Called from Application.onTerminate is unreliable; used by tests and shutdown. */
    fun shutdown() {
        scope.launch { mutex.withLock { withLockStop("shutdown") } }
        scope.cancel()
    }

    private companion object {
        const val TUNNEL_NAME = "qwer0"

        /** One "still alive" log line per minute at most. */
        const val PROBE_HEARTBEAT_MS = 60_000L

        /** Recovery is bounded: after this many failed attempts we stop and report. */
        const val MAX_RECOVERY_ATTEMPTS = 6

        /**
         * Minimum gap between forced restarts caused by network changes.
         *
         * A single Wi-Fi -> cellular handover can emit three or four transport changes
         * within ten seconds. Restarting on each would tear down a tunnel that is still
         * being established, so only the first one acts and the rest are logged.
         */
        const val MIN_RESTART_INTERVAL_MS = 15_000L

        /** Restarts after a spontaneous engine death before giving up. */
        const val MAX_ENGINE_RESTARTS = 5

        /** Base delay of the engine-restart ladder: 2 s, 4 s, 8 s … */
        const val RETRY_BASE_MS = 2_000L

        /** Never wait longer than this between engine restarts. */
        const val MAX_ENGINE_RESTART_DELAY_MS = 30_000L

        /**
         * A tunnel younger than this is not restarted on a transport change: it was
         * created after the change and already uses the current network.
         */
        const val MIN_SETTLE_MS = 20_000L

        /** Attempts to get back to the pre-failover profile before giving up. */
        const val MAX_RETURN_ATTEMPTS = 4

        /** Traffic UI refresh; 2 s is smooth enough and cheap enough for battery. */
        const val TRAFFIC_INTERVAL_MS = 2_000L
    }
}
