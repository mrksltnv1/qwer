package com.qwer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Routing is config-string surgery plus a persistence format. Both are easy to get subtly
 * wrong in a way that only shows up as "the tunnel carries no traffic" on a real device,
 * so every rule is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class AppRoutingTest {

    private val base = """
        [Interface]
        PrivateKey = aaaa
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = bbbb
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    // ------------------------------------------------------------------ configLines

    @Test
    fun `all mode emits nothing so existing profiles stay byte-identical`() {
        assertTrue(AppRouting.configLines(AppRouting.All).isEmpty())
    }

    @Test
    fun `all mode with forced exclusions emits only excluded`() {
        val lines = AppRouting.configLines(AppRouting.All, setOf("com.qwer.app"))
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("ExcludedApplications = "))
        assertTrue(lines[0].contains("com.qwer.app"))
    }

    @Test
    fun `only mode uses IncludedApplications`() {
        val lines = AppRouting.configLines(AppRouting.Only(setOf("org.telegram.messenger")))
        assertEquals(listOf("IncludedApplications = org.telegram.messenger"), lines)
    }

    @Test
    fun `only mode with an empty set falls back to a full tunnel`() {
        // An empty allow-list would route nothing into the tunnel and break connectivity.
        val lines = AppRouting.configLines(AppRouting.Only(emptySet()))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `except mode uses ExcludedApplications`() {
        val lines = AppRouting.configLines(AppRouting.Except(setOf("com.bank.app")))
        assertEquals(listOf("ExcludedApplications = com.bank.app"), lines)
    }

    @Test
    fun `never emits both keys at once`() {
        // VpnService.Builder throws if allowed and disallowed apps are mixed.
        val routing = AppRouting.Except(setOf("a.b", "c.d"))
        val lines = AppRouting.configLines(routing, setOf("com.qwer.app"))
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("ExcludedApplications"))
        assertFalse(lines.any { it.startsWith("IncludedApplications") })
    }

    @Test
    fun `packages are sorted and blanks dropped`() {
        val lines = AppRouting.configLines(
            AppRouting.Except(setOf("z.app", " ", "a.app")),
        )
        assertEquals("ExcludedApplications = a.app, z.app", lines[0])
    }

    @Test
    fun `forced exclusion survives in only mode`() {
        val lines = AppRouting.configLines(
            AppRouting.Only(setOf("org.telegram.messenger")),
            setOf("com.qwer.app"),
        )
        assertTrue(lines[0].contains("com.qwer.app"))
        assertTrue(lines[0].contains("org.telegram.messenger"))
    }

    // ------------------------------------------------------------------ injector

    @Test
    fun `apply inserts routing inside the interface section`() {
        val out = RoutingInjector.apply(base, listOf("ExcludedApplications = com.bank.app"))
        val interfaceIdx = out.lines().indexOfFirst { it.trim() == "[Interface]" }
        val peerIdx = out.lines().indexOfFirst { it.trim() == "[Peer]" }
        val routingIdx = out.lines().indexOfFirst { it.startsWith("ExcludedApplications") }
        assertTrue("routing must follow [Interface]", routingIdx > interfaceIdx)
        assertTrue("routing must precede [Peer]", routingIdx < peerIdx)
    }

    @Test
    fun `apply replaces a previous routing key instead of duplicating it`() {
        val withOld = base.replace(
            "Address = 10.0.0.2/32",
            "Address = 10.0.0.2/32\nExcludedApplications = old.app",
        )
        val out = RoutingInjector.apply(withOld, listOf("ExcludedApplications = new.app"))
        assertEquals(1, out.lines().count { it.startsWith("ExcludedApplications") })
        assertTrue(out.contains("new.app"))
        assertFalse(out.contains("old.app"))
    }

    @Test
    fun `apply with empty lines strips existing routing`() {
        val withOld = base.replace(
            "Address = 10.0.0.2/32",
            "Address = 10.0.0.2/32\nExcludedApplications = old.app",
        )
        val out = RoutingInjector.apply(withOld, emptyList())
        assertFalse(out.contains("ExcludedApplications"))
        assertTrue(out.contains("PrivateKey"))
    }

    @Test
    fun `apply preserves every other line and its order`() {
        val out = RoutingInjector.apply(base, listOf("IncludedApplications = a.b"))
        val original = base.lines().filter { it.isNotBlank() }
        val produced = out.lines().filter { it.isNotBlank() }
        assertEquals(original.size + 1, produced.size)
        assertTrue(produced.contains("[Peer]"))
        assertTrue(produced.contains("AllowedIPs = 0.0.0.0/0"))
    }

    @Test
    fun `apply is idempotent`() {
        val once = RoutingInjector.apply(base, listOf("ExcludedApplications = a.b"))
        val twice = RoutingInjector.apply(once, listOf("ExcludedApplications = a.b"))
        assertEquals(once.trim(), twice.trim())
    }

    @Test
    fun `apply leaves a config without an interface untouched`() {
        val broken = "[Peer]\nPublicKey = xx\n"
        assertEquals(broken, RoutingInjector.apply(broken, listOf("ExcludedApplications = a.b")))
    }

    @Test
    fun `declaredPackages reads back what was written`() {
        val out = RoutingInjector.apply(base, listOf("ExcludedApplications = a.b, c.d"))
        val declared = RoutingInjector.declaredPackages(out)
        assertEquals(listOf("a.b", "c.d"), declared["excludedapplications"])
    }

    @Test
    fun `declaredPackages is case insensitive on the key`() {
        val cfg = "[Interface]\nexcludedapplications = x.y\n"
        assertEquals(listOf("x.y"), RoutingInjector.declaredPackages(cfg)["excludedapplications"])
    }

    // ------------------------------------------------------------------ persistence

    @Test
    fun `json round trip for only mode`() {
        val o = org.json.JSONObject()
        RoutingInjector.toJson(AppRouting.Only(setOf("a.b", "c.d")), o)
        val back = RoutingInjector.fromJson(o)
        assertTrue(back is AppRouting.Only)
        assertEquals(setOf("a.b", "c.d"), (back as AppRouting.Only).packages)
    }

    @Test
    fun `json round trip for except mode`() {
        val o = org.json.JSONObject()
        RoutingInjector.toJson(AppRouting.Except(setOf("x.y")), o)
        assertEquals(setOf("x.y"), (RoutingInjector.fromJson(o) as AppRouting.Except).packages)
    }

    @Test
    fun `missing json keys mean all`() {
        // Profiles written by older versions have no routing fields at all.
        assertEquals(AppRouting.All, RoutingInjector.fromJson(org.json.JSONObject()))
    }

    @Test
    fun `corrupt json degrades to all instead of crashing`() {
        val o = org.json.JSONObject().apply {
            put("routingMode", "GARBAGE")
            put("routingPackages", org.json.JSONArray(listOf("a.b")))
        }
        assertEquals(AppRouting.All, RoutingInjector.fromJson(o))
    }

    @Test
    fun `empty package list degrades to all`() {
        val o = org.json.JSONObject().apply {
            put("routingMode", "ONLY")
            put("routingPackages", org.json.JSONArray())
        }
        assertEquals(AppRouting.All, RoutingInjector.fromJson(o))
    }

    // ------------------------------------------------------------------ ui helpers

    @Test
    fun `selectedCount reports what the ui should show`() {
        assertEquals(0, AppRouting.selectedCount(AppRouting.All))
        assertEquals(2, AppRouting.selectedCount(AppRouting.Only(setOf("a.b", "c.d"))))
        assertEquals(1, AppRouting.selectedCount(AppRouting.Except(setOf("a.b"))))
    }

    @Test
    fun `isUnsupportedEmpty flags the degenerate only mode`() {
        assertTrue(AppRouting.isUnsupportedEmpty(AppRouting.Only(emptySet())))
        assertFalse(AppRouting.isUnsupportedEmpty(AppRouting.Only(setOf("a.b"))))
        assertFalse(AppRouting.isUnsupportedEmpty(AppRouting.All))
    }
}
