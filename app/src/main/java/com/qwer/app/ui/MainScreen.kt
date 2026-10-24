package com.qwer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwer.app.core.ConnectionState
import com.qwer.app.core.FailureReason
import com.qwer.app.core.TrafficSnapshot
import com.qwer.app.core.VpnProfile
import com.qwer.app.update.UpdateUiState
import kotlinx.coroutines.delay

/**
 * The single screen.
 *
 * Layout is a vertical story the user reads top-to-bottom:
 *   1. state hero  — "what is happening right now", one glance
 *   2. one action  — the only button that matters, sized to be unmissable
 *   3. context     — auto-failover, channel health
 *   4. profiles    — what you can connect to
 *
 * Design rules enforced here:
 *  - No state is invented: every label comes from [ConnectionState].
 *  - Failures always carry a reason plus a next action, never a bare spinner.
 *  - Touch targets are >= 48dp; the connect button is 64dp tall.
 */
@Composable
fun MainScreen(
    state: ConnectionState,
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    autoFailover: Boolean,
    traffic: TrafficSnapshot,
    message: String?,
    wdttInstalled: Boolean,
    updateState: UpdateUiState,
    appVersion: String,
    onConnect: (VpnProfile) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onMakeFailover: (VpnProfile) -> Unit,
    onOpenImport: () -> Unit,
    onPickFile: () -> Unit,
    onOpenLogs: () -> Unit,
    onDismissMessage: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
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
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        TopBar(
            appVersion = appVersion,
            onCheckUpdates = onCheckUpdates,
            onOpenImport = onOpenImport,
            onPickFile = onPickFile,
            onOpenLogs = onOpenLogs,
        )

        Spacer(Modifier.height(20.dp))

        UpdateBanner(
            updateState = updateState,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onDismiss = onDismissUpdate,
        )

        Spacer(Modifier.height(12.dp))

        StateHero(state)

        // Traffic block: only while a session exists, so the screen never shows a
        // permanent "0 B" that would look like a broken tunnel.
        if (state is ConnectionState.Connected || state is ConnectionState.Degraded) {
            Spacer(Modifier.height(12.dp))
            TrafficCard(traffic)
        }

        Spacer(Modifier.height(24.dp))

        ConnectButton(
            state = state,
            hasProfile = profiles.isNotEmpty(),
            onConnect = {
                val target = profiles.firstOrNull { it.id == activeProfileId }
                    ?: profiles.firstOrNull()
                if (target != null) onConnect(target)
            },
            onDisconnect = onDisconnect,
            onRetry = { if (profiles.isEmpty()) onOpenImport() else onReconnect() },
        )

        AnimatedVisibility(visible = message != null) {
            Column {
                Spacer(Modifier.height(14.dp))
                NoticeCard(
                    text = message.orEmpty(),
                    onDismiss = onDismissMessage,
                )
            }
        }

        Spacer(Modifier.height(26.dp))

        AutoFailoverCard(
            enabled = autoFailover,
            wdttInstalled = wdttInstalled,
            onToggle = onToggleAuto,
        )

        Spacer(Modifier.height(24.dp))

        ProfilesSection(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onConnect = onConnect,
            onDelete = onDelete,
            onMakeFailover = onMakeFailover,
            onAdd = onOpenImport,
            onPickFile = onPickFile,
        )

        Spacer(Modifier.height(32.dp))
    }

    if (showImportDialog) {
        ImportDialog(
            text = importText,
            error = importError,
            onTextChange = onImportTextChange,
            onDismiss = onDismissImport,
            onConfirm = onConfirmImport,
            onPickFile = onPickFile,
        )
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(
    appVersion: String,
    onCheckUpdates: () -> Unit,
    onOpenImport: () -> Unit,
    onPickFile: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "qwer",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "VPN · $appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Меню" },
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Добавить вставкой") },
                    leadingIcon = { Icon(Icons.Filled.Description, null) },
                    onClick = { menuOpen = false; onOpenImport() },
                )
                DropdownMenuItem(
                    text = { Text("Импорт из файла") },
                    leadingIcon = { Icon(Icons.Filled.Description, null) },
                    onClick = { menuOpen = false; onPickFile() },
                )
                DropdownMenuItem(
                    text = { Text("Проверить обновления") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = { menuOpen = false; onCheckUpdates() },
                )
                DropdownMenuItem(
                    text = { Text("Логи") },
                    leadingIcon = { Icon(Icons.Filled.Description, null) },
                    onClick = { menuOpen = false; onOpenLogs() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- update banner

@Composable
private fun UpdateBanner(
    updateState: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (updateState) {
        is UpdateUiState.Available -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Доступно обновление ${updateState.info.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (updateState.info.sizeBytes > 0) {
                            Text(
                                "${updateState.info.sizeBytes / 1024 / 1024} МБ · скачать вручную",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                        }
                    }
                    TextButton(onClick = onDownload) { Text("Скачать") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Delete, contentDescription = "Скрыть", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        is UpdateUiState.Downloading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Загрузка обновления… ${updateState.percent}%", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { updateState.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is UpdateUiState.ReadyToInstall -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Обновление скачано",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Button(onClick = onInstall) { Text("Установить") }
                }
            }
        }
        is UpdateUiState.Failed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = updateState.message,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        else -> Unit
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun StateHero(state: ConnectionState) {
    val (label, accent) = statusLabel(state)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "alpha",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.10f),
                            Color.Transparent,
                        )
                    )
                )
                .padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .alpha(if (state.isBusy) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.13f)),
                )
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(38.dp),
                        color = accent,
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
                        tint = accent,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
                textAlign = TextAlign.Center,
            )

            val subtitle = stateSubtitle(state)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- action

@Composable
private fun ConnectButton(
    state: ConnectionState,
    hasProfile: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
) {
    val connected = state is ConnectionState.Connected
    val busy = state.isBusy
    val failed = state is ConnectionState.Failed

    val label = when {
        connected -> "Отключить"
        state is ConnectionState.Connecting -> "Подключение…"
        state is ConnectionState.Verifying -> "Проверка связи…"
        state is ConnectionState.Switching -> "Переключение…"
        state is ConnectionState.Degraded -> "Проверить снова"
        failed -> "Повторить"
        !hasProfile -> "Добавить конфигурацию"
        else -> "Подключить"
    }

    Button(
        onClick = {
            when {
                connected -> onDisconnect()
                busy -> Unit
                failed || state is ConnectionState.Degraded -> if (hasProfile) onRetry() else onConnect()
                !hasProfile -> onRetry()
                else -> onConnect()
            }
        },
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (connected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- notice

@Composable
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Delete, contentDescription = "Закрыть", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- failover

@Composable
private fun AutoFailoverCard(
    enabled: Boolean,
    wdttInstalled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Автопереключение", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "При долгой потере связи — WDTT Plus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!wdttInstalled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "WDTT Plus не установлен — переключение будет недоступно",
                    style = MaterialTheme.typography.labelSmall,
                    color = StateColors.degraded,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- profiles

@Composable
private fun ProfilesSection(
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    onConnect: (VpnProfile) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onMakeFailover: (VpnProfile) -> Unit,
    onAdd: () -> Unit,
    onPickFile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Конфигурации",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row {
                TextButton(onClick = onPickFile) { Text("Из файла") }
                TextButton(onClick = onAdd) { Text("Вставить") }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (profiles.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Пока пусто",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Добавьте конфиг WireGuard или AmneziaWG: вставьте текстом или " +
                            "выберите файл .conf.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onClick = { onConnect(profile) },
                    onDelete = { onDelete(profile) },
                    onMakeFailover = { onMakeFailover(profile) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: VpnProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMakeFailover: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
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
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(protocolAccent(profile.protocol).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.protocol.shortName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = protocolAccent(profile.protocol),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        profile.endpointSummary?.let { append(it) }
                        if (profile.isFailoverTarget) {
                            if (isNotEmpty()) append(" · ")
                            append("резерв")
                        }
                        if (isEmpty()) append(profile.protocol.displayName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Only WDTT can be a failover target (failoverTarget() filters on that
            // protocol), so the star is offered only where it can actually take effect.
            if (profile.protocol == com.qwer.app.core.Protocol.WDTT_PLUS) {
                IconButton(onClick = onMakeFailover) {
                    Icon(
                        imageVector = if (profile.isFailoverTarget) {
                            Icons.Filled.Star
                        } else {
                            Icons.Outlined.StarBorder
                        },
                        contentDescription = if (profile.isFailoverTarget) {
                            "Снять с резерва"
                        } else {
                            "Сделать резервным"
                        },
                        modifier = Modifier.size(20.dp),
                        tint = if (profile.isFailoverTarget) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Удалить ${profile.name}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- import dialog

@Composable
private fun ImportDialog(
    text: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onPickFile: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить конфигурацию") },
        text = {
            Column {
                Text(
                    "Вставьте конфиг WireGuard или AmneziaWG целиком — начиная с [Interface].",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 260.dp),
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
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onPickFile) {
                    Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать файл")
                }
                Text(
                    "Ключи остаются только на этом устройстве.",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------------------------------------------------------- helpers

private fun statusLabel(state: ConnectionState): Pair<String, Color> = when (state) {
    ConnectionState.Disconnected -> "Отключено" to StateColors.idle
    ConnectionState.Connecting -> "Подключение…" to StateColors.connecting
    ConnectionState.Verifying -> "Проверка связи…" to StateColors.connecting
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
        val elapsed = rememberElapsed(state.connectedAtMillis)
        "${state.protocol.displayName} · $elapsed"
    }
    is ConnectionState.Degraded -> "Проверяем: ${state.failedProbes} неудачных проб"
    is ConnectionState.Switching -> "${state.from.displayName} → ${state.to.displayName}"
    is ConnectionState.Failed -> state.detail
    else -> null
}

private fun protocolAccent(protocol: com.qwer.app.core.Protocol): Color = when (protocol) {
    com.qwer.app.core.Protocol.WIREGUARD -> Color(0xFF3D5AFE)
    com.qwer.app.core.Protocol.AMNEZIAWG -> Color(0xFF00897B)
    com.qwer.app.core.Protocol.WDTT_PLUS -> Color(0xFF7B4DFF)
}

/** Local elapsed-time ticker; cancelled automatically when the composable leaves. */
@Composable
private fun rememberElapsed(sinceMillis: Long): String {
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

// ---------------------------------------------------------------- traffic card

/**
 * Session block: protocol, live in/out counters and the duration of the current
 * connection.
 *
 * Design decisions:
 *  - [TrafficSnapshot.Unavailable] renders an explicit "нет данных" instead of zeros.
 *    Showing 0 B would imply a working-but-idle tunnel, which may be false.
 *  - Units are binary and formatted by [TrafficSnapshot.Known.formatBytes] so the
 *    number and its label can never disagree.
 *  - The duration comes from the session start recorded at connect time, not from the
 *    composable's lifetime.
 */
@Composable
private fun TrafficCard(traffic: TrafficSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (traffic) {
                TrafficSnapshot.Unavailable -> {
                    Text("Статистика", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Нет данных от движка",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is TrafficSnapshot.Known -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            traffic.protocol.displayName,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            traffic.formatDuration(
                                traffic.sessionDurationMillis(System.currentTimeMillis())
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TrafficTile(
                            label = "Входящий",
                            value = traffic.formatBytes(traffic.rxBytes),
                            modifier = Modifier.weight(1f),
                        )
                        TrafficTile(
                            label = "Исходящий",
                            value = traffic.formatBytes(traffic.txBytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
