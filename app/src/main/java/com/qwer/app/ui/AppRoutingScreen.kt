package com.qwer.app.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qwer.app.core.AppRouting
import com.qwer.app.core.QuickExclusions
import com.qwer.app.device.InstalledApps

/**
 * Per-app routing picker.
 *
 * The three modes map 1:1 onto what Android can actually do — see [AppRouting]. In
 * particular there is no BLOCK mode: a VpnService can route an app through the tunnel or
 * leave it out, and nothing else. Offering "block" would be a promise the platform
 * cannot keep.
 *
 * The list is searchable because a real phone has 60-150 launchable apps and scrolling
 * to find one is worse than typing three letters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoutingScreen(
    apps: List<InstalledApps.Entry>,
    initial: AppRouting,
    onApply: (AppRouting) -> Unit,
    onClose: () -> Unit,
) {
    var mode by remember {
        mutableStateOf(
            when (initial) {
                is AppRouting.All -> RoutingMode.ALL
                is AppRouting.Only -> RoutingMode.ONLY
                is AppRouting.Except -> RoutingMode.EXCEPT
            },
        )
    }
    var selected by remember {
        mutableStateOf(
            when (initial) {
                is AppRouting.All -> emptySet()
                is AppRouting.Only -> initial.packages
                is AppRouting.Except -> initial.packages
            },
        )
    }
    var query by remember { mutableStateOf("") }
    // Feedback for the quick-exclusion action. Shown instead of the generic hint after a
    // tap so the user sees that something happened even when the list is scrolled away.
    var quickAddedMessage by remember { mutableStateOf<String?>(null) }

    val visible = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim().lowercase()
            apps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Маршрутизация",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
            }
        }

        Spacer(Modifier.height(8.dp))
        /*
         * The three mode chips used to sit in one Row. With "Только выбранные" their
         * combined intrinsic width exceeded a 360dp screen, so the last chip was clipped
         * and its label wrapped mid-word. FlowRow lets them fall onto a second line
         * instead of compressing the text.
         */
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = mode == RoutingMode.ALL,
                onClick = { mode = RoutingMode.ALL },
                label = { Text("Всё", maxLines = 1, softWrap = false) },
            )
            FilterChip(
                selected = mode == RoutingMode.ONLY,
                onClick = { mode = RoutingMode.ONLY },
                label = {
                    Text(
                        "Только выбранные",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            FilterChip(
                selected = mode == RoutingMode.EXCEPT,
                onClick = { mode = RoutingMode.EXCEPT },
                label = { Text("Все кроме", maxLines = 1, softWrap = false) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                RoutingMode.ALL -> "Весь трафик идёт через VPN."
                RoutingMode.ONLY -> "Через VPN пойдут только отмеченные приложения."
                RoutingMode.EXCEPT -> "Отмеченные приложения пойдут мимо VPN."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (mode != RoutingMode.ALL) {
            Spacer(Modifier.height(4.dp))
            Text(
                // Android cannot guarantee the direct path; saying otherwise would be
                // a lie the user only discovers when an excluded app still has no access.
                "«Мимо VPN» не означает «напрямую»: если активен другой VPN, трафик уйдёт в него.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск приложения", maxLines = 1) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Выбрано: ${selected.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            //
            // WHY A FULL-WIDTH BUTTON AND NOT A LINK IN THE COUNTER ROW:
            // the feature was implemented but shipped as a TextButton squeezed next to
            // "Выбрано: N" inside a conditional that only rendered in "Все кроме" mode.
            // The user reported it as "ты не добавил быстрые исключения" — it was there,
            // just invisible. A button that fills the row cannot be missed.
            if (mode == RoutingMode.EXCEPT) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val candidates = QuickExclusions
                            .filter(apps.map { it.label to it.packageName })
                            .map { it.second }
                            .toSet()
                        // Count BEFORE merging: the message must say what the tap added,
                        // not how large the list became.
                        val added = (candidates - selected).size
                        // Merge, never replace: the user's own choices must survive.
                        selected = selected + candidates
                        quickAddedMessage = when {
                            added > 0 -> "Добавлено приложений: $added"
                            candidates.isEmpty() ->
                                "Подходящих приложений не найдено среди установленных"
                            else -> "Все подходящие приложения уже выбраны"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Быстрые исключения",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Captured into a local: a delegated `var` cannot be smart-cast, so
                // reading it twice inside an if/else failed to compile.
                val hint = quickAddedMessage
                Text(
                    if (hint != null) {
                        hint
                    } else {
                        "Добавит банки, госуслуги, маркетплейсы и операторов связи из установленных."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(Modifier.weight(1f)) {
            if (mode != RoutingMode.ALL && visible.isEmpty()) {
                item {
                    // Distinguishes "nothing matched the search" from "the app list could
                    // not be read", which are different problems with different fixes.
                    Text(
                        if (apps.isEmpty()) {
                            "Список приложений недоступен."
                        } else {
                            "Ничего не найдено."
                        },
                        modifier = Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.packageName }) { app ->
                val checked = selected.contains(app.packageName)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (checked && mode != RoutingMode.ALL) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    onClick = {
                        if (mode == RoutingMode.ALL) return@Card
                        selected = if (checked) {
                            selected - app.packageName
                        } else {
                            selected + app.packageName
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = mode != RoutingMode.ALL,
                            onCheckedChange = { on ->
                                if (mode == RoutingMode.ALL) return@Checkbox
                                selected = if (on) {
                                    selected + app.packageName
                                } else {
                                    selected - app.packageName
                                }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onApply(
                    when (mode) {
                        RoutingMode.ALL -> AppRouting.All
                        RoutingMode.ONLY -> AppRouting.Only(selected)
                        RoutingMode.EXCEPT -> AppRouting.Except(selected)
                    },
                )
            },
        ) {
            Text("Применить", maxLines = 1)
        }
    }
}

private enum class RoutingMode { ALL, ONLY, EXCEPT }
