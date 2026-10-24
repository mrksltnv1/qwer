package com.qwer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.LogGrouper
import kotlinx.coroutines.delay

/**
 * The log half of the diagnostics screen.
 *
 * Kept as a pane (not a whole screen) because logs and device checks answer the same
 * question — "what is wrong?" — and switching between two bottom tabs to correlate them
 * was the main complaint about the old layout.
 *
 * Font size is 13sp with a 17sp line height: the previous 11sp monospace was unreadable
 * on a high-density panel and could not be scaled by the system font setting.
 */
@Composable
fun LogsPane(
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    var lines by remember { mutableStateOf(Diagnostics.snapshot()) }
    var groupRepeats by remember { mutableStateOf(true) }

    val groups = remember(lines, groupRepeats) {
        if (groupRepeats) {
            LogGrouper.group(lines)
        } else {
            lines.map {
                LogGrouper.Group(1, LogGrouper.timestampOf(it), LogGrouper.timestampOf(it), it)
            }
        }
    }

    // Live tail: re-read once a second while the pane is visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            lines = Diagnostics.snapshot()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            Text(
                "Группировать",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = groupRepeats,
                onCheckedChange = { groupRepeats = it },
            )
            IconButton(onClick = {
                onCopy(Diagnostics.exportText("qwer — диагностика"))
            }) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Копировать логи",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                Diagnostics.clear()
                lines = Diagnostics.snapshot()
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Очистить логи",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пока пусто", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Записи появятся, когда вы импортируете профиль или подключитесь.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
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
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(groups) { group ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = group.line,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    // Explicit, because the monospace fallback renders
                                    // smaller than the default face at the same size.
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                ),
                                color = if (group.line.contains("!")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (group.isRepeated) {
                                Spacer(Modifier.width(6.dp))
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
    }
}
