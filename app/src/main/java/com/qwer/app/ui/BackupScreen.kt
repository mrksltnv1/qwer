package com.qwer.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qwer.app.backup.BackupManager
import androidx.compose.ui.text.style.TextOverflow

/**
 * Backup / restore screen.
 *
 * Design rules:
 *  - The password is entered twice on export (typo protection) and once on import.
 *  - Import never overwrites silently: the file is decrypted and previewed FIRST, and
 *    the dialog states how many profiles are new and how many will be replaced.
 *  - The password is never stored, logged, or put in saved state.
 */
@Composable
fun BackupScreen(
    onClose: () -> Unit,
    onExport: (password: CharArray, uri: Uri) -> Unit,
    onPreview: (uri: Uri, password: CharArray) -> Unit,
    onImport: (uri: Uri, password: CharArray, mode: BackupManager.ImportMode) -> Unit,
    statusMessage: String?,
    preview: BackupManager.PreviewResult?,
) {
    var mode by remember { mutableStateOf(ScreenMode.CHOICE) }

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
                    Text("Резервное копирование", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Профили и ключи шифруются паролем. Восстановить можно на другом устройстве.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(Modifier.height(16.dp))

            statusMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            when (mode) {
                ScreenMode.CHOICE -> {
                    OutlinedButton(
                        onClick = { mode = ScreenMode.EXPORT },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Создать резервную копию", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { mode = ScreenMode.IMPORT },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Восстановить из файла", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ScreenMode.EXPORT -> ExportForm(onExport = onExport, onCancel = { mode = ScreenMode.CHOICE })
                ScreenMode.IMPORT -> ImportForm(
                    onPreview = onPreview,
                    onImport = onImport,
                    onCancel = { mode = ScreenMode.CHOICE },
                    preview = preview,
                )
            }
        }
    }
}

private enum class ScreenMode { CHOICE, EXPORT, IMPORT }

@Composable
private fun ExportForm(
    onExport: (CharArray, Uri) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) onExport(password.toCharArray(), uri)
    }

    val mismatch = confirm.isNotEmpty() && password != confirm
    val tooShort = password.isNotEmpty() && password.length < 8

    Column {
        Text("Пароль защищает файл. Без него восстановление невозможно.", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        PasswordField("Пароль", password) { password = it }
        Spacer(Modifier.height(8.dp))
        PasswordField("Повторите пароль", confirm) { confirm = it }
        if (mismatch) {
            Spacer(Modifier.height(6.dp))
            Text("Пароли не совпадают", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
        if (tooShort) {
            Spacer(Modifier.height(6.dp))
            Text("Минимум 8 символов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { launcher.launch("qwer-backup.qwerbak") },
                enabled = password.length >= 8 && password == confirm,
            ) { Text("Сохранить файл", maxLines = 1) }
            TextButton(onClick = onCancel) { Text("Назад", maxLines = 1) }
        }
    }
}

@Composable
private fun ImportForm(
    onPreview: (Uri, CharArray) -> Unit,
    onImport: (Uri, CharArray, BackupManager.ImportMode) -> Unit,
    onCancel: () -> Unit,
    preview: BackupManager.PreviewResult?,
) {
    var password by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> picked = uri }

    Column {
        OutlinedButton(
            onClick = { launcher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (picked == null) "Выбрать файл" else "Файл выбран", maxLines = 1, overflow = TextOverflow.Ellipsis) }

        Spacer(Modifier.height(10.dp))
        PasswordField("Пароль от копии", password) { password = it }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picked?.let { onPreview(it, password.toCharArray()) } },
                enabled = picked != null && password.isNotEmpty(),
            ) { Text("Проверить", maxLines = 1) }
            TextButton(onClick = onCancel) { Text("Назад") }
        }

        if (preview is BackupManager.PreviewResult.Ok) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("В копии профилей: ${preview.total}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "Новых: ${preview.newProfiles} · заменят существующие: ${preview.conflicts}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Существующие профили, которых нет в копии, останутся.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            picked?.let { onImport(it, password.toCharArray(), BackupManager.ImportMode.MERGE) }
                        }) { Text("Добавить и заменить", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton(onClick = {
                            picked?.let { onImport(it, password.toCharArray(), BackupManager.ImportMode.REPLACE) }
                        }) { Text("Заменить всё", maxLines = 1) }
                    }
                }
            }
        }
        if (preview is BackupManager.PreviewResult.Failed) {
            Spacer(Modifier.height(10.dp))
            Text(preview.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
    )
}
