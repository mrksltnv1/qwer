package com.qwer.app.core

import com.qwer.app.config.ConfigNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end proof that per-app routing reaches the ENGINE, not just our own model.
 *
 * WHY THIS TEST EXISTS
 * --------------------
 * "Маршрутизация не работает" was reported three times, and each time the bug was in a
 * different layer: visibility (no `<queries>`), semantics (own package merged into the
 * allow-list), and wiring (the WDTT path ignored the profile entirely). Unit tests on
 * [AppRouting] could not catch any of those, because they all passed while the feature
 * was dead.
 *
 * So this test skips our model as much as possible: it takes the real config text, runs
 * it through the same [com.qwer.app.config.ConfigNormalizer] the engine uses, and then
 * parses it with the ENGINE's own `org.amnezia.awg.config.Config.parse`. If the package
 * names do not come back out of the engine's `Interface`, the feature does not work —
 * no matter what our classes believe.
 */
@RunWith(RobolectricTestRunner::class)
class AppRoutingEngineTest {

    /**
     * WireGuard keys are exactly 32 bytes. Hand-written base64 strings are the reason
     * the first version of this test failed with KeyFormatException instead of testing
     * routing — so both keys are derived here, not typed.
     */
    private fun key(seed: Byte): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { seed })

    private val privateKey = key(1)
    private val publicKey = key(2)

    private val wireGuardConfig = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = $publicKey
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    /** Shape of the config libclient.so publishes (endpoint is the local uplink). */
    private val wdttConfig = """
        [Interface]
        PrivateKey = $privateKey
        Address = 10.66.66.10/32
        DNS = 1.1.1.1, 77.88.8.8
        MTU = 1280

        [Peer]
        PublicKey = $publicKey
        Endpoint = 127.0.0.1:9000
        AllowedIPs = 0.0.0.0/0
        PersistentKeepalive = 25
    """.trimIndent()

    /**
     * The whole pipeline: routing lines → injected config → normalizer → engine parser.
     */
    private fun routingAsSeenByEngine(
        config: String,
        routing: AppRouting,
        alwaysExcluded: Set<String> = emptySet(),
    ): Pair<Set<String>, Set<String>> {
        val lines = AppRouting.configLines(routing, alwaysExcluded)
        val injected = if (lines.isEmpty()) config else RoutingInjector.apply(config, lines)
        val normalized = ConfigNormalizer.sanitize(injected).config

        val parsed = org.amnezia.awg.config.Config.parse(
            java.io.BufferedReader(java.io.StringReader(normalized)),
        )
        return parsed.`interface`.includedApplications to parsed.`interface`.excludedApplications
    }

    // ------------------------------------------------------------------ plain WG

    @Test
    fun `engine sees no routing when the mode is all`() {
        val (included, excluded) = routingAsSeenByEngine(wireGuardConfig, AppRouting.All)
        assertTrue("engine must not invent an allow-list", included.isEmpty())
        assertTrue(excluded.isEmpty())
    }

    @Test
    fun `engine receives excluded packages in except mode`() {
        val (included, excluded) = routingAsSeenByEngine(
            wireGuardConfig,
            AppRouting.Except(setOf("ru.sberbankmobile", "ru.ozon.app.android")),
        )
        assertTrue("allow-list must stay empty", included.isEmpty())
        assertEquals(setOf("ru.sberbankmobile", "ru.ozon.app.android"), excluded)
    }

    @Test
    fun `engine receives allowed packages in only mode`() {
        val (included, excluded) = routingAsSeenByEngine(
            wireGuardConfig,
            AppRouting.Only(setOf("org.telegram.messenger")),
        )
        assertEquals(setOf("org.telegram.messenger"), included)
        assertTrue("mixing both keys makes the engine throw", excluded.isEmpty())
    }

    @Test
    fun `engine keeps our own package out of the allow-list`() {
        // The WDTT self-loop guard: our UID must never be inside the tunnel.
        val (included, _) = routingAsSeenByEngine(
            wireGuardConfig,
            AppRouting.Only(setOf("org.telegram.messenger", "com.qwer.app")),
            alwaysExcluded = setOf("com.qwer.app"),
        )
        assertEquals(setOf("org.telegram.messenger"), included)
        assertFalse(included.contains("com.qwer.app"))
    }

    // ------------------------------------------------------------------ WDTT

    @Test
    fun `wdtt config with routing still parses and carries the rule`() {
        // WDTT's hosted config is a plain WireGuard config pointing at 127.0.0.1. The
        // WDTT branch used to call withSelfExclusion() only, so nothing reached here.
        val (_, excluded) = routingAsSeenByEngine(
            wdttConfig,
            AppRouting.Except(setOf("ru.sberbankmobile")),
            alwaysExcluded = setOf("com.qwer.app"),
        )
        assertEquals(setOf("com.qwer.app", "ru.sberbankmobile"), excluded)
    }

    @Test
    fun `wdtt config in only mode still excludes our package`() {
        val (included, excluded) = routingAsSeenByEngine(
            wdttConfig,
            AppRouting.Only(setOf("org.telegram.messenger")),
            alwaysExcluded = setOf("com.qwer.app"),
        )
        // Allow-list semantics already keep our package OUTSIDE; it must not appear in
        // the allow-list, and the engine forbids both keys at once.
        assertEquals(setOf("org.telegram.messenger"), included)
        assertTrue(excluded.isEmpty())
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun `routing lines survive the normalizer`() {
        // ConfigNormalizer removes wg-quick directives. If it ever started removing
        // application keys, routing would die silently — this pins the contract.
        val injected = RoutingInjector.apply(
            wireGuardConfig,
            listOf("ExcludedApplications = a.b"),
        )
        val normalized = ConfigNormalizer.sanitize(injected)
        assertTrue(normalized.config.contains("ExcludedApplications = a.b"))
        assertTrue(normalized.removedKeys.isEmpty())
    }

    @Test
    fun `routing is applied to the config the engine gets not to the stored one`() {
        // The stored config keeps the user's original bytes; only the copy handed to
        // up() carries the routing lines. Otherwise a mode change would be permanent.
        val routed = RoutingInjector.apply(
            wireGuardConfig,
            AppRouting.configLines(AppRouting.Except(setOf("a.b"))),
        )
        assertTrue(routed.contains("ExcludedApplications"))
        assertFalse(
            "the source config must stay untouched",
            wireGuardConfig.contains("ExcludedApplications"),
        )
    }

    @Test
    fun `changing the mode twice leaves exactly one routing key`() {
        val once = RoutingInjector.apply(
            wireGuardConfig,
            AppRouting.configLines(AppRouting.Except(setOf("a.b"))),
        )
        val twice = RoutingInjector.apply(
            once,
            AppRouting.configLines(AppRouting.Except(setOf("c.d"))),
        )
        assertEquals(1, twice.lines().count { it.startsWith("ExcludedApplications") })
        assertTrue(twice.contains("c.d"))
        assertFalse("the old rule must be replaced", twice.contains("a.b"))
    }
}
