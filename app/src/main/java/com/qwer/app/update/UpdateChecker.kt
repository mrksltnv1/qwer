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
    /**
     * Human-readable version of the running build, e.g. "0.9.8".
     *
     * WHY THE NAME IS THE SOURCE OF TRUTH AND NOT versionCode:
     * the manifest scheme changed inside the 0.8 → 0.9 line. Up to 0.8.x a patch
     * incremented by 10 (0.8.2 = 820); from 0.9.0 it increments by 1 (0.9.8 = 908).
     * Any single arithmetic formula therefore disagrees with half the history — the
     * `patch * 10` variant turned tag 0.9.8 into 980 > 908 and offered users an
     * "update" to the build they were already running.
     * Comparing the dotted version names has no such failure mode.
     */
    private val currentVersionName: String = "",
    private val httpTimeoutMillis: Int = 8_000,
) {

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val latest = fetchLatestRelease() ?: return@withContext UpdateResult.Failed(
                "Не удалось получить список релизов"
            )
            val isNewer = if (currentVersionName.isNotBlank()) {
                isNewerVersion(latest.versionName, currentVersionName)
            } else {
                // Fallback for callers that only know the code.
                latest.versionCode > currentVersionCode
            }
            if (isNewer) {
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
         * Parses "0.9.8", "v0.9.8", "0.9.8-beta" into [0, 9, 8].
         * Returns null when the tag carries no numeric version.
         */
        fun parseVersion(tag: String): List<Int>? {
            val cleaned = tag.trim().removePrefix("v").removePrefix("V")
            val numeric = cleaned.split('-', '+', ' ').firstOrNull().orEmpty()
            if (numeric.isBlank()) return null
            val parts = numeric.split('.')
            if (parts.size < 2) return null
            val numbers = ArrayList<Int>(parts.size)
            for (part in parts) {
                val digits = part.takeWhile { it.isDigit() }
                if (digits.isEmpty()) return null
                numbers += digits.toIntOrNull() ?: return null
            }
            return numbers
        }

        /**
         * True when [latest] is strictly newer than [current].
         *
         * Compares component by component, treating a missing component as 0, so
         * "0.9" and "0.9.0" are equal and "0.10.0" sorts after "0.9.8" — the classic
         * string-comparison trap.
         */
        fun isNewerVersion(latest: String, current: String): Boolean {
            val a = parseVersion(latest) ?: return false
            val b = parseVersion(current) ?: return false
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /**
         * Maps a release tag to the `versionCode` used in the manifest.
         *
         * The scheme is `major * 10000 + minor * 100 + patch * 10`, matching what
         * `app/build.gradle.kts` actually ships (0.8.0 -> 800, 0.8.1 -> 810,
         * 0.8.2 -> 820).
         *
         * BUG FIXED HERE: the previous formula used `+ patch` with no *10, so it
         * computed 0.8.2 -> 802 while the APK declared 820. `802 > 820` is false, so
         * the app would have ignored every patch release for the whole 0.8.x line —
         * including the very update that fixes this bug. Caught by comparing the
         * formula against the versionCode history, not by reading the code.
         */
        fun tagToVersionCode(tag: String): Int? {
            val cleaned = tag.trim().removePrefix("v").removePrefix("V")
            val parts = cleaned.split('.', '-', '+')
            if (parts.size < 2) return null
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            // Current scheme (0.9.0 and later): 0.9.8 -> 908, 1.0.0 -> 10000.
            // The old 0.8.x line used patch * 10; it is history and cannot be recovered
            // from the tag alone, which is exactly why isNewerVersion() compares names.
            return major * 10_000 + minor * 100 + patch
        }
    }
}
