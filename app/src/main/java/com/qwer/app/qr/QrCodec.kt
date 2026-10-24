package com.qwer.app.qr

import java.util.zip.CRC32

/**
 * Payload format for moving a profile between devices through a QR code.
 *
 * WHY A CUSTOM FORMAT
 * -------------------
 * The format is the risky part: it must fit a real QR byte-mode capacity, survive
 * chunking across several codes, and detect corruption *before* the config is handed to
 * the engine. Rendering and camera scanning are presentation concerns and sit on top of
 * this; the wire format is pinned here and covered by tests.
 *
 * FORMAT
 * ------
 *   QVPN1:<part>/<total>:<crc32-8-hex>:<base64url(name + "\n" + config)>
 *
 *  - `QVPN1` is the magic; a future incompatible change bumps it.
 *  - `part/total` allow a config to be split over several QR codes; parts must all be
 *    present and agree on `total` before anything is decoded.
 *  - The CRC32 covers the *raw* payload bytes, so a single flipped character in the
 *    base64 text is detected instead of producing a plausible but wrong config.
 *  - Base64url without padding keeps the payload alphanumeric-only, which QR encodes
 *    more densely than byte mode.
 *
 * LIMITS
 * ------
 * A single QR in byte mode holds 2953 bytes; the alphanumeric mode used here holds 4296
 * characters. [MAX_PART_BYTES] is set below both so the caller never has to think about
 * QR version 40 vs. error correction level.
 *
 * SECURITY
 * --------
 * The payload contains the private key. It is never logged; [Decoded] deliberately has no
 * `toString()` that could leak it into a crash report.
 */
object QrCodec {

    const val MAGIC = "QVPN1"

    /** Maximum characters in one code, chosen to stay inside QR alphanumeric capacity. */
    const val MAX_PART_BYTES = 2_800

    /** Beyond this a config is not worth moving by hand; it is also a decompression-bomb guard. */
    const val MAX_TOTAL_BYTES = 64 * 1024

    sealed interface Result {
        data class Ok(val parts: List<String>) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason {
        /** The text is not one of our codes at all. */
        NOT_A_QR_PAYLOAD,

        /** A newer app wrote it with an incompatible layout. */
        UNSUPPORTED_VERSION,

        /** The profile is too large to move over QR codes. */
        TOO_LARGE,

        /** Part numbering is inconsistent — usually two different profiles were scanned. */
        INCONSISTENT_PARTS,

        /** A part is missing, so reassembly would silently truncate the config. */
        MISSING_PARTS,

        /** A character was misread by the camera. */
        CHECKSUM_MISMATCH,

        /** Base64 or the name/config separator is broken. */
        CORRUPT_PAYLOAD,
    }

    /** A decoded profile. Holds the config verbatim; never log this object. */
    class Decoded(val name: String, val config: String)

    // ------------------------------------------------------------------ encode

    /**
     * Encodes a profile into one or more QR payload strings.
     *
     * @param name profile display name; newlines are stripped because they separate it
     *        from the config inside the payload.
     * @param config raw WireGuard/AmneziaWG text
     */
    fun encode(name: String, config: String): Result {
        val safeName = name.replace("\n", " ").replace("\r", " ").ifBlank { "Профиль" }
        val body = (safeName + "\n" + config).toByteArray(Charsets.UTF_8)
        if (body.size > MAX_TOTAL_BYTES) return Result.Invalid(Reason.TOO_LARGE)

        val data = b64encode(body)
        val crc = crcHex(body)

        // Worst-case header for 1 part; used to decide how much data fits per code.
        val headerLen = "$MAGIC:1/1:$crc:".length
        val perPart = MAX_PART_BYTES - headerLen
        if (perPart < 16) return Result.Invalid(Reason.TOO_LARGE)

        val total = if (data.length <= perPart) 1 else (data.length + perPart - 1) / perPart
        if (total > 99) return Result.Invalid(Reason.TOO_LARGE)

        val parts = ArrayList<String>(total)
        for (i in 0 until total) {
            val from = i * perPart
            val to = minOf(from + perPart, data.length)
            parts += "$MAGIC:${i + 1}/$total:$crc:${data.substring(from, to)}"
        }
        return Result.Ok(parts)
    }

    // ------------------------------------------------------------------ decode

    /**
     * Reassembles and verifies a set of scanned payload strings.
     *
     * Order of input does not matter. Duplicates are tolerated (users rescan the same
     * code) as long as the payloads are identical.
     */
    fun decode(parts: List<String>): DecodedOrError {
        val trimmed = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return DecodedOrError(Reason.NOT_A_QR_PAYLOAD)

        var expectedTotal = -1
        var crc = ""
        val byIndex = HashMap<Int, String>()

        for (raw in trimmed) {
            val prefix = "$MAGIC:"
            if (!raw.startsWith(prefix)) {
                return DecodedOrError(
                    if (raw.startsWith("QVPN")) Reason.UNSUPPORTED_VERSION
                    else Reason.NOT_A_QR_PAYLOAD,
                )
            }
            val rest = raw.substring(prefix.length)
            val slash = rest.indexOf('/')
            val colon1 = rest.indexOf(':')
            if (slash <= 0 || colon1 <= slash) return DecodedOrError(Reason.CORRUPT_PAYLOAD)

            val index = rest.substring(0, slash).toIntOrNull()
                ?: return DecodedOrError(Reason.CORRUPT_PAYLOAD)
            val total = rest.substring(slash + 1, colon1).toIntOrNull()
                ?: return DecodedOrError(Reason.CORRUPT_PAYLOAD)
            val colon2 = rest.indexOf(':', colon1 + 1)
            if (colon2 < 0) return DecodedOrError(Reason.CORRUPT_PAYLOAD)

            val thisCrc = rest.substring(colon1 + 1, colon2)
            val chunk = rest.substring(colon2 + 1)

            if (index < 1 || total < 1 || index > total) {
                return DecodedOrError(Reason.CORRUPT_PAYLOAD)
            }
            if (expectedTotal == -1) {
                expectedTotal = total
                crc = thisCrc
            } else if (total != expectedTotal || thisCrc != crc) {
                // Mixing two different profiles is a real user error worth naming.
                return DecodedOrError(Reason.INCONSISTENT_PARTS)
            }
            val existing = byIndex[index]
            if (existing != null && existing != chunk) {
                return DecodedOrError(Reason.INCONSISTENT_PARTS)
            }
            byIndex[index] = chunk
        }

        if (byIndex.size != expectedTotal) return DecodedOrError(Reason.MISSING_PARTS)

        val joined = (1..expectedTotal).joinToString("") { byIndex.getValue(it) }
        val bytes = b64decode(joined) ?: return DecodedOrError(Reason.CORRUPT_PAYLOAD)
        if (bytes.size > MAX_TOTAL_BYTES) return DecodedOrError(Reason.TOO_LARGE)
        if (crcHex(bytes) != crc.lowercase()) return DecodedOrError(Reason.CHECKSUM_MISMATCH)

        val text = bytes.toString(Charsets.UTF_8)
        val nl = text.indexOf('\n')
        if (nl <= 0) return DecodedOrError(Reason.CORRUPT_PAYLOAD)
        val name = text.substring(0, nl).trim()
        val config = text.substring(nl + 1)
        if (name.isEmpty() || config.isBlank()) return DecodedOrError(Reason.CORRUPT_PAYLOAD)
        return DecodedOrError(null, Decoded(name, config))
    }

    /**
     * Small result holder: [error] is null exactly when [value] is present.
     * Kept as one class so callers cannot forget to check both branches.
     */
    class DecodedOrError(val error: Reason?, val value: Decoded? = null) {
        val isOk: Boolean get() = error == null && value != null
    }

    // ------------------------------------------------------------------ helpers

    private fun crcHex(bytes: ByteArray): String {
        val c = CRC32().apply { update(bytes) }
        return c.value.toString(16).padStart(8, '0')
    }

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url without padding. Hand-rolled to stay testable on plain JVM (no Android). */
    fun b64encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
            }
        }
        return sb.toString()
    }

    /** Returns null on any invalid character instead of throwing. */
    fun b64decode(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        val out = ByteArray(s.length * 3 / 4 + 3)
        var outLen = 0
        var buf = 0
        var bits = 0
        for (ch in s) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buf ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}
