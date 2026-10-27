package com.qwer.app.imports

import com.qwer.app.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * Multi-config bundle parsing.
 *
 * The server is not the authority on what our engine can parse, so the protocol is
 * derived locally from the payload. A wrong label would send an AmneziaWG config down
 * the WireGuard path, and the engine would reject it at connect time — long after the
 * import looked successful.
 *
 * Robolectric is required because [org.json] is a stub on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class BundleImporterTest {

    private fun key(seed: Byte): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { seed })

    private val wgConfig = """
        [Interface]
        PrivateKey = ${key(1)}
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = ${key(2)}
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    private val awgConfig = """
        [Interface]
        PrivateKey = ${key(3)}
        Address = 10.0.0.3/32
        Jc = 4
        Jmin = 50
        H1 = 1234567

        [Peer]
        PublicKey = ${key(4)}
        Endpoint = 5.6.7.8:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    private val wdttLink =
        "wdtt://connect?v=1&host=195.58.51.229&dtls=56000&wg=56001&local=9000" +
            "&password=***&hashes=abc"

    private fun bundle(vararg items: Pair<String, String>): String {
        val arr = items.joinToString(",") { (name, cfg) ->
            val escName = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val escCfg = cfg.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n")
            """{"name":"$escName","config":"$escCfg"}"""
        }
        return """{"version":1,"expires_at":0,"profiles":[$arr]}"""
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `all three protocols are classified from payload shape`() {
        val body = bundle(
            "Мой WG" to wgConfig,
            "Мой AWG" to awgConfig,
            "Резерв" to wdttLink,
        )
        val r = BundleImporter.parse(body)
        assertTrue("expected Ok, got $r", r is BundleImporter.Result.Ok)
        val entries = (r as BundleImporter.Result.Ok).entries
        assertEquals(3, entries.size)
        assertEquals(Protocol.WIREGUARD, entries[0].protocol)
        assertEquals(Protocol.AMNEZIAWG, entries[1].protocol)
        assertEquals(Protocol.WDTT_PLUS, entries[2].protocol)
        assertEquals("Мой WG", entries[0].name)
    }

    @Test
    fun `protocol is derived locally even when the server lies`() {
        // A server that labels an AWG config as WIREGUARD must not be trusted.
        val body = bundle("mislabeled" to awgConfig)
        val entries = (BundleImporter.parse(body) as BundleImporter.Result.Ok).entries
        assertEquals(Protocol.AMNEZIAWG, entries[0].protocol)
    }

    @Test
    fun `wdtt link inside surrounding text is still recognised`() {
        val body = bundle("wdtt" to "Подключение: $wdttLink")
        val entries = (BundleImporter.parse(body) as BundleImporter.Result.Ok).entries
        assertEquals(Protocol.WDTT_PLUS, entries[0].protocol)
        // The stored config must be the bare link, not the surrounding prose.
        assertEquals(wdttLink, entries[0].config)
    }

    @Test
    fun `missing name gets a generated one`() {
        val body = """{"version":1,"profiles":[{"config":"${wgConfig.replace("\n", "\\n")}"}]}"""
        val entries = (BundleImporter.parse(body) as BundleImporter.Result.Ok).entries
        assertTrue(entries[0].name.isNotBlank())
        assertTrue(entries[0].name.startsWith("WG"))
    }

    // ------------------------------------------------------------------ rejections

    @Test
    fun `expired link is refused`() {
        val body = """{"version":1,"expires_at":1000000000,"profiles":[]}"""
        val r = BundleImporter.parse(body)
        assertTrue(r is BundleImporter.Result.Failed)
        assertTrue((r as BundleImporter.Result.Failed).reason.contains("истёк"))
    }

    @Test
    fun `future expiry in seconds is accepted`() {
        val future = System.currentTimeMillis() / 1000 + 3600
        val body = """{"version":1,"expires_at":$future,"profiles":[
            {"name":"a","config":"${wgConfig.replace("\n", "\\n")}"}
        ]}"""
        assertTrue(BundleImporter.parse(body) is BundleImporter.Result.Ok)
    }

    @Test
    fun `unknown version is refused instead of guessed`() {
        val body = """{"version":2,"profiles":[]}"""
        val r = BundleImporter.parse(body)
        assertTrue(r is BundleImporter.Result.Failed)
        assertTrue((r as BundleImporter.Result.Failed).reason.contains("версией"))
    }

    @Test
    fun `garbage body is refused`() {
        assertTrue(BundleImporter.parse("<html>404</html>") is BundleImporter.Result.Failed)
    }

    @Test
    fun `empty profile list is reported as empty not ok`() {
        val r = BundleImporter.parse("""{"version":1,"profiles":[]}""")
        assertTrue(r is BundleImporter.Result.Empty)
    }

    @Test
    fun `broken configs are skipped and reported`() {
        val body = bundle(
            "good" to wgConfig,
            "bad" to "[Interface]\nPrivateKey = notakey\n",
        )
        val r = BundleImporter.parse(body)
        assertTrue(r is BundleImporter.Result.Ok)
        assertEquals(1, (r as BundleImporter.Result.Ok).entries.size)
    }

    @Test
    fun `all configs broken yields empty with reasons`() {
        val body = bundle("bad" to "hello world")
        val r = BundleImporter.parse(body)
        assertTrue("expected Empty, got $r", r is BundleImporter.Result.Empty)
        assertTrue((r as BundleImporter.Result.Empty).skipped.isNotEmpty())
    }

    @Test
    fun `wdtt client card is skipped with an actionable reason`() {
        val card = """{"format":"wdtt-plus-client","version":1,"password":"abcdefgh","vk_hash":"x","expires_at":0}"""
        val body = bundle("card" to card)
        val r = BundleImporter.parse(body)
        assertTrue(r is BundleImporter.Result.Empty)
        val skipped = (r as BundleImporter.Result.Empty).skipped.joinToString(" ")
        assertTrue("reason should mention the address", skipped.contains("адрес"))
    }

    @Test
    fun `more than fifty profiles is refused`() {
        val items = (1..51).map { "p$it" to wgConfig }
        val r = BundleImporter.parse(bundle(*items.toTypedArray()))
        assertTrue(r is BundleImporter.Result.Failed)
    }

    // ------------------------------------------------------------------ transport

    @Test
    fun `plain http is refused before any connection`() {
        val r = BundleImporter.fetch("http://bot.example.com/import", "tok")
        assertTrue(r is BundleImporter.Result.Failed)
        assertTrue((r as BundleImporter.Result.Failed).reason.contains("HTTPS"))
    }

    @Test
    fun `empty base url is refused`() {
        val r = BundleImporter.fetch("", "tok")
        assertTrue(r is BundleImporter.Result.Failed)
    }

    @Test
    fun `blank token is refused`() {
        val r = BundleImporter.fetch("https://bot.example.com/import", "  ")
        assertTrue(r is BundleImporter.Result.Failed)
    }
}
