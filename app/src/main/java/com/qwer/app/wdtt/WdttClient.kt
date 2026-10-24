package com.qwer.app.wdtt

import android.content.Context
import android.util.Base64
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs the WDTT Plus native client and extracts the WireGuard config it publishes.
 *
 * HOW WDTT ACTUALLY WORKS (established by reading its source, not guessed):
 *
 *  1. `libclient.so` is an *executable*, not a library: WDTT Plus starts it with
 *     `ProcessBuilder(nativeLibraryDir + "/libclient.so", "-peer", ..., "-mode", "vpn")`.
 *     No JNI, no second VpnService.
 *  2. Secrets never go on the command line. They are written to the child's stdin as
 *     `START_CONFIG|<base64url json>` — see NativeClientStartupConfig.kt upstream.
 *  3. The client registers with the server over TURN/DTLS, fetches a WireGuard config
 *     via `GETCONF`, starts a userspace WireGuard on `127.0.0.1:<local>`, and prints
 *     that config to stdout inside a box:
 *
 *         ╔══════════════ WireGuard Конфиг ══════════════╗
 *         ║ [Interface]                                  ║
 *         ...
 *         ╚══════════════════════════════════════════════╝
 *
 *  4. The Android side parses that box and brings up its own tunnel pointing at
 *     `Endpoint = 127.0.0.1:<local>`.
 *
 * Because the published config is a plain WireGuard config, our existing AmneziaWG
 * engine can host it directly — that is exactly what makes WDTT embeddable without a
 * second VPN service and without two tunnels fighting over the single VpnService slot.
 */
class WdttClient(private val context: Context) {

    sealed interface StartResult {
        /** Config received; the tunnel can now be started against it. */
        data class Ready(val config: String) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    private val _state = MutableStateFlow(WdttState())
    val state: StateFlow<WdttState> = _state.asStateFlow()

    private var process: Process? = null
    private var readerThread: Thread? = null

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts the native client and waits for the WireGuard config.
     *
     * @param workers how many TURN workers to spawn; WDTT divides this into groups of 9.
     */
    suspend fun start(link: WdttLink, workers: Int = 18): StartResult = withContext(Dispatchers.IO) {
        stop() // never overlap two clients: the local port would collide

        val binary = File(context.applicationInfo.nativeLibraryDir, "libclient.so")
        if (!binary.isFile || binary.length() == 0L) {
            Diagnostics.warn(Event.WDTT_ENGINE, null, "why" to "binary_missing")
            return@withContext StartResult.Failed(
                "В этом билде нет WDTT-движка (libclient.so). Установите сборку с WDTT."
            )
        }

        // Flags mirror WDTT Plus TunnelManager: without -device-info the native client
        // registers an incomplete device and the server can drop it, and without
        // -transport-experiment it falls back to an older transport path. Both were
        // missing here, and the visible symptom was the process exiting a few seconds
        // after start with no explanation.
        val cmd = listOf(
            binary.absolutePath,
            "-peer", link.peer,
            "-listen", "127.0.0.1:${link.localPort}",
            "-startup-config-stdin=true",
            "-n", workers.toString(),
            "-mode", "vpn",
            "-transport-experiment", "auto",
            "-device-id", deviceId(),
            "-device-info", deviceInfoJson(),
        )

        val proc = try {
            ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
                .start()
        } catch (e: Exception) {
            Diagnostics.warn(Event.WDTT_ENGINE, e, "stage" to "spawn")
            return@withContext StartResult.Failed("Не удалось запустить WDTT-клиент")
        }

        // Secrets go through stdin, never argv: /proc/<pid>/cmdline is world-readable
        // on Android and a password in it would be a real leak.
        try {
            val startup = JSONObject()
                .put("vk_hashes", link.hashes.joinToString(","))
                .put("connection_password", link.password)
                .put("custom_vk_client_id", "")
                .put("custom_vk_client_secret", "")
                .put("socks_username", "")
                .put("socks_password", "")
                .toString()
            val encoded = Base64.encodeToString(startup.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            proc.outputStream.write("START_CONFIG|$encoded\n".toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            Diagnostics.warn(Event.WDTT_ENGINE, e, "stage" to "stdin")
            runCatching { proc.destroyForcibly() }
            return@withContext StartResult.Failed("Не удалось передать параметры WDTT-клиенту")
        }

        process = proc
        _state.value = WdttState(running = true, phase = WdttPhase.CONNECTING)
        Diagnostics.event(Event.WDTT_ENGINE, "stage" to "started", "peer" to link.safeLabel())

        // Wait for the config box. The client needs to do a TURN allocation and a DTLS
        // handshake first, which takes several seconds on a mobile link.
        val config = awaitConfig(proc, timeoutMillis = CONFIG_TIMEOUT_MS)

        if (config == null) {
            val tail = _state.value.lastLine
            val alive = proc.isAlive
            // Exit status is the single most useful fact when the process dies, and it
            // was not recorded: the log only said alive=false.
            val exit = runCatching { if (alive) -1 else proc.exitValue() }.getOrDefault(-1)
            stop()
            Diagnostics.warn(
                Event.WDTT_ENGINE,
                null,
                "stage" to "no_config",
                "alive" to alive,
                "exit" to exit,
                "tail" to (tail ?: "-"),
            )
            return@withContext StartResult.Failed(
                when {
                    !alive -> "WDTT-клиент завершился: ${tail ?: "нет данных"}"
                    tail != null -> "WDTT не выдал конфигурацию: $tail"
                    else -> "WDTT не выдал конфигурацию за ${CONFIG_TIMEOUT_MS / 1000} с"
                }
            )
        }

        _state.value = WdttState(running = true, phase = WdttPhase.READY)
        StartResult.Ready(config)
    }

    /** Reads stdout until the config box closes, or the deadline passes. */
    private fun awaitConfig(proc: Process, timeoutMillis: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis

        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        readerThread = Thread {
            var collecting = false
            val sb = StringBuilder()
            try {
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    _state.value = _state.value.copy(lastLine = line.trim().take(160))

                    when {
                        line.contains("╔") && line.contains("WireGuard", ignoreCase = true) -> {
                            collecting = true
                            sb.clear()
                        }
                        collecting && line.contains("╚") -> {
                            configBox = sb.toString().trim()
                            _state.value = _state.value.copy(phase = WdttPhase.READY)
                            return@Thread
                        }
                        collecting && line.contains("║") -> {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) sb.appendLine(content)
                        }
                        line.contains("FATAL_AUTH") -> {
                            _state.value = _state.value.copy(
                                phase = WdttPhase.FAILED,
                                lastLine = "Неверный пароль или срок доступа истёк",
                            )
                            return@Thread
                        }
                    }
                }
            } catch (e: Exception) {
                // Stream closed (process died or stop() called) — normal teardown.
            }
        }.apply {
            isDaemon = true
            name = "wdtt-stdout"
            start()
        }

        while (System.currentTimeMillis() < deadline) {
            configBox?.let { return it }
            if (!proc.isAlive && configBox == null) return null
            Thread.sleep(120)
        }
        return configBox
    }

    @Volatile
    private var configBox: String? = null

    fun stop() {
        readerThread?.interrupt()
        readerThread = null
        configBox = null
        process?.let { proc ->
            runCatching {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        }
        process = null
        _state.value = WdttState()
    }

    /**
     * Device description handed to the client as `-device-info`.
     *
     * The reference implementation always sends this JSON; the client reports it to the
     * server alongside the password, and a missing value was one of the differences
     * between our launch and a working one.
     */
    private fun deviceInfoJson(): String {
        val locale = java.util.Locale.getDefault()
        val name = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                "device_name",
            )
        }.getOrNull().orEmpty().ifBlank {
            listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                .joinToString(" ").trim().ifBlank { "Android device" }
        }
        return JSONObject()
            .put("name", name)
            .put("manufacturer", android.os.Build.MANUFACTURER.orEmpty())
            .put("brand", android.os.Build.BRAND.orEmpty())
            .put("model", android.os.Build.MODEL.orEmpty())
            .put("android_version", android.os.Build.VERSION.RELEASE.orEmpty())
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("locale", locale.toLanguageTag())
            .put("country", locale.country.orEmpty())
            .put("time_zone", java.util.TimeZone.getDefault().id)
            .toString()
    }

    /** Per-install id persisted so the server keeps the device binding stable. */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("wdtt", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = WdttLink.deviceId()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    private companion object {
        /** TURN allocation + DTLS handshake + GETCONF, measured ~6–20 s on mobile. */
        const val CONFIG_TIMEOUT_MS = 45_000L
    }
}

data class WdttState(
    val running: Boolean = false,
    val phase: WdttPhase = WdttPhase.IDLE,
    val lastLine: String? = null,
)

enum class WdttPhase { IDLE, CONNECTING, READY, FAILED }
