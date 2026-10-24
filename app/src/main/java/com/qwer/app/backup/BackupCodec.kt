package com.qwer.app.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup container.
 *
 * FILE LAYOUT (all integers big-endian):
 *
 * ```
 *  0   8   magic "QWERBAK" + format byte
 *  8  10   format version (u16)
 * 10  14   PBKDF2 iterations (u32)
 * 14  30   salt (16 bytes, random per file)
 * 30  42   nonce/IV (12 bytes, random per file)
 * 42  ..   AES-GCM ciphertext followed by the 16-byte tag
 * ```
 *
 * The first 42 bytes are used as AES-GCM **additional authenticated data**. That is what
 * makes the parameters tamper-evident: flipping the iteration count, the salt or the
 * version breaks authentication instead of silently producing a different key or a
 * weaker KDF. Without AAD an attacker could rewrite the iteration count to 1.
 *
 * ALGORITHM CHOICES
 *  - AES-256-GCM: authenticated encryption, so a modified byte fails loudly instead of
 *    decrypting into garbage that might be interpreted as a valid profile list.
 *  - PBKDF2WithHmacSHA256, 210 000 iterations: above the OWASP 2023 floor for
 *    SHA-256 (600 000 is the desktop recommendation; on a mid-range phone that adds
 *    multiple seconds to every backup while the file itself is only a few KB). The
 *    value is stored in the header, so it can be raised later without breaking old files.
 *  - SecureRandom for salt and nonce. Never a fixed IV, never a counter.
 *
 * The password is the only secret. Nothing is derivable from the device: a backup file
 * can be restored on a different phone, which is the whole point.
 */
object BackupCodec {

    private const val MAGIC = "QWERBAK"
    private const val FORMAT_BYTE = 1.toByte()
    private const val HEADER_SIZE = 42
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** Current default. Stored in every file so raising it does not break old backups. */
    const val DEFAULT_ITERATIONS = 210_000

    /** Hard ceiling: a backup of this app is a few KB, anything huge is an attack. */
    const val MAX_FILE_BYTES = 4 * 1024 * 1024

    /** Sanity bounds when reading a file, to stop a hostile header from forcing work. */
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000

    sealed interface Result {
        data class Ok(val plaintext: ByteArray) : Result
        data class Failed(val reason: Reason) : Result
    }

    enum class Reason {
        /** File smaller than the header, or no valid magic. */
        NOT_A_BACKUP,
        /** Format version newer than this build understands. */
        UNSUPPORTED_VERSION,
        /** Header values are outside sane bounds. */
        BAD_HEADER,
        /** File larger than [MAX_FILE_BYTES]. */
        TOO_LARGE,
        /** Wrong password, or the ciphertext/tag was modified. */
        BAD_PASSWORD_OR_CORRUPT,
        /** Anything else (provider missing, IO failure). */
        INTERNAL_ERROR,
    }

    /**
     * @throws IllegalArgumentException when [password] is blank — a caller bug, caught
     *         in tests rather than at the user's expense.
     */
    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(plaintext.isNotEmpty()) { "nothing to encrypt" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }

        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)

        val header = buildHeader(iterations, salt, iv)
        val key = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plaintext)
            return header + encrypted
        } finally {
            key.fill(0)
        }
    }

    /** Never throws for bad input: every failure is a typed [Reason]. */
    fun decrypt(file: ByteArray, password: CharArray): Result {
        if (file.size < HEADER_SIZE) return Result.Failed(Reason.NOT_A_BACKUP)
        if (file.size > MAX_FILE_BYTES) return Result.Failed(Reason.TOO_LARGE)
        if (!hasMagic(file)) return Result.Failed(Reason.NOT_A_BACKUP)

        val version = readU16(file, 8)
        if (version != FORMAT_BYTE.toInt()) {
            return Result.Failed(
                if (version > FORMAT_BYTE) Reason.UNSUPPORTED_VERSION else Reason.BAD_HEADER
            )
        }

        val iterations = readU32(file, 10)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            return Result.Failed(Reason.BAD_HEADER)
        }

        val salt = file.copyOfRange(14, 30)
        val iv = file.copyOfRange(30, 42)
        val header = file.copyOfRange(0, HEADER_SIZE)
        val ciphertext = file.copyOfRange(HEADER_SIZE, file.size)

        // A GCM tag alone is 16 bytes; anything shorter cannot even be authenticated.
        if (ciphertext.size <= TAG_BITS / 8) return Result.Failed(Reason.BAD_PASSWORD_OR_CORRUPT)

        if (password.isEmpty()) return Result.Failed(Reason.BAD_PASSWORD_OR_CORRUPT)

        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(header)
            Result.Ok(cipher.doFinal(ciphertext))
        } catch (e: javax.crypto.AEADBadTagException) {
            // Wrong password and tampering are indistinguishable by design.
            Result.Failed(Reason.BAD_PASSWORD_OR_CORRUPT)
        } catch (e: Exception) {
            Result.Failed(Reason.INTERNAL_ERROR)
        } finally {
            key.fill(0)
        }
    }

    // ---------------------------------------------------------------- header

    private fun buildHeader(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[7] = FORMAT_BYTE            // "QWERBAK" is 7 bytes, then the format byte
        writeU16(header, 8, FORMAT_BYTE.toInt())
        writeU32(header, 10, iterations)
        salt.copyInto(header, 14)
        iv.copyInto(header, 30)
        return header
    }

    private fun hasMagic(file: ByteArray): Boolean {
        val expected = (MAGIC + FORMAT_BYTE.toInt().toChar()).toByteArray(Charsets.US_ASCII)
        if (file.size < expected.size) return false
        for (i in expected.indices) {
            if (file[i] != expected[i]) return false
        }
        return true
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
