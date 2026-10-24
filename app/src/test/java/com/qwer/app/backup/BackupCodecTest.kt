package com.qwer.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Container-level tests. The interesting cases are not "round trip works" but:
 * a wrong password must fail, a flipped byte must fail, and the header must be
 * authenticated so an attacker cannot weaken the KDF parameters.
 */
class BackupCodecTest {

    private val password = "correct horse battery staple".toCharArray()
    private val plaintext = """{"schema":1,"profiles":[]}""".toByteArray()

    @Test
    fun `round trip returns the original bytes`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        when (val r = BackupCodec.decrypt(encrypted, password)) {
            is BackupCodec.Result.Ok -> assertArrayEquals(plaintext, r.plaintext)
            is BackupCodec.Result.Failed -> error("decrypt failed: ${r.reason}")
        }
    }

    @Test
    fun `encrypted output is much larger than the plaintext only by header and tag`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        // 42-byte header + ciphertext + 16-byte tag
        assertEquals(plaintext.size + 42 + 16, encrypted.size)
    }

    @Test
    fun `wrong password is rejected`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        val r = BackupCodec.decrypt(encrypted, "wrong password".toCharArray())
        assertEquals(BackupCodec.Result.Failed(BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT), r)
    }

    @Test
    fun `two encryptions of the same data differ because salts and IVs are random`() {
        val a = BackupCodec.encrypt(plaintext, password)
        val b = BackupCodec.encrypt(plaintext, password)
        assertNotEquals("salt/IV must not be deterministic", a.toList(), b.toList())
        // Both still decrypt.
        assertTrue(BackupCodec.decrypt(a, password) is BackupCodec.Result.Ok)
        assertTrue(BackupCodec.decrypt(b, password) is BackupCodec.Result.Ok)
    }

    @Test
    fun `truncated file is rejected`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        val truncated = encrypted.copyOfRange(0, encrypted.size - 5)
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT),
            BackupCodec.decrypt(truncated, password),
        )
    }

    @Test
    fun `file shorter than the header is not a backup`() {
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.NOT_A_BACKUP),
            BackupCodec.decrypt(ByteArray(10), password),
        )
    }

    @Test
    fun `foreign file is rejected by magic`() {
        val foreign = ByteArray(100) { 0x41 }
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.NOT_A_BACKUP),
            BackupCodec.decrypt(foreign, password),
        )
    }

    @Test
    fun `tampering with the ciphertext is detected`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 20] = (tampered[tampered.size - 20].toInt() xor 0xFF).toByte()
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT),
            BackupCodec.decrypt(tampered, password),
        )
    }

    @Test
    fun `tampering with the salt breaks authentication`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        val tampered = encrypted.copyOf()
        tampered[20] = (tampered[20].toInt() xor 0x01).toByte() // inside salt
        // Salt is authenticated as AAD: changing it must not silently produce a new key.
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT),
            BackupCodec.decrypt(tampered, password),
        )
    }

    @Test
    fun `weakened iteration count in the header is rejected`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        val tampered = encrypted.copyOf()
        // Write iterations = 1000 into the header (offset 10, u32).
        tampered[10] = 0
        tampered[11] = 0
        tampered[12] = 0x03.toByte()
        tampered[13] = 0xE8.toByte()
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.BAD_HEADER),
            BackupCodec.decrypt(tampered, password),
        )
    }

    @Test
    fun `future format version is reported separately from corruption`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        encrypted[8] = 0
        encrypted[9] = 99.toByte()
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.UNSUPPORTED_VERSION),
            BackupCodec.decrypt(encrypted, password),
        )
    }

    @Test
    fun `oversized input is refused before any crypto work`() {
        val huge = ByteArray(BackupCodec.MAX_FILE_BYTES + 1)
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.TOO_LARGE),
            BackupCodec.decrypt(huge, password),
        )
    }

    @Test
    fun `empty password is rejected on encrypt`() {
        val failed = runCatching { BackupCodec.encrypt(plaintext, CharArray(0)) }.isFailure
        assertTrue("blank password must not be accepted", failed)
    }

    @Test
    fun `empty password cannot decrypt`() {
        val encrypted = BackupCodec.encrypt(plaintext, password)
        assertEquals(
            BackupCodec.Result.Failed(BackupCodec.Reason.BAD_PASSWORD_OR_CORRUPT),
            BackupCodec.decrypt(encrypted, CharArray(0)),
        )
    }

    @Test
    fun `single byte plaintext round trips`() {
        val one = byteArrayOf(0x7B)
        val enc = BackupCodec.encrypt(one, password)
        val r = BackupCodec.decrypt(enc, password)
        assertTrue(r is BackupCodec.Result.Ok)
        assertArrayEquals(one, (r as BackupCodec.Result.Ok).plaintext)
    }

    @Test
    fun `unicode content survives`() {
        val text = "Профиль «Домашний» — テスト ✓".toByteArray(Charsets.UTF_8)
        val enc = BackupCodec.encrypt(text, password)
        val r = BackupCodec.decrypt(enc, password) as BackupCodec.Result.Ok
        assertEquals(String(text, Charsets.UTF_8), String(r.plaintext, Charsets.UTF_8))
    }

    @Test
    fun `header magic is stable`() {
        val enc = BackupCodec.encrypt(plaintext, password)
        assertEquals("QWERBAK\u0001", String(enc.copyOfRange(0, 8), Charsets.US_ASCII))
    }
}
