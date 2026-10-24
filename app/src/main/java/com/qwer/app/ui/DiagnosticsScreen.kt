package com.qwer.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwer.app.device.CheckStatus
import com.qwer.app.device.DiagnosticCheck
import com.qwer.app.device.DiagnosticReport
import com.qwer.app.device.DiagnosticReportFormatter
import kotlinx.coroutines.delay

/**
 * Device diagnostics screen.
 *
 * Deliberate choices:
 *  - State lives in the Activity, so rotating the device does not lose a finished
 *    report (re-running a DNS probe on every rotation would be both slow and noisy).
 *  - Errors are listed first: a user opening this screen wants the problem, not a list.
 *  - SKIPPED is visually neutral — an absent optional engine must not look like a fault.
 *  - The copy button copies a scrubbed plain-text report suitable for a support chat.
 */
@Composable
fun DiagnosticsScreen(
    report: DiagnosticReport?,
    isRunning: Boolean,
    onRun: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Logs and checks are two views of the same question. A tab row beats a second
    // bottom-bar entry: correlating a failure with a log line no longer means leaving
    // the screen.
    var pane by remember { mutableStateOf(DiagPane.CHECKS) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Диагностика", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = report?.summaryLine()
                            ?: "Проверим, всё ли в порядке с устройством и сетью",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pane == DiagPane.CHECKS,
                    onClick = { pane = DiagPane.CHECKS },
                    label = { Text("Проверки") },
                )
                FilterChip(
                    selected = pane == DiagPane.LOGS,
                    onClick = { pane = DiagPane.LOGS },
                    label = { Text("Логи") },
                )
            }

            Spacer(Modifier.height(12.dp))

            if (pane == DiagPane.LOGS) {
                LogsPane(
                    modifier = Modifier.fillMaxSize(),
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                )
                return@Column
            }

            if (isRunning) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Выполняем проверки…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRun,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (report == null) "Запустить диагностику" else "Проверить снова")
                    }
                    if (report != null) {
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(DiagnosticReportFormatter.format(report)))
                                copied = true
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Копировать")
                        }
                    }
                }
            }

            if (copied) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        "Отчёт скопирован в буфер обмена",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                report == null && !isRunning -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Нажмите «Запустить диагностику», чтобы проверить устройство и сеть.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                report != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(report.checks.sortedBy { it.status.ordinal }) { check ->
                            CheckCard(check)
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
internal fun CheckCard(check: DiagnosticCheck) {
    val accent = statusColor(check.status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        check.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        DiagnosticReportFormatter.label(check.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                check.recommendation?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "→ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                check.technical?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.OK -> Color(0xFF2E9E6B)
    CheckStatus.WARNING -> Color(0xFFD08A00)
    CheckStatus.ERROR -> Color(0xFFC62828)
    CheckStatus.SKIPPED -> Color(0xFF7A8296)
}

/** Which half of the diagnostics screen is visible. */
private enum class DiagPane { CHECKS, LOGS }
