package com.qwer.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Tabs of the app. Kept as an enum so a third screen is one entry away. */
enum class AppTab(val label: String) {
    HOME("Главная"),
    LOGS("Логи"),
    DIAGNOSTICS("Диагностика"),
    BACKUP("Копия"),
}

/**
 * Bottom navigation.
 *
 * Replaces the old "replace the whole screen with a dialog-like overlay" approach:
 * the log view is a peer of the main screen, so it has its own tab and the back
 * behaviour is predictable.
 */
@Composable
fun AppBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == AppTab.HOME,
            onClick = { onSelect(AppTab.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(AppTab.HOME.label) },
        )
        NavigationBarItem(
            selected = selected == AppTab.LOGS,
            onClick = { onSelect(AppTab.LOGS) },
            icon = { Icon(Icons.Filled.Description, contentDescription = null) },
            label = { Text(AppTab.LOGS.label) },
        )
        NavigationBarItem(
            selected = selected == AppTab.DIAGNOSTICS,
            onClick = { onSelect(AppTab.DIAGNOSTICS) },
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            label = { Text(AppTab.DIAGNOSTICS.label) },
        )
        NavigationBarItem(
            selected = selected == AppTab.BACKUP,
            onClick = { onSelect(AppTab.BACKUP) },
            icon = { Icon(Icons.Filled.Save, contentDescription = null) },
            label = { Text(AppTab.BACKUP.label) },
        )
    }
}
