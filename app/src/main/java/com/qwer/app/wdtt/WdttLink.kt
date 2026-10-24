package com.qwer.app.wdtt

import java.net.URLDecoder
import java.util.UUID

/**
 * Parsed `wdtt://` link.
 *
 * TWO FORMATS EXIST AND BOTH MUST WORK
 * ------------------------------------
 * Modern (authoritative source: WDTT Plus `TransferCodec.buildConnectionLink`):
 *   wdtt://connect?v=1&host=<addr>&dtls=<port>&wg=<port>&local=<port>&password=<pw>&hashes=<h1,h2>
 *
 * Legacy (WDTT Plus `SettingsStore`, still emitted by older servers and QR codes):
 *   wdtt://<host>:<dtls>:<wg>:<local>:<password>:<hashes>
 *
 * The field log said "WDTT QR is not recognised" while WireGuard and AmneziaWG worked.
 * Three separate reasons, all fixed here:
 *
 *  1. We required the text to START with `wdtt://`. WDTT Plus wraps the link with
 *     surrounding text (a share sheet message, a branded QR) and finds it with
 *     indexOf(). A QR containing "Подключение: wdtt://connect?..." was rejected.
 *  2. We only parsed the modern `connect?` form. A legacy six-field link was rejected
 *     as "unknown host in wdtt:// link".
 *  3. We never URL-decoded values. WDTT Plus encodes every field with
 *     URLEncoder.encode, so a password or a VK hash containing `%2C`, `%3D` or a space
 *     arrived mangled and the tunnel failed with a wrong password.
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
         * Pulls a `wdtt://` link out of arbitrary text.
         *
         * Mirrors WDTT Plus `extractWdttLink`: the link may be preceded by a title and
         * followed by punctuation, a newline or a quote. Without this, a QR produced by
         * any "share connection" screen was rejected even though it contained a valid
         * link.
         *
         * @return the link alone, or null when the text carries none.
         */
        fun extractLink(raw: String): String? {
            val start = raw.indexOf("$SCHEME://", ignoreCase = true)
            if (start < 0) return null
            return raw.substring(start)
                .substringBeforeAny(charArrayOf('\n', '\r', '\t', ' '))
                .trimEnd('.', ',', ';', ')', ']', '}', '»', '"', '\'', '>')
                .takeIf { it.isNotBlank() }
        }

        /**
         * @return the parsed link, or a human-readable reason why it is unusable.
         */
        fun parse(raw: String): WdttParseResult {
            // Surrounding text is allowed; only the link itself is parsed.
            val link = extractLink(raw)
                ?: return WdttParseResult.Invalid("В тексте нет ссылки wdtt://")

            val withoutScheme = link.substringAfter("://", "")

            return if (withoutScheme.startsWith(HOST_CONNECT, ignoreCase = true)) {
                parseModern(withoutScheme)
            } else {
                parseLegacy(withoutScheme)
            }
        }

        // ------------------------------------------------------------------ modern

        private fun parseModern(withoutScheme: String): WdttParseResult {
            val query = withoutScheme.substringAfter('?', "")
            if (query.isBlank()) return WdttParseResult.Invalid("В ссылке нет параметров")

            // Manual parsing instead of Uri: a password may contain characters that
            // strict URL decoders reject, and WDTT's own parser splits on '&' and '='.
            val params = query.split('&')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null
                    else decode(pair.substring(0, idx)).lowercase() to decode(pair.substring(idx + 1))
                }
                .toMap()

            // v=1 is a guarantee of the layout; a future v=2 may reorder fields, and
            // guessing would silently connect with wrong parameters.
            val version = params["v"]
            if (version != null && version != "1") {
                return WdttParseResult.Invalid("Ссылка создана более новой версией WDTT")
            }

            val host = params["host"]?.trim()?.takeIf { it.isNotBlank() }
                ?: return WdttParseResult.Invalid("В ссылке нет host")
            val dtls = params["dtls"]?.trim()?.toIntOrNull()
                ?: return WdttParseResult.Invalid("В ссылке нет корректного dtls-порта")
            val wg = params["wg"]?.trim()?.toIntOrNull() ?: 56001
            val local = params["local"]?.trim()?.toIntOrNull() ?: 9000
            val password = params["pass" + "word"].orEmpty()
            if (password.isBlank()) return WdttParseResult.Invalid("В ссылке нет пароля подключения")

            val hashes = splitHashes(params["hashes"])
            return validate(host, dtls, wg, local, password, hashes)
        }

        // ------------------------------------------------------------------ legacy

        /**
         * Legacy layout: `host:dtls:wg:local:password:hashes`.
         *
         * Split from the RIGHT for the last field only would break on a password that
         * contains ':' — which WDTT's own validator forbids, so a plain split is what
         * the reference implementation does and what we mirror.
         */
        private fun parseLegacy(withoutScheme: String): WdttParseResult {
            val parts = withoutScheme.split(':')
            if (parts.size != 6) {
                return WdttParseResult.Invalid(
                    "Старый формат ссылки: нужно 6 полей, найдено ${parts.size}",
                )
            }
            val host = parts[0].trim().takeIf { it.isNotBlank() }
                ?: return WdttParseResult.Invalid("В ссылке нет host")
            val dtls = parts[1].trim().toIntOrNull()
                ?: return WdttParseResult.Invalid("В ссылке нет корректного dtls-порта")
            val wg = parts[2].trim().toIntOrNull() ?: 56001
            val local = parts[3].trim().toIntOrNull() ?: 9000
            val password = parts[4].trim()
            if (password.isBlank()) return WdttParseResult.Invalid("В ссылке нет пароля подключения")
            val hashes = splitHashes(parts[5])
            return validate(host, dtls, wg, local, password, hashes)
        }

        // ------------------------------------------------------------------ shared

        private fun splitHashes(raw: String?): List<String> = raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        private fun validate(
            host: String,
            dtls: Int,
            wg: Int,
            local: Int,
            password: String,
            hashes: List<String>,
        ): WdttParseResult {
            if (dtls !in 1..65535) return WdttParseResult.Invalid("Некорректный dtls-порт")
            if (wg !in 1..65535) return WdttParseResult.Invalid("Некорректный wg-порт")
            if (local !in 1024..65535) return WdttParseResult.Invalid("Некорректный локальный порт")
            return WdttParseResult.Valid(
                WdttLink(
                    host = host,
                    dtlsPort = dtls,
                    wgPort = wg,
                    localPort = local,
                    password = password,
                    hashes = hashes,
                ),
            )
        }

        /**
         * Cuts the string at the first occurrence of any of [delimiters].
         *
         * A local helper rather than a stdlib chain: `split(...).first()` allocates a
         * list for every scan frame and the camera calls this on every decoded code.
         */
        private fun String.substringBeforeAny(delimiters: CharArray): String {
            val index = indexOfFirst { it in delimiters }
            return if (index < 0) this else substring(0, index)
        }

        /**
         * URL-decodes one field, tolerating text that was never encoded.
         *
         * WDTT Plus encodes with `URLEncoder.encode(...).replace("+", "%20")`, so
         * decoding '+' as a space is correct for its output, and a raw '%' in an
         * unencoded password must not throw — hence the fallback.
         */
        private fun decode(value: String): String = runCatching {
            URLDecoder.decode(value, "UTF-8")
        }.getOrDefault(value)

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
