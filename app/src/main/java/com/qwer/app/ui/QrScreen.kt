package com.qwer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.qwer.app.qr.QrAnalyzer
import com.qwer.app.qr.QrCodec
import java.util.concurrent.Executors

/**
 * Camera-only QR import.
 *
 * WHAT CHANGED AND WHY
 * --------------------
 * 1. Showing a profile as a QR code was REMOVED. The user's own config never needs to
 *    leave the phone for this app to work, and a screenshot of that code is a private
 *    key in the gallery. Export stays available through the encrypted backup, which is
 *    password-protected and deliberate.
 * 2. This screen used to accept ONLY our own `QVPN1:` payload and answered "это не
 *    QR-код qwer" for everything else. What people actually scan is a QR produced by
 *    their provider — a plain WireGuard/AmneziaWG config or a `wdtt://` link. Those are
 *    now parsed directly, which is the whole point of the feature.
 */
@Composable
fun QrScanScreen(
    onImport: (config: String) -> Unit,
    onClose: () -> Unit,
) {
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

    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var scannedParts by remember { mutableStateOf(listOf<String>()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Сканировать QR", style = MaterialTheme.typography.titleLarge)
                Text(
                    "WireGuard, AmneziaWG или WDTT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!hasPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Нужно разрешение камеры", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Без него сканировать QR-код невозможно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Разрешить")
                    }
                }
            }
            return
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(16.dp),
        ) {
            CameraPreview(
                onDecoded = { text ->
                    if (done) return@CameraPreview

                    // 1) Our own multi-part payload, if this is one.
                    val ourFormat = QrCodec.decode(
                        (scannedParts + text).distinct(),
                    )
                    if (ourFormat.isOk) {
                        done = true
                        val v = ourFormat.value!!
                        onImport(v.config)
                        status = "Профиль распознан: ${v.name}"
                        statusIsError = false
                        return@CameraPreview
                    }

                    // 2) Anything else: a plain config or a wdtt:// link, i.e. what a
                    //    provider actually prints in a QR code.
                    val plain = normalizeScanned(text)
                    if (plain != null) {
                        done = true
                        onImport(plain)
                        status = "Конфигурация распознана"
                        statusIsError = false
                        return@CameraPreview
                    }

                    // 3) Nothing recognisable. Only report a multi-part hint when the
                    //    text at least looks like our format; otherwise the camera is
                    //    simply pointed at the wrong code.
                    when (ourFormat.error) {
                        QrCodec.Reason.MISSING_PARTS -> {
                            scannedParts = (scannedParts + text).distinct()
                            status = "Часть ${scannedParts.size}. Сканируйте следующий код."
                            statusIsError = false
                        }
                        QrCodec.Reason.INCONSISTENT_PARTS -> {
                            scannedParts = emptyList()
                            status = "Коды от разных профилей. Начните заново."
                            statusIsError = true
                        }
                        else -> {
                            status = "Это не конфигурация VPN. Наведите на QR с конфигом."
                            statusIsError = true
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            status ?: "Наведите камеру на QR-код с конфигурацией.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (statusIsError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (scannedParts.isNotEmpty() && !done) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scannedParts = emptyList()
                    status = null
                    statusIsError = false
                }) { Text("Сбросить части") }
            }
        }
    }
}

/**
 * Accepts scanned text that is a VPN config rather than our own payload.
 *
 * Only structural checks happen here. The authoritative validation is
 * [com.qwer.app.config.ConfigImporter], which runs when the text is saved — doing it
 * twice would just duplicate the error messages.
 */
private fun normalizeScanned(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("wdtt://", ignoreCase = true)) return trimmed
    if (trimmed.startsWith("[Interface]", ignoreCase = true)) return trimmed
    // Some providers wrap configs in a qwer:// deep link or a bare base64 blob; both are
    // handled elsewhere, so anything unrecognised stays "not a config".
    return null
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
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
                    // No camera or already bound: the pane stays dark instead of taking
                    // the whole tab down.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val future = ProcessCameraProvider.getInstance(context)
                if (future.isDone) future.get().unbindAll()
            }
        }
    }
}
