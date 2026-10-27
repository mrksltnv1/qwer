package com.qwer.app.imports

import com.qwer.app.config.ConfigImporter
import com.qwer.app.config.ImportResult
import com.qwer.app.core.Protocol
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.wdtt.WdttClientCard
import com.qwer.app.wdtt.WdttLink
import com.qwer.app.wdtt.WdttParseResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multi-profile import delivered by a single link.
 *
 * WHY THIS EXISTS
 * ---------------
 * A user with several tunnels (WireGuard on one server, AmneziaWG on another, WDTT as a
 * backup) had to copy each config by hand: paste, repeat, repeat. The bot already knows
 * every config belonging to a Telegram id, so one link can hand the whole set over.
 *
 * PROTOCOL (agreed with the bot side)
 * ----------------------------------
 *   GET <base>/<token>          → 200 application/json
 *   {
 *     "version": 1,
 *     "expires_at": 1792854000,          // epoch seconds, 0 = no expiry
 *     "profiles": [
 *       { "name": "WG · Kazan",  "config": "[Interface]\n..." },
 *       { "name": "AWG · Europe","config": "[Interface]\nJc = 4\n..." },
 *       { "name": "WDTT · backup","config": "wdtt://connect?..." }
 *     ]
 *   }
 *
 * The protocol is NOT sent by the server. It is derived locally from the payload with
 * [Protocol.classifyConfig] and [WdttLink.parse], because the server is not the
 * authority on what our engine can parse — and a wrong label would silently send an AWG
 * config down the WireGuard path.
 *
 * SECURITY
 * --------
 *  - HTTPS only; a plain-http URL is refused before any connection is opened.
 *  - The token is never logged. Only a short SHA-256 prefix is recorded so a support
 *    conversation can correlate a link without exposing it.
 *  - Response size is capped, because a hostile or broken server must not be able to
 *    exhaust memory.
 *  - Nothing is written until every profile parses. A half-imported set is worse than a
 *    failed import: the user cannot tell which tunnels are missing.
 */
object BundleImporter {

    /** Hard cap on the response body. Ten configs are ~40 KB; 2 MB is generous. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** A bundle must be small: it carries text configs, not media. */
    const val MAX_PROFILES = 50

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** One profile from the bundle, already classified. */
    data class Entry(
        val name: String,
        val protocol: Protocol,
        val config: String,
    )

    sealed interface Result {
        data class Ok(val entries: List<Entry>) : Result

        /**
         * The bundle arrived and parsed, but nothing usable was inside.
         * [skipped] explains per-profile rejections for the diagnostics log.
         */
        data class Empty(val reason: String, val skipped: List<String> = emptyList()) : Result

        data class Failed(val reason: String) : Result
    }

    // ------------------------------------------------------------------ fetch

    /**
     * Fetches and parses a bundle.
     *
     * @param baseUrl server root, e.g. `https://bot.example.com/import`
     * @param token opaque single-use token from the deep link
     */
    fun fetch(baseUrl: String, token: String): Result {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) {
            return Result.Failed("Сервер выдачи конфигураций не настроен")
        }
        if (!root.startsWith("https://", ignoreCase = true)) {
            // Refusing http is deliberate: the response contains private keys, and a
            // plaintext hop on a carrier network is a real interception surface.
            return Result.Failed("Сервер выдачи должен работать по HTTPS")
        }
        if (token.isBlank()) return Result.Failed("В ссылке нет токена")

        val url = "$root/$token"
        val body = runCatching { httpGet(url) }
            .getOrElse { e ->
                Diagnostics.warn(Event.DEEPLINK_RECEIVED, e, "stage" to "bundle_fetch")
                return Result.Failed(describeNetworkError(e))
            }

        return parse(body)
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            // No cookies, no redirects to another host: a redirect here would let a
            // compromised server move the request somewhere unexpected.
            instanceFollowRedirects = false
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw BundleHttpException(code)
            }
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) throw BundleTooLargeException()
            val bytes = conn.inputStream.use { it.readAtMost(MAX_BYTES) }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** Reads at most [limit] bytes and refuses a body that is exactly at the cap. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            total += read
            if (total > limit) throw BundleTooLargeException()
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    class BundleHttpException(val code: Int) : Exception("HTTP $code")
    class BundleTooLargeException : Exception("bundle too large")

    // ------------------------------------------------------------------ parse

    /** Parses an already-fetched body. Separated so it is unit-testable without network. */
    fun parse(body: String): Result {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
            ?: return Result.Failed("Ответ сервера повреждён")

        val version = json.optInt("version", 1)
        if (version != 1) {
            return Result.Failed("Набор создан более новой версией приложения")
        }

        val expiresAt = normalizedExpiry(json.optLong("expires_at", 0L))
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            return Result.Failed("Срок действия ссылки истёк")
        }

        val array = json.optJSONArray("profiles")
            ?: return Result.Failed("В ответе нет списка конфигураций")
        if (array.length() == 0) return Result.Empty("Сервер вернул пустой список")
        if (array.length() > MAX_PROFILES) {
            return Result.Failed("Слишком много конфигураций в одном наборе")
        }

        val entries = ArrayList<Entry>(array.length())
        val skipped = ArrayList<String>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val raw = item.optString("config")
            if (raw.isBlank()) {
                skipped += "#${i + 1}: пустой config"
                continue
            }
            val name = item.optString("name").takeIf { it.isNotBlank() }

            when (val classified = classify(name, raw)) {
                is Classification.Ok -> entries += classified.entry
                is Classification.Skip -> skipped += classified.reason
            }
        }

        if (entries.isEmpty()) {
            return Result.Empty("Ни один конфиг не подошёл", skipped)
        }
        Diagnostics.event(
            Event.PROFILE_IMPORTED,
            "stage" to "bundle",
            "count" to entries.size,
            "skipped" to skipped.size,
        )
        return Result.Ok(entries)
    }

    /**
     * Expiry may arrive in seconds or milliseconds: the Go side of the bot stores
     * seconds, the Kotlin side milliseconds. Anything below year 2001 is seconds.
     */
    private fun normalizedExpiry(raw: Long): Long = when {
        raw <= 0L -> 0L
        raw < 100_000_000_000L -> raw * 1000L
        else -> raw
    }

    // ------------------------------------------------------------------ classify

    private sealed interface Classification {
        data class Ok(val entry: Entry) : Classification
        data class Skip(val reason: String) : Classification
    }

    /**
     * Decides what a payload is by its own structure, never by the server's label.
     *
     * Order matters: a `wdtt://` link is also "text", and a client card is JSON, so the
     * URI and JSON checks must run before the `[Interface]` check.
     */
    private fun classify(name: String?, cfg: String): Classification {
        val trimmed = cfg.trim()

        // 1. WDTT connection link, possibly wrapped in surrounding text.
        val wdttLink = WdttLink.extractLink(trimmed)
        if (wdttLink != null) {
            return when (val parsed = WdttLink.parse(wdttLink)) {
                is WdttParseResult.Valid -> Classification.Ok(
                    Entry(
                        name = name ?: "WDTT · ${parsed.link.safeLabel()}",
                        protocol = Protocol.WDTT_PLUS,
                        config = wdttLink,
                    ),
                )
                is WdttParseResult.Invalid -> Classification.Skip(
                    "WDTT: ${parsed.reason}",
                )
            }
        }

        // 2. WDTT client card: credentials only, no server address. Cannot become a
        //    profile on its own, so it is reported instead of silently dropped.
        if (WdttClientCard.looksLikeCard(trimmed)) {
            return Classification.Skip(
                "WDTT-карточка без адреса сервера — импортируйте её отдельно через QR",
            )
        }

        // 3. WireGuard / AmneziaWG config text.
        return when (val validated = ConfigImporter.validate(trimmed)) {
            is ImportResult.Valid -> Classification.Ok(
                Entry(
                    name = name ?: "${validated.protocol.shortTag()} · " +
                        (validated.endpoint ?: "профиль"),
                    protocol = validated.protocol,
                    config = trimmed,
                ),
            )
            is ImportResult.Invalid -> Classification.Skip(validated.reason)
        }
    }

    /** Short tag for generated profile names. Local, so it cannot clash with the UI one. */
    private fun Protocol.shortTag(): String = when (this) {
        Protocol.WIREGUARD -> "WG"
        Protocol.AMNEZIAWG -> "AWG"
        Protocol.WDTT_PLUS -> "WDTT"
    }

    // ------------------------------------------------------------------ errors

    private fun describeNetworkError(e: Throwable): String = when (e) {
        is BundleTooLargeException -> "Ответ сервера слишком большой"
        is BundleHttpException -> when (e.code) {
            401, 403 -> "Ссылка недействительна"
            404, 410 -> "Ссылка истекла или уже использована"
            429 -> "Слишком много запросов, попробуйте позже"
            in 500..599 -> "Сервер выдачи недоступен"
            else -> "Сервер ответил ошибкой ${e.code}"
        }
        is java.net.UnknownHostException -> "Сервер выдачи не найден"
        is java.net.SocketTimeoutException -> "Сервер выдачи не ответил вовремя"
        else -> "Не удалось получить конфигурации"
    }
}
