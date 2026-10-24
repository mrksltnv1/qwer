package com.qwer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.LogGrouper
import kotlinx.coroutines.delay

/**
 * In-app log viewer.
 *
 * Exists so a user can copy what happened without adb, a PC, or a support ticket.
 * The buffer is in memory only (see [Diagnostics]) and is bounded, so this screen
 * never shows unbounded history and nothing is persisted to disk.
 *
 * Everything here is already scrubbed by [Diagnostics.scrub] before it reaches the
 * buffer: keys, tokens and `password=` fields never appear on screen or in the
 * clipboard.
 */
@Composable
fun LogsScreen(
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var lines by remember { mutableStateOf(Diagnostics.snapshot()) }
    var copied by remember { mutableStateOf(false) }
    // Grouping is on by default because the field log showed runs of identical events
    // pushing real transitions off-screen. The toggle keeps the raw order available.
    var groupRepeats by remember { mutableStateOf(true) }
    val groups = remember(lines, groupRepeats) {
        if (groupRepeats) LogGrouper.group(lines) else lines.map {
            LogGrouper.Group(1, LogGrouper.timestampOf(it), LogGrouper.timestampOf(it), it)
        }
    }
    // Re-read on a timer so the view stays live while a connection is being attempted.
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        delay(1000)
        lines = Diagnostics.snapshot()
        tick++
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Логи", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (groupRepeats && groups.size != lines.size) {
                            "${lines.size} записей · ${groups.size} строк"
                        } else {
                            "${lines.size} записей · только в памяти"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                Diagnostics.exportText(
                                    "qwer — диагностика",
                                )
                            )
                        )
                        copied = true
                    }
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Копировать логи",
                        tint = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        onClear()
                        lines = Diagnostics.snapshot()
                    }
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Очистить логи",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(Modifier.height(10.dp))

            if (copied) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        "Скопировано в буфер обмена",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LaunchedEffect(copied) {
                    delay(2000)
                    copied = false
                }
            }

            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Пока пусто",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Записи появятся, когда вы импортируете профиль или подключитесь.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // Auto-scroll to the newest group, like a terminal.
                LaunchedEffect(groups.size) {
                    if (groups.isNotEmpty()) listState.animateScrollToItem(groups.size - 1)
                }
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    ) {
                        items(groups) { group ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = group.line,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    ),
                                    color = if (group.line.contains("!")) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (group.isRepeated) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "×${group.count}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (group.isRepeated && group.firstTimestamp != null &&
                                group.lastTimestamp != group.firstTimestamp
                            ) {
                                Text(
                                    text = "  ${group.firstTimestamp} … ${group.lastTimestamp}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Назад") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(Diagnostics.exportText("qwer — диагностика"))
                        )
                        copied = true
                    }
                ) {
                    Text("Копировать всё")
                }
            }
        }
    }
}
