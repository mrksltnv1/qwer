package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reason exposed by the engine must produce a usable message.
 *
 * The engine AAR defines exactly eight values in BackendException.Reason; this test
 * pins that all of them are handled and that an unknown one is never swallowed.
 */
class EngineErrorMapperTest {

    @Test
    fun `all engine reason codes are known`() {
        // Guards against an AAR upgrade adding a reason that silently degrades.
        assertEquals(8, EngineErrorMapper.KNOWN_REASON_CODES.size)
    }

    @Test
    fun `every known reason maps to a non-empty failure with a stage`() {
        for (code in EngineErrorMapper.KNOWN_REASON_CODES) {
            val f = EngineErrorMapper.fromReasonCode(code)
            assertEquals("code round-trips for $code", code, f.code)
            assertTrue("title for $code", f.title.isNotBlank())
            assertTrue("description for $code", f.description.isNotBlank())
            assertTrue("stage for $code", f.stage != ConnectionStage.UNKNOWN)
            assertTrue("message for $code", f.userMessage().isNotBlank())
        }
    }

    @Test
    fun `permission failure carries the permission stage and advice`() {
        val f = EngineErrorMapper.fromReasonCode("VPN_NOT_AUTHORIZED")
        assertEquals(ConnectionStage.PERMISSION, f.stage)
        assertNotNull("advice needed", f.recommendation)
        assertTrue(f.userMessage().contains("разрешени", ignoreCase = true))
    }

    @Test
    fun `missing config points at the profile stage`() {
        val f = EngineErrorMapper.fromReasonCode("TUNNEL_MISSING_CONFIG")
        assertEquals(ConnectionStage.PROFILE, f.stage)
        assertNotNull(f.recommendation)
    }

    @Test
    fun `dns failure shows the host that could not be resolved`() {
        val f = EngineErrorMapper.fromReasonCode(
            reasonCode = "DNS_RESOLUTION_FAILURE",
            formatArg = "vpn.example.com",
        )
        assertEquals(ConnectionStage.DNS, f.stage)
        assertTrue("host must appear", f.description.contains("vpn.example.com"))
        assertTrue("technical keeps the host", f.technical!!.contains("vpn.example.com"))
    }

    @Test
    fun `dns failure without a host still explains itself`() {
        val f = EngineErrorMapper.fromReasonCode("DNS_RESOLUTION_FAILURE")
        assertEquals(ConnectionStage.DNS, f.stage)
        assertTrue(f.description.isNotBlank())
        assertNull("no fabricated host", f.technical)
    }

    @Test
    fun `go activation code is preserved for support`() {
        val f = EngineErrorMapper.fromReasonCode(
            reasonCode = "GO_ACTIVATION_ERROR_CODE",
            formatArg = "-7",
        )
        assertEquals(ConnectionStage.CONNECT, f.stage)
        assertTrue("code visible in technical", f.technical!!.contains("-7"))
        assertTrue(f.description.contains("-7"))
    }

    @Test
    fun `awg quick config error names the obfuscation parameters`() {
        val f = EngineErrorMapper.fromReasonCode("AWG_QUICK_CONFIG_ERROR_CODE")
        assertEquals(ConnectionStage.ENGINE_PREP, f.stage)
        assertTrue("must mention obfuscation", f.recommendation!!.contains("обфускации", ignoreCase = true))
    }

    @Test
    fun `unknown kernel module suggests plain wireguard`() {
        val f = EngineErrorMapper.fromReasonCode("UNKNOWN_KERNEL_MODULE_NAME")
        assertEquals(ConnectionStage.ENGINE_PREP, f.stage)
        assertTrue(f.recommendation!!.contains("WireGuard", ignoreCase = true))
    }

    @Test
    fun `vpn service and tun failures have distinct stages`() {
        val service = EngineErrorMapper.fromReasonCode("UNABLE_TO_START_VPN")
        val tun = EngineErrorMapper.fromReasonCode("TUN_CREATION_ERROR")
        assertEquals(ConnectionStage.VPN_SERVICE, service.stage)
        assertEquals(ConnectionStage.TUNNEL_INIT, tun.stage)
        assertTrue("titles must differ", service.title != tun.title)
    }

    @Test
    fun `unknown reason is preserved, never swallowed`() {
        val f = EngineErrorMapper.fromReasonCode(
            reasonCode = "SOME_FUTURE_REASON",
            rawMessage = "engine said something new",
        )
        assertEquals("SOME_FUTURE_REASON", f.code)
        assertEquals(ConnectionStage.UNKNOWN, f.stage)
        assertTrue("raw message kept", f.technical!!.contains("engine said something new"))
        assertTrue("advice points at diagnostics", f.recommendation!!.contains("Диагностик", ignoreCase = true))
    }

    @Test
    fun `null reason with a message is still classified`() {
        val f = EngineErrorMapper.fromReasonCode(null, "BadConfig: unknown key")
        assertEquals("BAD_CONFIG", f.code)
        assertEquals(ConnectionStage.ENGINE_PREP, f.stage)
        assertTrue(f.technical!!.contains("BadConfig"))
    }

    @Test
    fun `null reason and null message still gives a usable message`() {
        val f = EngineErrorMapper.fromReasonCode(null, null)
        assertEquals("UNKNOWN", f.code)
        assertTrue(f.userMessage().isNotBlank())
        assertTrue("must not be an empty technical dump", f.userMessage().length > 10)
    }

    @Test
    fun `message mentioning a known reason is classified, not treated as unknown`() {
        val f = EngineErrorMapper.fromReasonCode(null, "VPN_NOT_AUTHORIZED in backend")
        assertEquals(ConnectionStage.PERMISSION, f.stage)
    }

    @Test
    fun `blank reason code falls back to unknown`() {
        val f = EngineErrorMapper.fromReasonCode("   ")
        assertEquals("UNKNOWN", f.code)
    }

    @Test
    fun `user message contains both headline and advice`() {
        val f = EngineErrorMapper.fromReasonCode("TUN_CREATION_ERROR")
        val msg = f.userMessage()
        assertTrue("starts with title", msg.startsWith(f.title))
        assertTrue("includes advice", msg.contains(f.recommendation!!))
    }

    @Test
    fun `technical detail never exceeds a screenshot-friendly length`() {
        val longMessage = "x".repeat(1000)
        val f = EngineErrorMapper.fromReasonCode("WEIRD", longMessage)
        assertTrue("truncated", f.technical!!.length <= 200)
    }
}
