package com.qwer.app.config

import com.qwer.app.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImporterTest {

    // Real-shaped 44-char base64 keys (32 bytes). Test-only, never used for a real tunnel.
    private val priv = "aBcD1234567890aBcD1234567890aBcD1234567890A="
    private val pub = "xYz9876543210xYz9876543210xYz9876543210X="

    private fun wg(extraInterface: String = "") = """
        [Interface]
        PrivateKey = $priv
        $extraInterface
        Address = 10.66.66.2/32
        DNS = 1.1.1.1, 8.8.8.8

        [Peer]
        PublicKey = $pub
        Endpoint = 195.58.51.229:56001
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `valid wireguard config is parsed`() {
        val r = ConfigImporter.validate(wg())
        assertTrue("expected Valid, got $r", r is ImportResult.Valid)
        r as ImportResult.Valid
        assertEquals(Protocol.WIREGUARD, r.protocol)
        assertEquals(1, r.peerCount)
        assertEquals("195.58.51.229:56001", r.endpoint)
        assertEquals(listOf("10.66.66.2/32"), r.interfaceAddresses)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), r.dnsServers)
        assertEquals(2, r.allowedIps.size)
    }

    @Test
    fun `amneziawg obfuscation keys classify as AWG`() {
        val awg = wg(
            extraInterface = """
                Jc = 4
                Jmin = 40
                Jmax = 70
                S1 = 50
                S2 = 60
                H1 = 1
                H2 = 2
                H3 = 3
                H4 = 4
            """.trimIndent(),
        )
        val r = ConfigImporter.validate(awg)
        assertTrue(r is ImportResult.Valid)
        assertEquals(Protocol.AMNEZIAWG, (r as ImportResult.Valid).protocol)
    }

    @Test
    fun `empty config is rejected`() {
        val r = ConfigImporter.validate("")
        assertTrue(r is ImportResult.Invalid)
    }

    @Test
    fun `missing peer section is rejected`() {
        val noPeer = """
            [Interface]
            PrivateKey = $priv
            Address = 10.0.0.2/32
        """.trimIndent()
        val r = ConfigImporter.validate(noPeer)
        assertTrue(r is ImportResult.Invalid)
        assertTrue((r as ImportResult.Invalid).reason.contains("Peer"))
    }

    @Test
    fun `missing private key is rejected`() {
        val bad = """
            [Interface]
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = $pub
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val r = ConfigImporter.validate(bad)
        assertTrue(r is ImportResult.Invalid)
    }

    @Test
    fun `malformed key is rejected`() {
        val bad = """
            [Interface]
            PrivateKey = not-a-real-key
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = $pub
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val r = ConfigImporter.validate(bad)
        assertTrue(r is ImportResult.Invalid)
    }

    @Test
    fun `oversized config is rejected`() {
        val huge = wg() + "\n# " + "x".repeat(ConfigImporter.MAX_CONFIG_BYTES)
        val r = ConfigImporter.validate(huge)
        assertTrue(r is ImportResult.Invalid)
    }

    @Test
    fun `control characters are rejected`() {
        val bad = wg() + "\u0000\u0001"
        val r = ConfigImporter.validate(bad)
        assertTrue(r is ImportResult.Invalid)
    }

    @Test
    fun `comments and blank lines are tolerated`() {
        val commented = "# my profile\n\n" + wg() + "\n# trailing\n"
        val r = ConfigImporter.validate(commented)
        assertTrue(r is ImportResult.Valid)
    }

    @Test
    fun `multiple peers are counted`() {
        val two = wg() + "\n\n[Peer]\nPublicKey = $pub\nAllowedIPs = 10.0.0.0/24\n"
        val r = ConfigImporter.validate(two)
        assertTrue(r is ImportResult.Valid)
        assertEquals(2, (r as ImportResult.Valid).peerCount)
    }

    @Test
    fun `missing address is rejected`() {
        val bad = """
            [Interface]
            PrivateKey = $priv

            [Peer]
            PublicKey = $pub
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val r = ConfigImporter.validate(bad)
        assertTrue(r is ImportResult.Invalid)
    }
}
