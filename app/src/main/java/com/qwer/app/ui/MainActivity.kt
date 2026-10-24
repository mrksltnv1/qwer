package com.qwer.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.qwer.app.BuildConfig
import com.qwer.app.backup.BackupManager
import com.qwer.app.QwerApplication
import com.qwer.app.config.ConfigImporter
import com.qwer.app.config.FileConfigReader
import com.qwer.app.config.ImportResult
import com.qwer.app.core.Protocol
import com.qwer.app.core.AppRouting
import com.qwer.app.core.VpnProfile
import com.qwer.app.deeplink.DeepLinkHandler
import com.qwer.app.device.DiagnosticEngine
import com.qwer.app.device.InstalledApps
import com.qwer.app.device.DiagnosticReport
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.update.UpdateChecker
import com.qwer.app.update.UpdateInstaller
import com.qwer.app.update.UpdateResult
import com.qwer.app.update.UpdateUiState
import com.qwer.app.wdtt.WdttLink
import com.qwer.app.wdtt.WdttParseResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    private var tab by mutableStateOf(AppTab.HOME)
    private var importText by mutableStateOf("")
    private var importError by mutableStateOf<String?>(null)

    private var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)

    // Diagnostics state lives here, not in the composable: a rotation must not throw
    // away a finished report and re-run DNS probes.
    private var diagnosticReport by mutableStateOf<DiagnosticReport?>(null)
    private var diagnosticsRunning by mutableStateOf(false)
    private lateinit var diagnosticEngine: DiagnosticEngine
    private lateinit var backupManager: BackupManager
    private var backupStatus by mutableStateOf<String?>(null)
    private var backupPreview by mutableStateOf<BackupManager.PreviewResult?>(null)
    private var profilesState by mutableStateOf<List<VpnProfile>>(emptyList())
    private var activeIdState by mutableStateOf<String?>(null)

    // Routing picker: which profile is being edited, and the launchable-app list.
    // The app list is loaded once per open, not per recomposition.
    private var routingProfile by mutableStateOf<VpnProfile?>(null)
    private var installedApps by mutableStateOf<List<InstalledApps.Entry>>(emptyList())

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

    /**
     * Android 13+ requires an explicit runtime grant for notifications. Without it the
     * foreground service still runs (the tunnel stays alive) but its notification is
     * silently dropped — which is exactly why the shade showed nothing while the app
     * claimed to be connected.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Diagnostics.event(Event.FOREGROUND_SERVICE, "notif_permission" to granted)
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

        diagnosticEngine = DiagnosticEngine(this)
        backupManager = BackupManager(app.repository)
        updateChecker = UpdateChecker(currentVersionCode = BuildConfig.VERSION_CODE)
        updateInstaller = UpdateInstaller(this)
        refreshProfiles()

        handleIntent(intent)
        ensureNotificationPermission()

        setContent {
            val state by app.connectionManager.state.collectAsStateWithLifecycle()
            val traffic by app.connectionManager.trafficState.collectAsStateWithLifecycle()
            val auto by app.connectionManager.autoFailoverEnabled.collectAsStateWithLifecycle()

            QwerTheme {
                Scaffold(
                    bottomBar = {
                        AppBottomBar(
                            selected = tab,
                            onSelect = { next ->
                                if (next == AppTab.ROUTING) {
                                    // Entering the picker: choose the profile it edits and
                                    // load launchable apps once. Doing it here (not in the
                                    // composable) keeps the PackageManager query off the
                                    // recomposition path.
                                    routingProfile = activeIdState?.let { app.repository.find(it) }
                                        ?: profilesState.firstOrNull()
                                    installedApps = InstalledApps.launchable(this@MainActivity)
                                }
                                tab = next
                            },
                        )
                    },
                ) { innerPadding ->
                Box(Modifier.padding(innerPadding)) {
                when (tab) {
                    AppTab.LOGS -> LogsScreen(
                        onClose = { tab = AppTab.HOME },
                        onClear = { Diagnostics.clear() },
                    )
                    AppTab.DIAGNOSTICS -> DiagnosticsScreen(
                        report = diagnosticReport,
                        isRunning = diagnosticsRunning,
                        onRun = { runDiagnostics() },
                        onClose = { tab = AppTab.HOME },
                    )
                    AppTab.QR -> QrScreen(
                        profiles = profilesState,
                        configForProfile = { id -> app.repository.configFor(id) },
                        onImport = { name, config ->
                            // The QR carries the original profile name; keeping it is the
                            // whole point of moving a profile between devices.
                            val error = saveProfileFromText(config, namePrefix = null, exactName = name)
                            if (error == null) {
                                refreshProfiles()
                            } else {
                                message = error
                            }
                            tab = AppTab.HOME
                        },
                        onClose = { tab = AppTab.HOME },
                    )
                    AppTab.ROUTING -> {
                        val target = routingProfile
                        if (target == null) {
                            AppRoutingScreen(
                                apps = emptyList(),
                                initial = AppRouting.All,
                                onApply = {},
                                onClose = { tab = AppTab.HOME },
                            )
                        } else {
                            AppRoutingScreen(
                                apps = installedApps,
                                initial = target.appRouting,
                                onApply = { routing ->
                                    app.repository.save(target.copy(appRouting = routing), null)
                                    refreshProfiles()
                                    routingProfile = null
                                    message = if (routing is AppRouting.All) {
                                        "Весь трафик через VPN"
                                    } else {
                                        "Приложений в правиле: ${AppRouting.selectedCount(routing)}"
                                    }
                                    tab = AppTab.HOME
                                },
                                onClose = {
                                    routingProfile = null
                                    tab = AppTab.HOME
                                },
                            )
                        }
                    }
                    AppTab.BACKUP -> BackupScreen(
                        onClose = { tab = AppTab.HOME },
                        onExport = { pw, uri -> exportBackup(pw, uri) },
                        onPreview = { uri, pw -> previewBackup(uri, pw) },
                        onImport = { uri, pw, mode -> importBackup(uri, pw, mode) },
                        statusMessage = backupStatus,
                        preview = backupPreview,
                    )
                    AppTab.HOME -> MainScreen(
                    state = state,
                    profiles = profilesState,
                    activeProfileId = activeIdState,
                    autoFailover = auto,
                    traffic = traffic,
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
                    onMakeFailover = { profile ->
                        // Toggle: tapping the star on the current target clears it.
                        val newTarget = if (profile.isFailoverTarget) null else profile.id
                        app.repository.setFailoverTarget(newTarget)
                        refreshProfiles()
                        message = if (newTarget != null) {
                            "Резервный профиль: ${profile.name}"
                        } else {
                            "Резервный профиль снят"
                        }
                    },
                    onOpenLogs = { tab = AppTab.LOGS },
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
                }
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

    /**
     * Runs the device diagnostic off the main thread and publishes the result.
     *
     * Guarded by [diagnosticsRunning] so a double tap cannot start two runs; failures
     * are contained inside DiagnosticEngine, so this coroutine cannot crash the UI.
     */
    /**
     * Writes the encrypted backup to the URI the user picked.
     *
     * The password char array is zeroed after use; it is never held in a String field,
     * never logged and never placed in saved state.
     */
    private fun exportBackup(password: CharArray, uri: android.net.Uri) {
        lifecycleScope.launch {
            backupStatus = null
            try {
                val result = withContext(Dispatchers.IO) { backupManager.export(password) }
                when (result) {
                    is BackupManager.ExportResult.Failed -> backupStatus = result.message
                    is BackupManager.ExportResult.Ok -> {
                        val written = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openOutputStream(uri)?.use { it.write(result.bytes) }
                                    ?: error("no stream")
                            }.isSuccess
                        }
                        backupStatus = if (written) {
                            "Резервная копия создана: ${result.profileCount} профилей"
                        } else {
                            "Не удалось записать файл"
                        }
                    }
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Decrypts and previews without touching stored profiles. */
    private fun previewBackup(uri: android.net.Uri, password: CharArray) {
        lifecycleScope.launch {
            backupPreview = null
            backupStatus = null
            try {
                val bytes = withContext(Dispatchers.IO) { readAll(uri) }
                if (bytes == null) {
                    backupStatus = "Не удалось прочитать файл"
                    return@launch
                }
                backupPreview = withContext(Dispatchers.IO) { backupManager.preview(bytes, password) }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun importBackup(
        uri: android.net.Uri,
        password: CharArray,
        mode: BackupManager.ImportMode,
    ) {
        lifecycleScope.launch {
            backupStatus = null
            try {
                val bytes = withContext(Dispatchers.IO) { readAll(uri) }
                if (bytes == null) {
                    backupStatus = "Не удалось прочитать файл"
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { backupManager.import(bytes, password, mode) }
                backupPreview = null
                backupStatus = when (result) {
                    is BackupManager.ImportResult.Failed -> result.message
                    is BackupManager.ImportResult.Ok -> buildString {
                        append("Восстановлено: добавлено ${result.added}")
                        if (result.replaced > 0) append(", заменено ${result.replaced}")
                        if (result.skipped > 0) append(", пропущено ${result.skipped}")
                    }
                }
                refreshProfiles()
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Bounded read: a hostile file must not be pulled into memory whole. */
    private fun readAll(uri: android.net.Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(com.qwer.app.backup.BackupCodec.MAX_FILE_BYTES + 1)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            if (total > com.qwer.app.backup.BackupCodec.MAX_FILE_BYTES) null else buf.copyOf(total)
        }
    }.getOrNull()

    private fun runDiagnostics() {
        if (diagnosticsRunning) return
        diagnosticsRunning = true
        lifecycleScope.launch {
            try {
                diagnosticReport = diagnosticEngine.run()
            } finally {
                diagnosticsRunning = false
            }
        }
    }

    private fun requestConnect(profile: VpnProfile) {
        message = null
        ensureNotificationPermission()
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
    private fun saveProfileFromText(raw: String, namePrefix: String?, exactName: String? = null): String? {
        if (raw.trim().startsWith("wdtt://", ignoreCase = true)) {
            return when (val parsed = WdttLink.parse(raw)) {
                is WdttParseResult.Valid -> {
                    val profile = VpnProfile(
                        name = exactName ?: ((namePrefix ?: "") + "WDTT · ${parsed.link.safeLabel()}"),
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
                    name = exactName ?: ((namePrefix ?: "") + baseName),
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
            // Debug-only: import a REAL wdtt:// link (needed to test the WG->WDTT
            // failover path end to end; the stub profile below has no link).
            intent?.getStringExtra("debug_wdtt_link")?.let { raw ->
                // adb shell splits arguments on '&', which shreds a wdtt:// query
                // string. Accept b64: to survive the shell boundary.
                val link = if (raw.startsWith("b64:")) {
                    runCatching {
                        String(android.util.Base64.decode(raw.removePrefix("b64:"), android.util.Base64.DEFAULT))
                    }.getOrNull()
                } else raw
                if (link == null) {
                    message = "WDTT-ссылка повреждена"
                    return
                }
                val err = saveProfileFromText(link, namePrefix = "Test ")
                if (err == null) {
                    val p = app.repository.all().firstOrNull { it.protocol == Protocol.WDTT_PLUS }
                    if (p != null) app.repository.setFailoverTarget(p.id)
                    refreshProfiles()
                    message = "WDTT-профиль добавлен как резервный"
                } else {
                    message = "WDTT-ссылка отклонена: $err"
                }
                return
            }
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
                    // Debug-only: connect without touching the UI. Needed because the
                    // BlissOS test VM shows system overlays (home-app resolver, welcome
                    // screen) that swallow synthetic taps, which made UI-driven tests
                    // report a false "tunnel never started".
                    if (intent?.getBooleanExtra("debug_auto_connect", false) == true) {
                        val id = app.repository.activeProfile()?.id
                        if (id != null) {
                            Diagnostics.event(Event.VPN_PERMISSION, "debug_auto_connect" to true)
                            requestConnect(app.repository.find(id)!!)
                        }
                    }
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
