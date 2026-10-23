package com.qwer.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
 * There is no silent install — Android refuses it for a sideloaded app anyway.
 */
class UpdateInstaller(private val context: Context) {

    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    /**
     * Result of trying to open the installer.
     *
     * Every branch carries a user-visible outcome on purpose: the previous version
     * returned a bare `Result<Unit>` and a blocked launch looked exactly like success,
     * so the UI went back to Idle and the button appeared to do nothing.
     */
    sealed interface InstallResult {
        /** The system installer was opened. */
        data object Started : InstallResult
        /** Android needs the user to allow installs from this source. */
        data object NeedUnknownSourcesPermission : InstallResult
        /** Something concrete went wrong; the message is safe to show. */
        data class Failed(val message: String) : InstallResult
    }

    /**
     * Whether this app is allowed to install packages at all.
     *
     * On Android 8+ this is OFF by default for an app that was never granted the
     * "install unknown apps" capability. `startActivity` with an APK then does nothing
     * at all — no dialog, no exception, no log line. That is the exact symptom this
     * method exists to catch before we try.
     *
     * `canRequestPackageInstalls()` is API 26+; minSdk is 24, so the call must be
     * guarded or it throws NoSuchMethodError on Android 7.x, where installs are
     * unrestricted anyway.
     */
    fun canInstallPackages(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    } catch (e: Exception) {
        true
    }

    /**
     * Opens the per-app "install unknown apps" screen so the user can flip the switch.
     * Falls back to the general unknown-sources list when the per-app page is missing.
     */
    fun openUnknownSourcesSettings(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val perApp = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(perApp)) return true
        }

        val generic = Intent(Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(generic)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Diagnostics.warn(Diagnostics.Event.APP_UPDATE, e, "stage" to "open_settings")
        false
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

    /**
     * Opens the system installer for a downloaded APK.
     *
     * Checks the permission *first*, because without it Android returns no error and
     * shows no dialog — the call simply evaporates.
     */
    fun install(file: File): InstallResult {
        if (!file.exists() || file.length() < 1024) {
            Diagnostics.warn(Diagnostics.Event.APP_UPDATE, null, "stage" to "install", "why" to "file_missing")
            return InstallResult.Failed("Файл обновления не найден. Скачайте заново.")
        }
        if (!looksLikeApk(file)) {
            Diagnostics.warn(Diagnostics.Event.APP_UPDATE, null, "stage" to "install", "why" to "not_apk")
            return InstallResult.Failed("Скачанный файл повреждён. Скачайте заново.")
        }
        if (!canInstallPackages()) {
            Diagnostics.event(Diagnostics.Event.APP_UPDATE, "install_blocked" to "unknown_sources")
            return InstallResult.NeedUnknownSourcesPermission
        }

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: IllegalArgumentException) {
            // Authority/path mismatch — a real bug we must not swallow.
            Diagnostics.warn(Diagnostics.Event.APP_UPDATE, e, "stage" to "fileprovider")
            return InstallResult.Failed("Внутренняя ошибка доступа к файлу обновления")
        }

        // ACTION_VIEW is the modern path; ACTION_INSTALL_PACKAGE is the legacy one that
        // some OEM installers still prefer. Try both rather than dying on the first.
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStart(viewIntent)) {
            Diagnostics.event(Diagnostics.Event.APP_UPDATE, "installer" to "view")
            return InstallResult.Started
        }

        val legacyIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStart(legacyIntent)) {
            Diagnostics.event(Diagnostics.Event.APP_UPDATE, "installer" to "legacy")
            return InstallResult.Started
        }

        Diagnostics.warn(Diagnostics.Event.APP_UPDATE, null, "stage" to "install", "why" to "no_handler")
        return InstallResult.Failed(
            "На устройстве не найден системный установщик APK. " +
                "Откройте файл вручную: ${file.absolutePath}"
        )
    }

    /** Cheap structural check: a real APK/zip starts with "PK\u0003\u0004". */
    private fun looksLikeApk(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            val read = input.read(head)
            read == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
