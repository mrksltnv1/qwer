package com.myvpn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myvpn.app.core.ConnectionState
import com.myvpn.app.core.FailureReason
import com.myvpn.app.core.VpnProfile
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * The single screen: one big action, one honest status, a compact profile list.
 *
 * Design rules applied:
 *  - The status text is derived from [ConnectionState]; "Подключено" is impossible
 *    without a proven probe (the manager enforces that).
 *  - Every failure shows a reason plus a retry action — never an endless spinner.
 *  - The switch for auto-failover explains what it does without jargon.
 */
@Composable
fun MainScreen(
    state: ConnectionState,
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    autoFailover: Boolean,
    message: String?,
    wdttInstalled: Boolean,
    onConnect: (VpnProfile) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onOpenImport: () -> Unit,
    showImportDialog: Boolean,
    importText: String,
    importError: String?,
    onImportTextChange: (String) -> Unit,
    onDismissImport: () -> Unit,
    onConfirmImport: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "MyVPN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(28.dp))

        StatusBadge(state)

        Spacer(Modifier.height(20.dp))

        PrimaryAction(
            state = state,
            activeProfile = profiles.firstOrNull { it.id == activeProfileId },
            profiles = profiles,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onReconnect = onReconnect,
        )

        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Автопереключение", fontWeight = FontWeight.Medium)
                        Text(
                            text = "Если связь пропадёт надолго — открыть WDTT Plus",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoFailover, onCheckedChange = onToggleAuto)
                }
                if (!wdttInstalled) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "WDTT Plus не установлен — автопереключение недоступно",
                        style = MaterialTheme.typography.bodySmall,
                        color = StateColors.degraded,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        ProfileSection(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onConnect = onConnect,
            onDelete = onDelete,
            onImport = onOpenImport,
        )
    }

    if (showImportDialog) {
        ImportDialog(
            text = importText,
            error = importError,
            onTextChange = onImportTextChange,
            onDismiss = onDismissImport,
            onConfirm = onConfirmImport,
        )
    }
}

/**
 * Paste-a-config dialog: the manual path for a user who already has a `.conf` file or
 * a config from a bot. No server, no deep link, no account — this is what makes the
 * app usable on a phone right now.
 */
@Composable
private fun ImportDialog(
    text: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить конфигурацию") },
        text = {
            Column {
                Text(
                    text = "Вставьте конфиг WireGuard или AmneziaWG целиком — начиная с [Interface].",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    placeholder = { Text("[Interface]\nPrivateKey = …") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = false,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Ключи остаются только на этом устройстве, в защищённом хранилище Android.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = text.isNotBlank()) {
                Text("Импортировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun StatusBadge(state: ConnectionState) {
    val (label, color) = statusLabel(state)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = color,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = if (state is ConnectionState.Connected) {
                        Icons.Filled.Lock
                    } else {
                        Icons.Filled.LockOpen
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = TextAlign.Center,
        )
        val sub = stateSubtitle(state)
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    state: ConnectionState,
    activeProfile: VpnProfile?,
    profiles: List<VpnProfile>,
    onConnect: (VpnProfile) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val connected = state is ConnectionState.Connected
    val busy = state.isBusy
    val target = activeProfile ?: profiles.firstOrNull()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {
                when {
                    connected -> onDisconnect()
                    state is ConnectionState.Failed -> onReconnect()
                    target != null -> onConnect(target)
                }
            },
            enabled = !busy && (target != null || connected || state is ConnectionState.Failed),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics {
                    contentDescription = when {
                        connected -> "Отключить VPN"
                        else -> "Подключить VPN"
                    }
                },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) StateColors.failed else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = when {
                    connected -> "Отключить"
                    state is ConnectionState.Connecting -> "Подключение…"
                    state is ConnectionState.Verifying -> "Проверка связи…"
                    state is ConnectionState.Switching -> "Переключение…"
                    state is ConnectionState.Degraded -> "Проверить снова"
                    state is ConnectionState.Failed -> "Повторить"
                    else -> "Подключить"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (target == null && profiles.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Нет конфигураций. Импортируйте ссылкой из Telegram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProfileSection(
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    onConnect: (VpnProfile) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Конфигурации", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onImport) { Text("Добавить") }
        }
        Spacer(Modifier.height(8.dp))

        if (profiles.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = "Пока пусто. Нажмите «Добавить» и вставьте конфиг WireGuard или AmneziaWG.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onConnect = { onConnect(profile) },
                    onDelete = { onDelete(profile) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: VpnProfile,
    isActive: Boolean,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Medium)
                Text(
                    text = buildString {
                        append(profile.protocol.shortName())
                        profile.endpointSummary?.let { append(" · ").append(it) }
                        if (profile.isFailoverTarget) append(" · резерв")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onConnect) { Text("Выбрать") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить профиль")
            }
        }
    }
}

private fun statusLabel(state: ConnectionState): Pair<String, Color> = when (state) {
    ConnectionState.Disconnected -> "Отключено" to StateColors.idle
    ConnectionState.Connecting -> "Подключение…" to StateColors.connecting
    ConnectionState.Verifying -> "Проверка соединения…" to StateColors.connecting
    is ConnectionState.Connected -> "Подключено" to StateColors.connected
    is ConnectionState.Degraded -> "Связь нестабильна" to StateColors.degraded
    is ConnectionState.Switching -> "Переключение…" to StateColors.connecting
    is ConnectionState.Failed -> failureTitle(state.reason) to StateColors.failed
}

private fun failureTitle(reason: FailureReason): String = when (reason) {
    FailureReason.NO_PROFILE -> "Нет профиля"
    FailureReason.VPN_PERMISSION_DENIED -> "Нет разрешения VPN"
    FailureReason.ENGINE_ERROR -> "Ошибка движка"
    FailureReason.NO_NETWORK -> "Нет сети"
    FailureReason.PROBE_FAILED -> "Нет доступа в сеть"
    FailureReason.WDTT_UNAVAILABLE -> "WDTT недоступен"
    FailureReason.WDTT_HANDOVER_REQUIRED -> "Нужен WDTT Plus"
    FailureReason.IMPORT_FAILED -> "Ошибка импорта"
    FailureReason.COOLDOWN_ACTIVE -> "Подождите"
}

@Composable
private fun stateSubtitle(state: ConnectionState): String? = when (state) {
    is ConnectionState.Connected -> {
        val elapsed = rememberElapsedSeconds(state.connectedAtMillis)
        "${state.protocol.displayName} · $elapsed"
    }
    is ConnectionState.Degraded -> "Проверяем: ${state.failedProbes} неудачных проб"
    is ConnectionState.Switching -> "${state.from.displayName} → ${state.to.displayName}"
    is ConnectionState.Failed -> state.detail
    else -> null
}

/** Local elapsed-time ticker; cancelled automatically when the composable leaves. */
@Composable
private fun rememberElapsedSeconds(sinceMillis: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val total = ((now - sinceMillis) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
