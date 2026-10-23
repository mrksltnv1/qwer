package com.qwer.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests encode the engine's real allow-list, verified against the built AAR:
 * wg-quick directives make `Config.parse` throw `BadConfigException`, and one bad line
 * kills the entire tunnel start.
 */
class ConfigNormalizerTest {

    private val base = """
        [Interface]
        PrivateKey = aBcD1234567890aBcD1234567890aBcD1234567890A=
        Address = 10.8.1.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = xYz9876543210xYz9876543210xYz9876543210X=
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun `clean config is untouched`() {
        val r = ConfigNormalizer.sanitize(base)
        assertFalse(r.hadChanges)
        assertEquals(emptyList<String>(), r.removedKeys)
        assertTrue(r.config.contains("PrivateKey"))
        assertTrue(r.config.contains("[Peer]"))
    }

    @Test
    fun `postup and postdown are removed`() {
        val cfg = base.replace(
            "DNS = 1.1.1.1",
            "DNS = 1.1.1.1\nPostUp = iptables -A FORWARD -j ACCEPT\nPostDown = iptables -D FORWARD -j ACCEPT",
        )
        val r = ConfigNormalizer.sanitize(cfg)
        assertEquals(listOf("postup", "postdown"), r.removedKeys)
        assertFalse("PostUp must be gone", r.config.contains("PostUp"))
        assertFalse("PostDown must be gone", r.config.contains("PostDown"))
        // Everything the engine needs must survive.
        assertTrue(r.config.contains("PrivateKey"))
        assertTrue(r.config.contains("AllowedIPs"))
    }

    @Test
    fun `table saveconfig fwmark are removed`() {
        val cfg = base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nTable = off\nSaveConfig = true\nFwMark = 0x1234")
        val r = ConfigNormalizer.sanitize(cfg)
        assertEquals(setOf("table", "saveconfig", "fwmark"), r.removedKeys.toSet())
    }

    @Test
    fun `case and spacing do not matter`() {
        val cfg = base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\npostup = echo hi\nPOSTDOWN=echo bye")
        val r = ConfigNormalizer.sanitize(cfg)
        assertEquals(setOf("postup", "postdown"), r.removedKeys.toSet())
    }

    @Test
    fun `protocol keys are never removed`() {
        val cfg = base.replace(
            "DNS = 1.1.1.1",
            "DNS = 1.1.1.1\nJc = 5\nJmin = 50\nJmax = 100\nS1 = 30\nS2 = 40\nS3 = 10\nS4 = 20\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\nI1 = <b 0x11>",
        )
        val r = ConfigNormalizer.sanitize(cfg)
        assertFalse(r.hadChanges)
        listOf("Jc", "S1", "S3", "H4", "I1").forEach {
            assertTrue("$it must survive", r.config.contains(it))
        }
    }

    @Test
    fun `peer section keys survive`() {
        val r = ConfigNormalizer.sanitize(base)
        assertTrue(r.config.contains("PublicKey"))
        assertTrue(r.config.contains("Endpoint"))
        assertTrue(r.config.contains("AllowedIPs"))
    }

    @Test
    fun `comment mentioning postup is not treated as a directive`() {
        val cfg = base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\n# PostUp = iptables -A FORWARD")
        val r = ConfigNormalizer.sanitize(cfg)
        assertFalse("comments must be preserved", r.hadChanges)
        assertTrue(r.config.contains("# PostUp = iptables -A FORWARD"))
    }

    @Test
    fun `trailing newline is normalised`() {
        val r = ConfigNormalizer.sanitize(base)
        assertTrue(r.config.endsWith("\n"))
        assertFalse(r.config.endsWith("\n\n\n"))
    }
}
