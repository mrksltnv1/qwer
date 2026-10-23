package com.qwer.app.ui

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
import com.qwer.app.BuildConfig
import com.qwer.app.QwerApplication
import com.qwer.app.config.ConfigImporter
import com.qwer.app.config.ImportResult
import com.qwer.app.core.ConnectionState
import com.qwer.app.core.Protocol
import com.qwer.app.core.VpnProfile
import com.qwer.app.deeplink.DeepLinkHandler
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event

/**
 * Single screen. Deliberately holds no tunnel logic: it renders [ConnectionState] and
 * forwards user intents to [com.qwer.app.core.ConnectionManager].
 */
class MainActivity : ComponentActivity() {

    private val app: QwerApplication
        get() = application as QwerApplication

    private var pendingProfileId by mutableStateOf<String?>(null)
    private var importMessage by mutableStateOf<String?>(null)

    // Manual import dialog: the path that works right now, without a server or bot.
    private var showImportDialog by mutableStateOf(false)
    private var importText by mutableStateOf("")
    private var importError by mutableStateOf<String?>(null)

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

            QwerTheme {
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
                    onOpenImport = {
                        importText = ""
                        importError = null
                        showImportDialog = true
                    },
                    showImportDialog = showImportDialog,
                    importText = importText,
                    importError = importError,
                    onImportTextChange = {
                        importText = it
                        importError = null
                    },
                    onDismissImport = { showImportDialog = false },
                    onConfirmImport = {
                        val outcome = saveProfileFromText(importText, namePrefix = null)
                        if (outcome == null) {
                            showImportDialog = false
                            importText = ""
                            profiles = app.repository.all()
                            activeId = app.repository.activeProfile()?.id
                        } else {
                            importError = outcome
                        }
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
        // DEBUG-ONLY test hooks: let automated tests push a config through `am start`.
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
                    saveProfileFromText(decoded, namePrefix = "Test ")
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

    /**
     * Validates raw config text and stores it.
     *
     * @return null on success, or a user-facing error message to show inline.
     */
    private fun saveProfileFromText(raw: String, namePrefix: String?): String? =
        when (val result = ConfigImporter.validate(raw)) {
            is ImportResult.Valid -> {
                val baseName = "${result.protocol.shortName()} · ${result.endpoint ?: "профиль"}"
                val profile = VpnProfile(
                    name = (namePrefix ?: "") + baseName,
                    protocol = result.protocol,
                    endpointSummary = result.endpoint,
                )
                app.repository.save(profile, raw)
                app.repository.setActive(profile.id)
                importMessage = "Профиль добавлен: ${profile.name}"
                Diagnostics.event(
                    Event.PROFILE_IMPORTED,
                    "protocol" to result.protocol,
                    "peers" to result.peerCount,
                )
                null
            }
            is ImportResult.Invalid -> {
                Diagnostics.event(Event.PROFILE_IMPORT_REJECTED, "stage" to "validate")
                result.reason
            }
        }
}

/** Small helper so the UI has no dependency on the engine's protocol enum ordering. */
internal fun Protocol.shortName(): String = when (this) {
    Protocol.WIREGUARD -> "WG"
    Protocol.AMNEZIAWG -> "AWG"
    Protocol.WDTT_PLUS -> "WDTT"
}
