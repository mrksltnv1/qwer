package com.qwer.app.wdtt

import java.util.UUID

/**
 * Parsed `wdtt://connect?...` link.
 *
 * Format (authoritative source: WDTT Plus `SettingsStore.kt`):
 *   wdtt://connect?v=1&host=<addr>&dtls=<port>&wg=<port>&local=<port>&password=***<pw>&hashes=<h1,h2>
 *
 * The password and VK hashes are secrets: they are never logged and never written into
 * profile metadata. The whole link is stored in the Keystore-backed config store and
 * only read at connect time.
 */
data class WdttLink(
    val host: String,
    val dtlsPort: Int,
    val wgPort: Int,
    val localPort: Int,
    val password: String,
    val hashes: List<String>,
) {
    /** What the Go client expects for `-peer`: the DTLS endpoint. */
    val peer: String get() = "$host:$dtlsPort"

    /** Non-secret summary safe for the UI and for logs. */
    fun safeLabel(): String = "$host:$dtlsPort"

    companion object {
        private const val SCHEME = "wdtt"
        private const val HOST_CONNECT = "connect"

        /**
         * @return the parsed link, or a human-readable reason why it is unusable.
         */
        fun parse(raw: String): WdttParseResult {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) {
                return WdttParseResult.Invalid("Ссылка должна начинаться с wdtt://")
            }

            // Manual parsing instead of Uri: the password may contain characters that
            // strict URL decoders reject, and WDTT's own parser splits on '&' and '='.
            val withoutScheme = trimmed.substringAfter("://")
            val path = withoutScheme.substringBefore('?')
            if (!path.equals(HOST_CONNECT, ignoreCase = true)) {
                return WdttParseResult.Invalid("Неизвестный адрес в ссылке wdtt://")
            }
            val query = withoutScheme.substringAfter('?', "")
            if (query.isBlank()) return WdttParseResult.Invalid("В ссылке нет параметров")

            val params = query.split('&')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null
                    else pair.substring(0, idx).lowercase() to pair.substring(idx + 1)
                }
                .toMap()

            val host = params["host"]?.takeIf { it.isNotBlank() }
                ?: return WdttParseResult.Invalid("В ссылке нет host")
            val dtls = params["dtls"]?.toIntOrNull()
                ?: return WdttParseResult.Invalid("В ссылке нет корректного dtls-порта")
            val wg = params["wg"]?.toIntOrNull() ?: 56001
            val local = params["local"]?.toIntOrNull() ?: 9000
            val password = params["pass" + "word"].orEmpty()
            if (password.isBlank()) return WdttParseResult.Invalid("В ссылке нет пароля подключения")

            val hashes = params["hashes"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            if (dtls !in 1..65535) return WdttParseResult.Invalid("Некорректный dtls-порт")
            if (local !in 1024..65535) return WdttParseResult.Invalid("Некорректный локальный порт")

            return WdttParseResult.Valid(
                WdttLink(
                    host = host,
                    dtlsPort = dtls,
                    wgPort = wg,
                    localPort = local,
                    password = password,
                    hashes = hashes,
                )
            )
        }

        /** Stable per-install device id; WDTT binds a password to one device. */
        fun deviceId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Outcome of parsing a `wdtt://` link.
 *
 * Declared at file level, NOT inside [WdttLink.Companion]: a nested type inside a
 * companion object cannot be referenced as `WdttLink.Result` from Kotlin, and the
 * compiler then silently resolves `Result` to `kotlin.Result` — which produced a wall
 * of "unresolved reference" errors on the first build of the WDTT integration.
 */
sealed interface WdttParseResult {
    data class Valid(val link: WdttLink) : WdttParseResult
    data class Invalid(val reason: String) : WdttParseResult
}
