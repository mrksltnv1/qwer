package com.qwer.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.qwer.app.BuildConfig
import com.qwer.app.QwerApplication
import com.qwer.app.config.ConfigImporter
import com.qwer.app.config.FileConfigReader
import com.qwer.app.config.ImportResult
import com.qwer.app.core.Protocol
import com.qwer.app.core.VpnProfile
import com.qwer.app.deeplink.DeepLinkHandler
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.update.UpdateChecker
import com.qwer.app.update.UpdateInstaller
import com.qwer.app.update.UpdateResult
import com.qwer.app.update.UpdateUiState
import com.qwer.app.wdtt.WdttLink
import com.qwer.app.wdtt.WdttParseResult
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single screen host. Holds no tunnel logic: it renders state and forwards intents.
 */
class MainActivity : ComponentActivity() {

    private val app: QwerApplication
        get() = application as QwerApplication

    private var pendingProfileId by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)

    private var showImportDialog by mutableStateOf(false)
    private var importText by mutableStateOf("")
    private var importError by mutableStateOf<String?>(null)

    private var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
    private var profilesState by mutableStateOf<List<VpnProfile>>(emptyList())
    private var activeIdState by mutableStateOf<String?>(null)

    private lateinit var updateChecker: UpdateChecker
    private lateinit var updateInstaller: UpdateInstaller

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
            message = "Без разрешения Android VPN подключение невозможно"
        }
    }

    /** SAF picker for a .conf file. */
    private val pickConfigFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            when (val res = FileConfigReader.read(this@MainActivity, uri)) {
                is FileConfigReader.Result.Ok -> {
                    val error = saveProfileFromText(res.text, namePrefix = null)
                    if (error == null) {
                        showImportDialog = false
                        importText = ""
                        refreshProfiles()
                        message = "Импортировано из файла: ${res.displayName}"
                    } else {
                        // Keep the dialog open with the error so the user can fix it.
                        importText = res.text
                        importError = error
                        showImportDialog = true
                    }
                }
                is FileConfigReader.Result.Error -> {
                    message = res.message
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateChecker = UpdateChecker(currentVersionCode = BuildConfig.VERSION_CODE)
        updateInstaller = UpdateInstaller(this)
        refreshProfiles()

        handleIntent(intent)

        setContent {
            val state by app.connectionManager.state.collectAsStateWithLifecycle()
            val auto by app.connectionManager.autoFailoverEnabled.collectAsStateWithLifecycle()

            QwerTheme {
                MainScreen(
                    state = state,
                    profiles = profilesState,
                    activeProfileId = activeIdState,
                    autoFailover = auto,
                    message = message,
                    wdttInstalled = app.wdtt.availability().installed,
                    updateState = updateState,
                    appVersion = BuildConfig.VERSION_NAME,
                    onConnect = { profile -> requestConnect(profile) },
                    onDisconnect = { app.connectionManager.disconnect() },
                    onReconnect = { app.connectionManager.reconnect() },
                    onToggleAuto = { app.connectionManager.setAutoFailover(it) },
                    onDelete = { profile ->
                        app.repository.delete(profile.id)
                        refreshProfiles()
                    },
                    onOpenImport = {
                        importText = ""
                        importError = null
                        showImportDialog = true
                    },
                    onPickFile = { pickConfigFile.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) },
                    onDismissMessage = { message = null },
                    onCheckUpdates = { checkForUpdates(manual = true) },
                    onDownloadUpdate = { downloadUpdate() },
                    onInstallUpdate = { installUpdate() },
                    onDismissUpdate = { updateState = UpdateUiState.Idle },
                    showImportDialog = showImportDialog,
                    importText = importText,
                    importError = importError,
                    onImportTextChange = {
                        importText = it
                        importError = null
                    },
                    onDismissImport = { showImportDialog = false },
                    onConfirmImport = {
                        val error = saveProfileFromText(importText, namePrefix = null)
                        if (error == null) {
                            showImportDialog = false
                            importText = ""
                            refreshProfiles()
                        } else {
                            importError = error
                        }
                    },
                )
            }
        }

        // Automatic check, once per start. No silent download: the user is asked first.
        checkForUpdates(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdates(manual: Boolean) {
        if (updateState is UpdateUiState.Downloading) return
        updateState = UpdateUiState.Checking
        lifecycleScope.launch {
            when (val result = updateChecker.check()) {
                is UpdateResult.Available -> updateState = UpdateUiState.Available(result.info)
                UpdateResult.UpToDate -> {
                    updateState = UpdateUiState.Idle
                    if (manual) message = "У вас последняя версия (${BuildConfig.VERSION_NAME})"
                }
                is UpdateResult.Failed -> {
                    updateState = UpdateUiState.Idle
                    // A failed background check must stay invisible; only a manual one reports.
                    if (manual) message = result.reason
                }
            }
        }
    }

    private fun downloadUpdate() {
        val available = updateState as? UpdateUiState.Available ?: return
        updateState = UpdateUiState.Downloading(0)
        lifecycleScope.launch {
            when (val res = updateInstaller.download(available.info) { pct ->
                updateState = UpdateUiState.Downloading(pct)
            }) {
                is UpdateInstaller.DownloadResult.Ok -> updateState = UpdateUiState.ReadyToInstall(res.file)
                is UpdateInstaller.DownloadResult.Failed -> updateState = UpdateUiState.Failed(res.message)
            }
        }
    }

    private fun installUpdate() {
        val ready = updateState as? UpdateUiState.ReadyToInstall ?: return
        when (val res = updateInstaller.install(ready.file)) {
            UpdateInstaller.InstallResult.Started -> {
                // Leave the card visible: the user may come back after cancelling.
                updateState = UpdateUiState.ReadyToInstall(ready.file)
            }
            UpdateInstaller.InstallResult.NeedUnknownSourcesPermission -> {
                // The engine rejected the launch because this app is not allowed to
                // install packages. Send the user straight to the right settings page
                // instead of leaving them with a dead button.
                val opened = updateInstaller.openUnknownSourcesSettings()
                updateState = UpdateUiState.Failed(
                    if (opened) {
                        "Разрешите qwer установку приложений в открывшихся настройках, " +
                            "затем вернитесь и нажмите «Установить» снова."
                    } else {
                        "Нужно разрешение на установку. Откройте Настройки → Безопасность → " +
                            "Установка неизвестных приложений → qwer."
                    }
                )
            }
            is UpdateInstaller.InstallResult.Failed -> {
                updateState = UpdateUiState.Failed(res.message)
            }
        }
    }

    // ---------------------------------------------------------------- profiles

    private fun refreshProfiles() {
        profilesState = app.repository.all()
        activeIdState = app.repository.activeProfile()?.id
    }

    private fun requestConnect(profile: VpnProfile) {
        message = null
        val consent = app.connectionManager.vpnPermissionIntent()
        if (consent != null) {
            pendingProfileId = profile.id
            vpnPermissionLauncher.launch(consent)
        } else {
            app.connectionManager.connect(profile.id)
        }
    }

    /**
     * Validates raw text and stores it.
     *
     * Two shapes are accepted:
     *  - a WireGuard/AmneziaWG config (starts with [Interface])
     *  - a `wdtt://connect?...` link, stored verbatim as the profile "config"; the
     *    WDTT client consumes it at connect time and publishes a WG config itself.
     *
     * @return null on success, or a user-facing error to show inline.
     */
    private fun saveProfileFromText(raw: String, namePrefix: String?): String? {
        if (raw.trim().startsWith("wdtt://", ignoreCase = true)) {
            return when (val parsed = WdttLink.parse(raw)) {
                is WdttParseResult.Valid -> {
                    val profile = VpnProfile(
                        name = (namePrefix ?: "") + "WDTT · ${parsed.link.safeLabel()}",
                        protocol = Protocol.WDTT_PLUS,
                        endpointSummary = parsed.link.safeLabel(),
                    )
                    app.repository.save(profile, raw)
                    app.repository.setActive(profile.id)
                    message = "Профиль добавлен: ${profile.name}"
                    Diagnostics.event(Event.PROFILE_IMPORTED, "protocol" to Protocol.WDTT_PLUS)
                    null
                }
                is WdttParseResult.Invalid -> {
                    Diagnostics.event(Event.PROFILE_IMPORT_REJECTED, "stage" to "wdtt_parse")
                    parsed.reason
                }
            }
        }

        return when (val result = ConfigImporter.validate(raw)) {
            is ImportResult.Valid -> {
                val baseName = "${result.protocol.shortName()} · ${result.endpoint ?: "профиль"}"
                val profile = VpnProfile(
                    name = (namePrefix ?: "") + baseName,
                    protocol = result.protocol,
                    endpointSummary = result.endpoint,
                )
                app.repository.save(profile, raw)
                app.repository.setActive(profile.id)
                message = "Профиль добавлен: ${profile.name}"
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

    // ---------------------------------------------------------------- intents

    private fun handleIntent(intent: Intent?) {
        if (BuildConfig.DEBUG) {
            if (intent?.getBooleanExtra("debug_wdtt_profile", false) == true) {
                val profile = VpnProfile(
                    name = "Test WDTT (резерв)",
                    protocol = Protocol.WDTT_PLUS,
                    isFailoverTarget = true,
                )
                app.repository.save(profile, null)
                refreshProfiles()
                message = "Резервный профиль WDTT добавлен"
                return
            }
            intent?.getStringExtra("debug_wg_config")?.let { raw ->
                val decoded = if (raw.startsWith("b64:")) {
                    runCatching {
                        String(android.util.Base64.decode(raw.removePrefix("b64:"), android.util.Base64.DEFAULT))
                    }.getOrNull()
                } else raw
                if (decoded != null) {
                    saveProfileFromText(decoded, namePrefix = "Test ")
                    refreshProfiles()
                    return
                }
            }
        }

        // A .conf opened from Telegram / Files arrives as VIEW or SEND.
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme == "content" || uri.scheme == "file") {
                        lifecycleScope.launch { importFromUri(uri) }
                        return
                    }
                }
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    if (text.contains("[Interface]", ignoreCase = true)) {
                        saveProfileFromText(text, namePrefix = null)
                        refreshProfiles()
                        return
                    }
                }
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                    lifecycleScope.launch { importFromUri(uri) }
                    return
                }
            }
        }

        when (val result = DeepLinkHandler.parse(intent)) {
            is DeepLinkHandler.Result.ImportRequest -> {
                message = "Ссылка принята (${DeepLinkHandler.hint(result.token)}). " +
                    "Сервер выдачи конфигураций не настроен — импорт не выполнен."
                Diagnostics.event(Event.DEEPLINK_RECEIVED, "stage" to "awaiting_server")
            }
            is DeepLinkHandler.Result.Rejected -> message = result.reason
            DeepLinkHandler.Result.NotOurs -> Unit
        }
    }

    private suspend fun importFromUri(uri: Uri) {
        when (val res = FileConfigReader.read(this, uri)) {
            is FileConfigReader.Result.Ok -> {
                val error = saveProfileFromText(res.text, namePrefix = null)
                if (error == null) {
                    refreshProfiles()
                    message = "Импортировано: ${res.displayName}"
                } else {
                    message = "Файл отклонён: $error"
                }
            }
            is FileConfigReader.Result.Error -> message = res.message
        }
    }
}

/** Short protocol tag used in profile names and avatar chips. */
internal fun Protocol.shortName(): String = when (this) {
    Protocol.WIREGUARD -> "WG"
    Protocol.AMNEZIAWG -> "AWG"
    Protocol.WDTT_PLUS -> "WDTT"
}
