package com.qwer.app.wdtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WDTT links arrive in two shapes and wrapped in surrounding text. The field report was
 * "QR for WDTT is not recognised" while WireGuard and AmneziaWG worked, so each of the
 * three divergences from the reference implementation is pinned here.
 */
class WdttLinkTest {

    private val modern =
        "wdtt://connect?v=1&host=195.58.51.229&dtls=56000&wg=56001&local=9000" +
            "&password=136900&hashes=abc123,def456"

    // ------------------------------------------------------------------ modern

    @Test
    fun `modern link parses all fields`() {
        val r = WdttLink.parse(modern)
        assertTrue(r is WdttParseResult.Valid)
        val l = (r as WdttParseResult.Valid).link
        assertEquals("195.58.51.229", l.host)
        assertEquals(56000, l.dtlsPort)
        assertEquals(56001, l.wgPort)
        assertEquals(9000, l.localPort)
        assertEquals("136900", l.password)
        assertEquals(listOf("abc123", "def456"), l.hashes)
        assertEquals("195.58.51.229:56000", l.peer)
    }

    @Test
    fun `link wrapped in a share message is found`() {
        // WDTT Plus wraps the link when sharing; indexOf finds it, startsWith did not.
        val wrapped = "Подключение к серверу:\nwdtt://connect?v=1&host=1.2.3.4&dtls=1" +
            "&wg=2&local=9000&password=pw&hashes=h1\nПриятной работы"
        val r = WdttLink.parse(wrapped)
        assertTrue(r is WdttParseResult.Valid)
        assertEquals("1.2.3.4", (r as WdttParseResult.Valid).link.host)
    }

    @Test
    fun `link with trailing punctuation from a chat is accepted`() {
        val r = WdttLink.parse("Вот ссылка: wdtt://connect?v=1&host=5.6.7.8&dtls=10" +
            "&wg=11&local=9000&password=pw&hashes=h1»")
        assertTrue(r is WdttParseResult.Valid)
        assertEquals("5.6.7.8", (r as WdttParseResult.Valid).link.host)
    }

    @Test
    fun `url encoded password is decoded`() {
        // WDTT Plus encodes with URLEncoder; '%3D' is '=', '%40' is '@'.
        val r = WdttLink.parse(
            "wdtt://connect?v=1&host=1.1.1.1&dtls=1&wg=2&local=9000" +
                "&password=a%3Db%40c&hashes=h1",
        )
        assertTrue(r is WdttParseResult.Valid)
        assertEquals("a=b@c", (r as WdttParseResult.Valid).link.password)
    }

    @Test
    fun `encoded plus sign becomes a space and survives`() {
        val r = WdttLink.parse(
            "wdtt://connect?v=1&host=1.1.1.1&dtls=1&wg=2&local=9000" +
                "&password=p%2Bw&hashes=h1",
        )
        assertEquals("p+w", (r as WdttParseResult.Valid).link.password)
    }

    @Test
    fun `unknown version is refused instead of guessed`() {
        val r = WdttLink.parse(
            "wdtt://connect?v=2&host=1.1.1.1&dtls=1&wg=2&local=9000&password=pw&hashes=h1",
        )
        assertEquals(
            WdttParseResult.Invalid::class.java,
            r::class.java,
        )
        assertTrue((r as WdttParseResult.Invalid).reason.contains("версией"))
    }

    @Test
    fun `missing password is refused`() {
        val r = WdttLink.parse("wdtt://connect?v=1&host=1.1.1.1&dtls=1&wg=2&local=9000&hashes=h1")
        assertTrue(r is WdttParseResult.Invalid)
    }

    @Test
    fun `missing host is refused`() {
        val r = WdttLink.parse("wdtt://connect?v=1&dtls=1&wg=2&local=9000&password=pw&hashes=h1")
        assertTrue(r is WdttParseResult.Invalid)
    }

    @Test
    fun `out of range local port is refused`() {
        val r = WdttLink.parse(
            "wdtt://connect?v=1&host=1.1.1.1&dtls=1&wg=2&local=80&password=pw&hashes=h1",
        )
        assertTrue(r is WdttParseResult.Invalid)
    }

    // ------------------------------------------------------------------ legacy

    @Test
    fun `legacy six field link parses`() {
        // host:dtls:wg:local:password:hashes — still emitted by older servers.
        val r = WdttLink.parse("wdtt://1.2.3.4:56000:56001:9000:mypass:hash1,hash2")
        assertTrue(r is WdttParseResult.Valid)
        val l = (r as WdttParseResult.Valid).link
        assertEquals("1.2.3.4", l.host)
        assertEquals(56000, l.dtlsPort)
        assertEquals(56001, l.wgPort)
        assertEquals(9000, l.localPort)
        assertEquals("mypass", l.password)
        assertEquals(listOf("hash1", "hash2"), l.hashes)
    }

    @Test
    fun `legacy link with wrong field count explains itself`() {
        val r = WdttLink.parse("wdtt://1.2.3.4:56000:56001:9000:pass")
        assertTrue(r is WdttParseResult.Invalid)
        assertTrue((r as WdttParseResult.Invalid).reason.contains("6 полей"))
    }

    // ------------------------------------------------------------------ rejections

    @Test
    fun `text without a wdtt link is refused`() {
        val r = WdttLink.parse("[Interface]\nPrivateKey = x")
        assertTrue(r is WdttParseResult.Invalid)
        assertTrue((r as WdttParseResult.Invalid).reason.contains("wdtt://"))
    }

    @Test
    fun `empty input is refused`() {
        assertTrue(WdttLink.parse("   ") is WdttParseResult.Invalid)
    }

    @Test
    fun `extractLink returns the bare link`() {
        val link = WdttLink.extractLink("text wdtt://1.2.3.4:1:2:9000:p:h more text")
        assertEquals("wdtt://1.2.3.4:1:2:9000:p:h", link)
    }

    @Test
    fun `extractLink returns null when absent`() {
        assertEquals(null, WdttLink.extractLink("no link here"))
    }
}
