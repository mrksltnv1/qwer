package com.myvpn.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myvpn.app.MyVpnApplication
import com.myvpn.app.BuildConfig
import com.myvpn.app.config.ConfigImporter
import com.myvpn.app.config.ImportResult
import com.myvpn.app.core.ConnectionState
import com.myvpn.app.core.Protocol
import com.myvpn.app.core.VpnProfile
import com.myvpn.app.deeplink.DeepLinkHandler
import com.myvpn.app.diagnostics.Diagnostics
import com.myvpn.app.diagnostics.Diagnostics.Event

/**
 * Single screen. Deliberately holds no tunnel logic: it renders [ConnectionState] and
 * forwards user intents to [com.myvpn.app.core.ConnectionManager].
 */
class MainActivity : ComponentActivity() {

    private val app: MyVpnApplication
        get() = application as MyVpnApplication

    private var pendingProfileId by mutableStateOf<String?>(null)
    private var importMessage by mutableStateOf<String?>(null)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        Diagnostics.event(Event.VPN_PERMISSION, "granted" to granted)
        val id = pendingProfileId
        pendingProfileId = null
        if (granted && id != null) {
            app.connectionManager.connect(id)
        } else if (!granted) {
            importMessage = "Без разрешения Android VPN подключение невозможно"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val state by app.connectionManager.state.collectAsStateWithLifecycle()
            val auto by app.connectionManager.autoFailoverEnabled.collectAsStateWithLifecycle()
            var profiles by mutableStateOf(app.repository.all())
            var activeId by mutableStateOf(app.repository.activeProfile()?.id)

            MyVpnTheme {
                MainScreen(
                    state = state,
                    profiles = profiles,
                    activeProfileId = activeId,
                    autoFailover = auto,
                    message = importMessage,
                    wdttInstalled = app.wdtt.availability().installed,
                    onConnect = { profile -> requestConnect(profile) },
                    onDisconnect = { app.connectionManager.disconnect() },
                    onReconnect = { app.connectionManager.reconnect() },
                    onToggleAuto = { app.connectionManager.setAutoFailover(it) },
                    onDelete = { profile ->
                        app.repository.delete(profile.id)
                        profiles = app.repository.all()
                        activeId = app.repository.activeProfile()?.id
                    },
                    onImportSample = {
                        importMessage = "Импорт доступен по ссылке из Telegram: myvpn://import?token=…"
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestConnect(profile: VpnProfile) {
        importMessage = null
        val consent = app.connectionManager.vpnPermissionIntent()
        if (consent != null) {
            pendingProfileId = profile.id
            vpnPermissionLauncher.launch(consent)
        } else {
            app.connectionManager.connect(profile.id)
        }
    }

    private fun handleIntent(intent: Intent?) {
        // DEBUG-ONLY test hook: lets automated tests push a config through `am start`.
        // Compiled out of release builds because BuildConfig.DEBUG is a compile-time const.
        if (BuildConfig.DEBUG) {
            if (intent?.getBooleanExtra("debug_wdtt_profile", false) == true) {
                val profile = VpnProfile(
                    name = "Test WDTT (резерв)",
                    protocol = Protocol.WDTT_PLUS,
                    isFailoverTarget = true,
                )
                app.repository.save(profile, null)
                importMessage = "Резервный профиль WDTT добавлен"
                Diagnostics.event(Event.PROFILE_IMPORTED, "debug" to true, "protocol" to "WDTT")
                return
            }
            val debugConfig = intent?.getStringExtra("debug_wg_config")
            if (debugConfig != null) {
                // Accept either raw text or "b64:<base64>" so multi-line configs survive
                // the adb shell argument boundary, which splits on newlines.
                val decoded = if (debugConfig.startsWith("b64:")) {
                    runCatching {
                        String(
                            android.util.Base64.decode(
                                debugConfig.removePrefix("b64:"),
                                android.util.Base64.DEFAULT,
                            )
                        )
                    }.getOrNull()
                } else {
                    debugConfig
                }
                if (decoded != null) {
                    importDebugConfig(decoded)
                    return
                }
            }
        }

        when (val result = DeepLinkHandler.parse(intent)) {
            is DeepLinkHandler.Result.ImportRequest -> {
                // Token only. Fetching the config requires a server; without one the UI
                // must say so instead of pretending the import succeeded.
                importMessage =
                    "Ссылка принята (${DeepLinkHandler.hint(result.token)}). " +
                        "Сервер выдачи конфигураций не настроен — импорт не выполнен."
                Diagnostics.event(Event.DEEPLINK_RECEIVED, "stage" to "awaiting_server")
            }
            is DeepLinkHandler.Result.Rejected -> importMessage = result.reason
            DeepLinkHandler.Result.NotOurs -> Unit
        }
    }

    /** Debug-only: validates and stores a config supplied via intent extra. */
    private fun importDebugConfig(raw: String) {
        when (val result = ConfigImporter.validate(raw)) {
            is ImportResult.Valid -> {
                val name = "Test ${result.protocol.shortName()}"
                val profile = VpnProfile(
                    name = name,
                    protocol = result.protocol,
                    endpointSummary = result.endpoint,
                )
                app.repository.save(profile, raw)
                app.repository.setActive(profile.id)
                importMessage = "Тестовый профиль добавлен: $name"
                Diagnostics.event(Event.PROFILE_IMPORTED, "debug" to true, "protocol" to result.protocol)
            }
            is ImportResult.Invalid -> {
                importMessage = "Конфиг отклонён: ${result.reason}"
                Diagnostics.event(Event.PROFILE_IMPORT_REJECTED, "debug" to true)
            }
        }
    }
}

/** Small helper so the UI has no dependency on the engine's protocol enum ordering. */
internal fun Protocol.shortName(): String = when (this) {
    Protocol.WIREGUARD -> "WG"
    Protocol.AMNEZIAWG -> "AWG"
    Protocol.WDTT_PLUS -> "WDTT"
}
