package com.qwer.app.wdtt

import org.json.JSONObject

/**
 * `wdtt-plus-client` transfer card.
 *
 * WHAT THIS FORMAT IS, AND WHY IT IS NOT A LINK
 * ---------------------------------------------
 * WDTT Plus has two different things that both get called "QR":
 *
 *  1. `wdtt://connect?...` — a CONNECTION link: host, three ports, password, hashes.
 *     Everything needed to dial the server is in the string.
 *  2. `{"format":"wdtt-plus-client",...}` — a CLIENT CARD: one client's password,
 *     VK hash, label and expiry. It deliberately contains NO server address, because
 *     it is minted on the server and handed to a client that already knows where to
 *     connect — in WDTT Plus the server is chosen in the admin section, which we do
 *     not have.
 *
 * The field report was "QR for WDTT is not accepted" while WG/Amnezia worked, and the
 * scanned text was exactly this card. No amount of link parsing could ever accept it:
 * the address simply is not in the payload.
 *
 * So this app recognises the card, keeps its credentials, and asks the user for the
 * server address once. Refusing with "это не конфигурация VPN" was technically true
 * and practically useless.
 *
 * The password and VK hash are secrets: they are never logged, and the assembled link
 * goes straight into the Keystore-backed config store.
 */
data class WdttClientCard(
    val password: String,
    val label: String?,
    val vkHash: String?,
    /** Epoch millis, 0 = no expiry. */
    val expiresAtMillis: Long,
    val deactivated: Boolean,
) {
    /** Non-secret summary, safe for logs and UI. */
    fun safeLabel(): String = label?.takeIf { it.isNotBlank() } ?: "WDTT"

    /** True when the server should refuse this client. */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis > 0 && expiresAtMillis <= nowMillis

    /**
     * Assembles a connection link once the user supplies the server address.
     *
     * Ports default to the reference implementation's values (DTLS 56000, WG 56001,
     * local 9000) because the card does not carry them and every stock WDTT server
     * uses these unless an operator changed them.
     */
    fun toLink(
        host: String,
        dtlsPort: Int = DEFAULT_DTLS,
        wgPort: Int = DEFAULT_WG,
        localPort: Int = DEFAULT_LOCAL,
    ): String {
        val params = linkedMapOf(
            "v" to "1",
            "host" to host.trim(),
            "dtls" to dtlsPort.toString(),
            "wg" to wgPort.toString(),
            "local" to localPort.toString(),
            "password" to password,
            "hashes" to (vkHash ?: ""),
        )
        // Server-side values are URL-encoded (WDTT Plus encodes every field), so a
        // password containing '=' or '&' survives the round trip.
        return "wdtt://connect?" + params.entries.joinToString("&") { (k, v) ->
            "$k=${encode(v)}"
        }
    }

    companion object {
        private const val FORMAT = "wdtt-plus-client"
        private const val SUPPORTED_VERSION = 1
        const val DEFAULT_DTLS = 56000
        const val DEFAULT_WG = 56001
        const val DEFAULT_LOCAL = 9000

        /**
         * True when [text] looks like a client card rather than a config or a link.
         * Cheap pre-check so the camera can route the text without parsing JSON twice.
         */
        fun looksLikeCard(text: String): Boolean {
            val t = text.trim()
            if (!t.startsWith("{")) return false
            return t.contains("\"$FORMAT\"") || t.contains("wdtt-plus-client")
        }

        /**
         * Parses the card.
         *
         * @return the card, or null when the payload is a different JSON document.
         *         A card with a wrong version is returned as an error through [Result]
         *         so the user learns that the app is out of date instead of seeing
         *         "not a config".
         */
        fun parse(text: String): Result {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) return Result.NotACard

            val json = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return Result.Invalid("Данные клиента повреждены")

            val format = json.optString("format")
            if (format != FORMAT) return Result.NotACard

            val version = json.optInt("version", -1)
            if (version != SUPPORTED_VERSION) {
                return Result.Invalid("Карточка клиента создана более новой версией WDTT Plus")
            }

            val password = json.optString("password")
            if (password.isBlank()) return Result.Invalid("В карточке нет пароля клиента")

            val expiresAt = json.optLong("expires_at", 0L)
            // WDTT Plus stores seconds in the Go struct and millis in the Android one;
            // accept either by treating anything below ~year 2001 as seconds.
            val expiresMillis = when {
                expiresAt <= 0L -> 0L
                expiresAt < 100_000_000_000L -> expiresAt * 1000L
                else -> expiresAt
            }

            val card = WdttClientCard(
                password = password,
                label = json.optString("label").takeIf { it.isNotBlank() },
                vkHash = json.optString("vk_hash").takeIf { it.isNotBlank() },
                expiresAtMillis = expiresMillis,
                deactivated = json.optBoolean("deactivated", false),
            )

            if (card.isExpired()) {
                return Result.Invalid("Срок действия клиента истёк")
            }
            if (card.deactivated) {
                return Result.Invalid("Клиент отключён на сервере")
            }
            return Result.Ok(card)
        }

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    sealed interface Result {
        data class Ok(val card: WdttClientCard) : Result

        /** The text is not a client card; the caller should try other formats. */
        data object NotACard : Result

        /** It IS a card, but unusable — show this to the user. */
        data class Invalid(val reason: String) : Result
    }
}
