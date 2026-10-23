package com.qwer.app.update

import com.qwer.app.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where new builds come from. The app is distributed outside Google Play, so the
 * release feed is the single source of truth for "is there a newer version".
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseUrl: String,
    val notes: String,
    val sizeBytes: Long,
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: UpdateInfo) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Checks GitHub Releases for a newer build.
 *
 * Deliberately no auto-download and no silent install: the user is *offered* an update
 * and decides. Silent self-update from an APK download is exactly the behaviour that
 * makes sideloaded apps look like malware, and Play Protect would block it anyway.
 */
class UpdateChecker(
    private val repoOwner: String = "mrksltnv1",
    private val repoName: String = "qwer",
    private val currentVersionCode: Int,
    private val httpTimeoutMillis: Int = 8_000,
) {

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatestRelease() ?: return@withContext UpdateResult.Failed(
                "Не удалось получить список релизов"
            )
            if (latest.versionCode > currentVersionCode) {
                Diagnostics.event(
                    Diagnostics.Event.APP_START,
                    "update_available" to latest.versionName,
                )
                UpdateResult.Available(latest)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            Diagnostics.warn(Diagnostics.Event.APP_START, e, "stage" to "update_check")
            UpdateResult.Failed(humanize(e))
        }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val conn = (URL("https://api.github.com/repos/$repoOwner/$repoName/releases")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = httpTimeoutMillis
            readTimeout = httpTimeoutMillis
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "qwer-android")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = org.json.JSONArray(body)
            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft", false)) continue
                if (r.optBoolean("prerelease", false)) continue
                val tag = r.optString("tag_name").removePrefix("v").removePrefix("V")
                val code = tagToVersionCode(tag) ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                var apkSize = 0L
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("probe", ignoreCase = true)
                    ) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size", 0L)
                        if (name.contains("universal", ignoreCase = true)) break
                    }
                }
                if (apkUrl == null) continue
                return UpdateInfo(
                    versionName = tag,
                    versionCode = code,
                    downloadUrl = apkUrl,
                    releaseUrl = r.optString("html_url"),
                    notes = r.optString("body").take(600),
                    sizeBytes = apkSize,
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun humanize(e: Exception): String = when {
        e is java.net.UnknownHostException -> "Нет сети"
        e is java.net.SocketTimeoutException -> "Сервер не отвечает"
        else -> "Ошибка проверки обновлений"
    }

    companion object {
        /**
         * "0.4.0" -> 400. Version codes must be monotonic: the manifest uses
         * major*10000 + minor*100 + patch so a semver tag maps to a comparable int.
         */
        fun tagToVersionCode(tag: String): Int? {
            val parts = tag.trim().split('.', '-', '+')
            if (parts.size < 2) return null
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            return major * 10_000 + minor * 100 + patch
        }
    }
}
