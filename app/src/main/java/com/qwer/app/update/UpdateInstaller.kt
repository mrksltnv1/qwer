package com.qwer.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.qwer.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * The user stays in control: we download, then open the normal Android install dialog.
 * There is no silent install — Android would refuse it for a sideloaded app anyway, and
 * a VPN client that installs itself silently is not something anyone should ship.
 */
class UpdateInstaller(private val context: Context) {

    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    suspend fun download(
        info: UpdateInfo,
        onProgress: (percent: Int) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Fresh file each time so a partial earlier download can never be installed.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "qwer-${info.versionName}.apk")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "qwer-android")
            }
            if (conn.responseCode != 200) {
                return@withContext DownloadResult.Failed("Сервер вернул ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var written = 0L
            var lastPercent = -1

            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPercent) {
                                lastPercent = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            // A truncated download must never reach the installer: Android would show
            // "package appears to be corrupt" and the user would blame the app.
            if (total > 0 && written != total) {
                target.delete()
                return@withContext DownloadResult.Failed(
                    "Загрузка прервана ($written из $total байт)"
                )
            }
            if (written < 1024) {
                target.delete()
                return@withContext DownloadResult.Failed("Файл слишком мал")
            }
            Diagnostics.event(Diagnostics.Event.APP_UPDATE, "bytes" to written)
            DownloadResult.Ok(target)
        } catch (e: Exception) {
            Diagnostics.warn(Diagnostics.Event.APP_UPDATE, e, "stage" to "download")
            target.delete()
            DownloadResult.Failed(
                when (e) {
                    is java.net.UnknownHostException -> "Нет сети"
                    is java.net.SocketTimeoutException -> "Сервер не отвечает"
                    else -> "Не удалось скачать обновление"
                }
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** Opens the system installer for a downloaded APK. */
    fun install(file: File): Result<Unit> = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Result.success(Unit)
    } catch (e: Exception) {
        Diagnostics.warn(Diagnostics.Event.APP_UPDATE, e, "stage" to "install")
        Result.failure(e)
    }
}
