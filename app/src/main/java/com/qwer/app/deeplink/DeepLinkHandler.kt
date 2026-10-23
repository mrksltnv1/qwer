package com.qwer.app.deeplink

import android.content.Intent
import android.net.Uri
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import java.security.MessageDigest

/**
 * Parses `qwer://import?token=...` links coming from the Telegram bot.
 *
 * Security rules enforced here:
 *  - Only the exact `qwer` scheme and `import` host are accepted; anything else is
 *    rejected before any work is done (no redirects, no host substitution).
 *  - The token is opaque, single-use and time-limited. It is never logged; only a
 *    short hash prefix may appear in diagnostics.
 *  - A link NEVER starts a tunnel on its own. It only produces an [ImportRequest]
 *    that the user must confirm in the UI.
 */
object DeepLinkHandler {

    const val SCHEME = "qwer"
    const val HOST_IMPORT = "import"

    sealed interface Result {
        data class ImportRequest(val token: String) : Result
        data class Rejected(val reason: String) : Result
        data object NotOurs : Result
    }

    fun parse(intent: Intent?): Result {
        val data = intent?.data ?: return Result.NotOurs
        return parse(data)
    }

    fun parse(uri: Uri): Result {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return Result.NotOurs
        if (!uri.host.equals(HOST_IMPORT, ignoreCase = true)) {
            Diagnostics.event(Event.DEEPLINK_REJECTED, "why" to "bad_host")
            return Result.Rejected("Неизвестный адрес импорта")
        }
        val token = uri.getQueryParameter("token")
        if (token.isNullOrBlank()) {
            Diagnostics.event(Event.DEEPLINK_REJECTED, "why" to "missing_token")
            return Result.Rejected("В ссылке нет токена")
        }
        if (token.length < MIN_TOKEN_LEN || token.length > MAX_TOKEN_LEN) {
            Diagnostics.event(Event.DEEPLINK_REJECTED, "why" to "bad_token_length")
            return Result.Rejected("Токен повреждён")
        }
        // Tokens are opaque base64url-ish strings.
        if (!token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            Diagnostics.event(Event.DEEPLINK_REJECTED, "why" to "bad_token_chars")
            return Result.Rejected("Токен повреждён")
        }
        Diagnostics.event(Event.DEEPLINK_RECEIVED, "tokenHint" to hint(token))
        return Result.ImportRequest(token)
    }

    /** Short, non-reversible hint so a support conversation can correlate a link. */
    fun hint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private const val MIN_TOKEN_LEN = 16
    private const val MAX_TOKEN_LEN = 512
}
