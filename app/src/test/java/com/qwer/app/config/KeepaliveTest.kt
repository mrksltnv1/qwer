package com.qwer.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keepalive injection.
 *
 * Without a persistent keepalive an idle WireGuard peer sends nothing, so NAT and
 * mobile carriers drop the mapping within 30-120 s. The tunnel then reports "up" while
 * every packet is black-holed — the reported "тоннель зависает" after a network switch.
 * These tests pin the exact text surgery, because a malformed config means the engine
 * refuses to start at all.
 */
class KeepaliveTest {

    private val base = """
        [Interface]
        PrivateKey = aaaa
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = bbbb
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun `keepalive is added when the peer has none`() {
        val out = ConfigNormalizer.ensureKeepalive(base)
        assertTrue(out.contains("PersistentKeepalive = 25"))
        // Still a valid config: the peer block keeps its original lines.
        assertTrue(out.contains("PublicKey = bbbb"))
        assertTrue(out.contains("AllowedIPs = 0.0.0.0/0"))
    }

    @Test
    fun `existing non-zero keepalive is preserved`() {
        val cfg = base.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 15")
        val out = ConfigNormalizer.ensureKeepalive(cfg)
        assertTrue(out.contains("PersistentKeepalive = 15"))
        assertFalse("must not add a second line", out.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `zero keepalive is replaced instead of kept`() {
        // 0 explicitly disables keepalive; that is exactly the broken state we fix.
        val cfg = base.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 0")
        val out = ConfigNormalizer.ensureKeepalive(cfg)
        assertTrue(out.contains("PersistentKeepalive = 25"))
        assertFalse(out.contains("PersistentKeepalive = 0"))
    }

    @Test
    fun `garbage keepalive value is replaced`() {
        val cfg = base.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = abc")
        val out = ConfigNormalizer.ensureKeepalive(cfg)
        assertTrue(out.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `config is unchanged when keepalive already correct`() {
        val cfg = base.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25")
        val once = ConfigNormalizer.ensureKeepalive(cfg)
        val twice = ConfigNormalizer.ensureKeepalive(once)
        assertEquals(once.trim(), twice.trim())
    }

    @Test
    fun `idempotent on a config that had no keepalive`() {
        val once = ConfigNormalizer.ensureKeepalive(base)
        val twice = ConfigNormalizer.ensureKeepalive(once)
        assertEquals(once.trim(), twice.trim())
        assertEquals(1, twice.lines().count { it.startsWith("PersistentKeepalive") })
    }

    @Test
    fun `every peer gets a keepalive`() {
        val multi = """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = bbbb
            AllowedIPs = 10.0.0.0/8

            [Peer]
            PublicKey = cccc
            AllowedIPs = 192.168.0.0/16
        """.trimIndent()
        val out = ConfigNormalizer.ensureKeepalive(multi)
        assertEquals(2, out.lines().count { it.startsWith("PersistentKeepalive") })
    }

    @Test
    fun `keepalive lands inside the peer section not the interface`() {
        val out = ConfigNormalizer.ensureKeepalive(base)
        val lines = out.lines()
        val peerIdx = lines.indexOfFirst { it.trim() == "[Peer]" }
        val kaIdx = lines.indexOfFirst { it.startsWith("PersistentKeepalive") }
        assertTrue("keepalive must follow [Peer]", kaIdx > peerIdx)
        // The [Interface] block must stay untouched.
        val ifaceIdx = lines.indexOfFirst { it.trim() == "[Interface]" }
        assertTrue(kaIdx > ifaceIdx)
    }

    @Test
    fun `amnezia obfuscation lines are preserved`() {
        val awg = """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            Jc = 4
            Jmin = 50
            S1 = 12
            H1 = 1234567

            [Peer]
            PublicKey = bbbb
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val out = ConfigNormalizer.ensureKeepalive(awg)
        assertTrue(out.contains("Jc = 4"))
        assertTrue(out.contains("H1 = 1234567"))
        assertTrue(out.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `zero seconds disables injection`() {
        val out = ConfigNormalizer.ensureKeepalive(base, seconds = 0)
        assertFalse(out.contains("PersistentKeepalive"))
    }

    @Test
    fun `config without peers is left alone`() {
        val ifaceOnly = "[Interface]\nPrivateKey = aaaa\nAddress = 10.0.0.2/32"
        val out = ConfigNormalizer.ensureKeepalive(ifaceOnly)
        assertFalse(out.contains("PersistentKeepalive"))
    }

    @Test
    fun `injection does not break sanitize`() {
        // The two passes run in sequence in TunnelEngine; both must be safe together.
        val dirty = base.replace("[Interface]", "[Interface]\nTable = off")
        val sanitized = ConfigNormalizer.sanitize(dirty).config
        val out = ConfigNormalizer.ensureKeepalive(sanitized)
        assertFalse("wg-quick key must be gone", out.contains("Table ="))
        assertTrue(out.contains("PersistentKeepalive = 25"))
    }
}
