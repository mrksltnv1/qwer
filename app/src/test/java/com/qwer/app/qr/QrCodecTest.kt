package com.qwer.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR payload format is the part that must not fail silently: a truncated or
 * mis-scanned code has to be rejected before the config reaches the engine.
 */
class QrCodecTest {

    private val config = """
        [Interface]
        PrivateKey = YK0m0P7m6l0m0P7m6l0m0P7m6l0m0P7m6l0m0P7m=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789abcd=
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    // ------------------------------------------------------------------ round trip

    @Test
    fun `single code round trip preserves name and config exactly`() {
        val enc = QrCodec.encode("Мой профиль", config) as QrCodec.Result.Ok
        assertEquals(1, enc.parts.size)

        val dec = QrCodec.decode(enc.parts)
        assertTrue(dec.error?.name ?: "ok", dec.isOk)
        assertEquals("Мой профиль", dec.value!!.name)
        assertEquals(config, dec.value!!.config)
    }

    @Test
    fun `payload starts with the magic so foreign qr codes are rejected`() {
        val enc = QrCodec.encode("p", config) as QrCodec.Result.Ok
        assertTrue(enc.parts[0].startsWith("QVPN1:"))
    }

    @Test
    fun `cyrillic names survive utf8`() {
        val enc = QrCodec.encode("Казань 🇷🇺 тест", config) as QrCodec.Result.Ok
        val dec = QrCodec.decode(enc.parts)
        assertEquals("Казань 🇷🇺 тест", dec.value!!.name)
    }

    @Test
    fun `newlines in the name are stripped so the separator stays unambiguous`() {
        val enc = QrCodec.encode("a\nb\rc", config) as QrCodec.Result.Ok
        val dec = QrCodec.decode(enc.parts)
        assertEquals("a b c", dec.value!!.name)
        assertEquals(config, dec.value!!.config)
    }

    @Test
    fun `blank name gets a default instead of producing an empty profile`() {
        val enc = QrCodec.encode("   ", config) as QrCodec.Result.Ok
        val dec = QrCodec.decode(enc.parts)
        assertEquals("Профиль", dec.value!!.name)
    }

    // ------------------------------------------------------------------ chunking

    @Test
    fun `large config splits into several codes that reassemble`() {
        // ~9 KB of config: comfortably beyond one QR code.
        val big = config + "\n# " + "x".repeat(9000)
        val enc = QrCodec.encode("big", big) as QrCodec.Result.Ok
        assertTrue("expected chunking, got ${enc.parts.size} part(s)", enc.parts.size > 1)

        val dec = QrCodec.decode(enc.parts)
        assertTrue(dec.isOk)
        assertEquals(big, dec.value!!.config)
    }

    @Test
    fun `each part stays inside the qr capacity limit`() {
        val big = config + "\n# " + "y".repeat(12000)
        val enc = QrCodec.encode("big", big) as QrCodec.Result.Ok
        enc.parts.forEach { p ->
            assertTrue("part is ${p.length} chars, limit ${QrCodec.MAX_PART_BYTES}",
                p.length <= QrCodec.MAX_PART_BYTES)
        }
    }

    @Test
    fun `parts can be scanned in any order`() {
        val big = config + "\n# " + "z".repeat(9000)
        val enc = QrCodec.encode("big", big) as QrCodec.Result.Ok
        val dec = QrCodec.decode(enc.parts.reversed())
        assertTrue(dec.isOk)
        assertEquals(big, dec.value!!.config)
    }

    @Test
    fun `duplicate scans are tolerated`() {
        val enc = QrCodec.encode("p", config) as QrCodec.Result.Ok
        val dec = QrCodec.decode(enc.parts + enc.parts + enc.parts)
        assertTrue(dec.isOk)
        assertEquals(config, dec.value!!.config)
    }

    @Test
    fun `missing part is reported instead of silently truncating`() {
        val big = config + "\n# " + "q".repeat(9000)
        val enc = QrCodec.encode("big", big) as QrCodec.Result.Ok
        assertTrue(enc.parts.size > 1)
        val dec = QrCodec.decode(enc.parts.dropLast(1))
        assertEquals(QrCodec.Reason.MISSING_PARTS, dec.error)
        assertNull(dec.value)
    }

    @Test
    fun `mixing two profiles is named as inconsistent parts`() {
        val a = QrCodec.encode("a", config + "\n# " + "a".repeat(9000)) as QrCodec.Result.Ok
        val b = QrCodec.encode("b", config + "\n# " + "b".repeat(9000)) as QrCodec.Result.Ok
        val dec = QrCodec.decode(listOf(a.parts[0], b.parts[1]))
        assertEquals(QrCodec.Reason.INCONSISTENT_PARTS, dec.error)
    }

    // ------------------------------------------------------------------ corruption

    @Test
    fun `a single flipped character is caught by the checksum`() {
        val enc = QrCodec.encode("p", config) as QrCodec.Result.Ok
        val part = enc.parts[0]
        // Flip one character near the end of the base64 body.
        val idx = part.length - 3
        val ch = part[idx]
        val replacement = if (ch == 'A') 'B' else 'A'
        val corrupted = part.substring(0, idx) + replacement + part.substring(idx + 1)

        val dec = QrCodec.decode(listOf(corrupted))
        // Either the base64 breaks or the CRC catches it; both are safe rejections.
        assertFalse("corrupted payload must not decode", dec.isOk)
    }

    @Test
    fun `foreign qr text is rejected`() {
        val dec = QrCodec.decode(listOf("https://example.com"))
        assertEquals(QrCodec.Reason.NOT_A_QR_PAYLOAD, dec.error)
    }

    @Test
    fun `a future magic is reported as unsupported version`() {
        val dec = QrCodec.decode(listOf("QVPN2:1/1:deadbeef:AAAA"))
        assertEquals(QrCodec.Reason.UNSUPPORTED_VERSION, dec.error)
    }

    @Test
    fun `garbage numbering is rejected`() {
        assertEquals(QrCodec.Reason.CORRUPT_PAYLOAD,
            QrCodec.decode(listOf("QVPN1:x/1:deadbeef:AA")).error)
        assertEquals(QrCodec.Reason.CORRUPT_PAYLOAD,
            QrCodec.decode(listOf("QVPN1:0/1:deadbeef:AA")).error)
        assertEquals(QrCodec.Reason.CORRUPT_PAYLOAD,
            QrCodec.decode(listOf("QVPN1:2/1:deadbeef:AA")).error)
    }

    @Test
    fun `payload without the crc separator is rejected`() {
        assertEquals(QrCodec.Reason.CORRUPT_PAYLOAD,
            QrCodec.decode(listOf("QVPN1:1/1:AAAA")).error)
    }

    @Test
    fun `empty input is rejected`() {
        assertEquals(QrCodec.Reason.NOT_A_QR_PAYLOAD, QrCodec.decode(emptyList()).error)
        assertEquals(QrCodec.Reason.NOT_A_QR_PAYLOAD, QrCodec.decode(listOf("   ")).error)
    }

    @Test
    fun `payload without a name separator is rejected`() {
        // Hand-craft a valid-looking code whose body has no newline.
        val body = "justconfignoline".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toString(16).padStart(8, '0')
        val payload = "QVPN1:1/1:$crc:" + QrCodec.b64encode(body)
        assertEquals(QrCodec.Reason.CORRUPT_PAYLOAD, QrCodec.decode(listOf(payload)).error)
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun `oversized payload is refused at encode time`() {
        val huge = "x".repeat(QrCodec.MAX_TOTAL_BYTES + 100)
        val res = QrCodec.encode("huge", huge)
        assertEquals(QrCodec.Reason.TOO_LARGE, (res as QrCodec.Result.Invalid).reason)
    }

    // ------------------------------------------------------------------ base64

    @Test
    fun `base64 round trips every length modulo three`() {
        for (n in 0..32) {
            val bytes = ByteArray(n) { (it * 7 + 3).toByte() }
            val enc = QrCodec.b64encode(bytes)
            val dec = QrCodec.b64decode(enc)
            assertNotNull("failed at n=$n", dec)
            assertArrayEquals(bytes, dec!!)
        }
    }

    @Test
    fun `base64url uses no padding and no plus or slash`() {
        val enc = QrCodec.b64encode(ByteArray(10) { (it + 250).toByte() })
        assertFalse(enc.contains('='))
        assertFalse(enc.contains('+'))
        assertFalse(enc.contains('/'))
    }

    @Test
    fun `base64 decode returns null for invalid characters`() {
        assertNull(QrCodec.b64decode("AAA*"))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) assertEquals("byte $i", expected[i], actual[i])
    }
}
