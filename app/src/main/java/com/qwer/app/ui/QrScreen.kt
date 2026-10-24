package com.qwer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.qwer.app.core.VpnProfile
import com.qwer.app.qr.QrAnalyzer
import com.qwer.app.qr.QrBitmap
import com.qwer.app.qr.QrCodec
import java.util.concurrent.Executors

/**
 * QR transfer screen: show a profile as a QR code, or scan one.
 *
 * The payload format lives in [QrCodec] and is unit-tested; this file only handles
 * pixels, permissions and navigation.
 *
 * Deliberate choices:
 *  - A profile with a large config becomes several QR codes. The screen shows which one
 *    is on display and steps through them, because showing only the first would silently
 *    transfer a truncated config.
 *  - Scanning accumulates parts until the set is complete; a single code is the common
 *    case and completes immediately.
 *  - The camera is released in [DisposableEffect] — leaving it bound would keep the
 *    sensor and the process awake after the user switches tabs.
 */
@Composable
fun QrScreen(
    profiles: List<VpnProfile>,
    configForProfile: (String) -> String?,
    onImport: (name: String, config: String) -> Unit,
    onClose: () -> Unit,
) {
    var mode by remember { mutableStateOf(QrMode.SHOW) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "QR-перенос",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == QrMode.SHOW,
                onClick = { mode = QrMode.SHOW },
                label = { Text("Показать") },
            )
            FilterChip(
                selected = mode == QrMode.SCAN,
                onClick = { mode = QrMode.SCAN },
                label = { Text("Сканировать") },
            )
        }
        Spacer(Modifier.height(12.dp))

        when (mode) {
            QrMode.SHOW -> ShowPane(profiles, configForProfile)
            QrMode.SCAN -> ScanPane(onImport = onImport)
        }
    }
}

private enum class QrMode { SHOW, SCAN }

// ---------------------------------------------------------------------- show

@Composable
private fun ShowPane(
    profiles: List<VpnProfile>,
    configForProfile: (String) -> String?,
) {
    if (profiles.isEmpty()) {
        Centered("Нет профилей", "Сначала импортируйте конфигурацию.")
        return
    }

    var selectedId by remember { mutableStateOf(profiles.first().id) }
    var partIndex by remember { mutableStateOf(0) }
    var payloadError by remember { mutableStateOf<String?>(null) }

    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()

    // Encoding is done once per profile, not per recomposition: zxing work on a 900 px
    // bitmap on every frame of the animation would stutter the list.
    val encoded = remember(selectedId) {
        val config = configForProfile(selected.id)
        if (config == null) {
            payloadError = "Конфигурация недоступна — импортируйте профиль заново"
            null
        } else {
            when (val res = QrCodec.encode(selected.name, config)) {
                is QrCodec.Result.Ok -> res.parts
                is QrCodec.Result.Invalid -> {
                    payloadError = "Профиль слишком большой для QR"
                    null
                }
            }
        }
    }

    if (encoded != null) partIndex = partIndex.coerceIn(0, encoded.lastIndex)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(profiles) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (p.id == selectedId) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    onClick = {
                        selectedId = p.id
                        partIndex = 0
                        payloadError = null
                    },
                ) {
                    Text(
                        p.safeLabel(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val error = payloadError
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        val parts = encoded ?: return@Column
        val bitmap = remember(selectedId, partIndex) { QrBitmap.encode(parts[partIndex]) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR-код профиля",
                    modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f),
                )
            } else {
                Text("Не удалось построить QR", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            if (parts.size > 1) {
                Text(
                    "Код ${partIndex + 1} из ${parts.size}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (partIndex > 0) partIndex-- },
                        enabled = partIndex > 0,
                    ) { Text("Назад") }
                    Button(
                        onClick = { if (partIndex < parts.lastIndex) partIndex++ },
                        enabled = partIndex < parts.lastIndex,
                    ) { Text("Далее") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Отсканируйте все коды по порядку.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Отсканируйте вторым устройством.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "В коде приватный ключ профиля. Не публикуйте скриншот.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------- scan

@Composable
private fun ScanPane(onImport: (name: String, config: String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var scanned by remember { mutableStateOf(listOf<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    if (!hasPermission) {
        Centered(
            "Нужно разрешение камеры",
            "Без него сканировать QR-код невозможно.",
            action = {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Разрешить")
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            CameraPreview(
                enabled = !done,
                onDecoded = { text ->
                    if (done) return@CameraPreview
                    if (scanned.contains(text)) return@CameraPreview
                    val next = scanned + text
                    scanned = next

                    val result = QrCodec.decode(next)
                    when {
                        result.isOk -> {
                            done = true
                            val v = result.value!!
                            onImport(v.name, v.config)
                            status = "Импортировано: ${v.name}"
                        }
                        result.error == QrCodec.Reason.MISSING_PARTS ->
                            status = "Часть ${next.size}. Сканируйте следующий код."
                        else ->
                            status = describe(result.error)
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            status ?: "Наведите камеру на QR-код профиля.",
            style = MaterialTheme.typography.bodySmall,
            color = if (status?.startsWith("Импортировано") == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (scanned.isNotEmpty() && !done) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                scanned = emptyList()
                status = null
            }) { Text("Сбросить") }
        }
    }
}

@Composable
private fun CameraPreview(enabled: Boolean, onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider == null) return@addListener
                try {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, QrAnalyzer(onDecoded)) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Throwable) {
                    // No camera / already bound: the pane simply stays dark instead of
                    // crashing the whole tab.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )

    // Unbind when the tab goes away so the sensor is not held by a hidden screen.
    DisposableEffect(enabled) {
        onDispose {
            runCatching {
                val ctx = context
                val future = ProcessCameraProvider.getInstance(ctx)
                if (future.isDone) future.get().unbindAll()
            }
        }
    }
}

// ---------------------------------------------------------------------- helpers

@Composable
private fun Centered(
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

private fun describe(reason: QrCodec.Reason?): String = when (reason) {
    QrCodec.Reason.NOT_A_QR_PAYLOAD -> "Это не QR-код qwer"
    QrCodec.Reason.UNSUPPORTED_VERSION -> "Код создан более новой версией приложения"
    QrCodec.Reason.TOO_LARGE -> "Профиль слишком большой"
    QrCodec.Reason.INCONSISTENT_PARTS -> "Коды от разных профилей"
    QrCodec.Reason.MISSING_PARTS -> "Не хватает части кодов"
    QrCodec.Reason.CHECKSUM_MISMATCH -> "Код повреждён, попробуйте ещё раз"
    QrCodec.Reason.CORRUPT_PAYLOAD -> "Не удалось прочитать код"
    null -> "Не удалось прочитать код"
}
