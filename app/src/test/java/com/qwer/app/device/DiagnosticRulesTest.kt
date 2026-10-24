package com.qwer.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules are pure, so every branch that matters is covered here without an emulator.
 *
 * The guiding principle under test: "unknown" must never be reported as OK or ERROR.
 */
class DiagnosticRulesTest {

    private fun snapshot(
        sdkInt: Int? = 34,
        androidRelease: String? = "14",
        manufacturer: String? = "realme",
        model: String? = "GT 6",
        supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        primaryAbi: String? = "arm64-v8a",
        pageSizeBytes: Long? = 4096,
        bundledNativeLibs: Set<String> = setOf("libwg-go.so", "libwg.so"),
        wgLibPresent: Boolean? = true,
        wdttClientPresent: Boolean? = false,
        vpnPermissionGranted: Boolean? = true,
        vpnTransportActive: Boolean? = false,
        notificationsEnabled: Boolean? = true,
        backgroundRestricted: Boolean? = false,
        ignoringBatteryOptimizations: Boolean? = true,
        freeMemoryBytes: Long? = 512L * 1024 * 1024,
        totalMemoryBytes: Long? = 8L * 1024 * 1024 * 1024,
        freeStorageBytes: Long? = 4L * 1024 * 1024 * 1024,
        networkAvailable: Boolean? = true,
        networkTransports: Set<String> = setOf("Wi-Fi"),
        dnsResolved: Boolean? = true,
        dnsResolvedHost: String? = "one.one.one.one",
        filesDirWritable: Boolean? = true,
    ) = DeviceSnapshot(
        sdkInt, androidRelease, manufacturer, model, supportedAbis, primaryAbi,
        pageSizeBytes, bundledNativeLibs, wgLibPresent, wdttClientPresent,
        vpnPermissionGranted, vpnTransportActive, notificationsEnabled,
        backgroundRestricted, ignoringBatteryOptimizations, freeMemoryBytes,
        totalMemoryBytes, freeStorageBytes, networkAvailable, networkTransports,
        dnsResolved, dnsResolvedHost, filesDirWritable,
    )

    private fun find(checks: List<DiagnosticCheck>, id: String) =
        checks.firstOrNull { it.id == id } ?: error("check '$id' missing")

    @Test
    fun `healthy device yields no errors`() {
        val checks = DiagnosticRules.evaluate(snapshot())
        assertTrue("no ERROR expected", checks.none { it.status == CheckStatus.ERROR })
        assertEquals(CheckStatus.OK, find(checks, "native_libs").status)
        assertEquals(CheckStatus.OK, find(checks, "network").status)
    }

    @Test
    fun `unknown values are SKIPPED, never OK or ERROR`() {
        val checks = DiagnosticRules.evaluate(
            snapshot(
                sdkInt = null,
                pageSizeBytes = null,
                wgLibPresent = null,
                vpnPermissionGranted = null,
                notificationsEnabled = null,
                backgroundRestricted = null,
                freeStorageBytes = null,
                freeMemoryBytes = null,
                networkAvailable = null,
                dnsResolved = null,
            )
        )
        listOf(
            "android_version", "page_size", "native_libs", "vpn_permission",
            "notifications", "background", "storage", "memory", "network", "dns",
        ).forEach { id ->
            assertEquals("$id must be SKIPPED", CheckStatus.SKIPPED, find(checks, id).status)
        }
    }

    @Test
    fun `missing wg library is an ERROR with a recommendation`() {
        val check = find(DiagnosticRules.evaluate(snapshot(wgLibPresent = false)), "native_libs")
        assertEquals(CheckStatus.ERROR, check.status)
        assertTrue("recommendation required", !check.recommendation.isNullOrBlank())
    }

    @Test
    fun `absent wdtt engine is SKIPPED, not an error`() {
        val check = find(DiagnosticRules.evaluate(snapshot(wdttClientPresent = false)), "wdtt_engine")
        assertEquals(CheckStatus.SKIPPED, check.status)
        assertFalse("must not look like a failure", check.status == CheckStatus.ERROR)
    }

    @Test
    fun `present wdtt engine is OK`() {
        val check = find(DiagnosticRules.evaluate(snapshot(wdttClientPresent = true)), "wdtt_engine")
        assertEquals(CheckStatus.OK, check.status)
    }

    @Test
    fun `unsupported abi is an ERROR`() {
        val check = find(
            DiagnosticRules.evaluate(snapshot(primaryAbi = "mips", supportedAbis = listOf("mips"))),
            "abi",
        )
        assertEquals(CheckStatus.ERROR, check.status)
    }

    @Test
    fun `android below minSdk is an ERROR`() {
        val check = find(DiagnosticRules.evaluate(snapshot(sdkInt = 23, androidRelease = "6")), "android_version")
        assertEquals(CheckStatus.ERROR, check.status)
    }

    @Test
    fun `android 7 is a WARNING not an error`() {
        val check = find(DiagnosticRules.evaluate(snapshot(sdkInt = 24, androidRelease = "7.0")), "android_version")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `no internet is an ERROR`() {
        val check = find(
            DiagnosticRules.evaluate(snapshot(networkAvailable = false, networkTransports = emptySet())),
            "network",
        )
        assertEquals(CheckStatus.ERROR, check.status)
        assertTrue(!check.recommendation.isNullOrBlank())
    }

    @Test
    fun `dns failure is a WARNING not an error`() {
        val check = find(DiagnosticRules.evaluate(snapshot(dnsResolved = false)), "dns")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `foreign vpn active is a WARNING`() {
        val check = find(
            DiagnosticRules.evaluate(snapshot(vpnTransportActive = true, networkTransports = setOf("Wi-Fi"))),
            "conflicting_vpn",
        )
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `restricted background is an ERROR with battery advice`() {
        val check = find(DiagnosticRules.evaluate(snapshot(backgroundRestricted = true)), "background")
        assertEquals(CheckStatus.ERROR, check.status)
        assertTrue("must mention battery", check.recommendation!!.contains("батаре"))
    }

    @Test
    fun `battery optimisation without restriction is a WARNING`() {
        val check = find(
            DiagnosticRules.evaluate(snapshot(backgroundRestricted = false, ignoringBatteryOptimizations = false)),
            "background",
        )
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `low memory is a WARNING`() {
        val check = find(DiagnosticRules.evaluate(snapshot(freeMemoryBytes = 32L * 1024 * 1024)), "memory")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `low storage is a WARNING`() {
        val check = find(DiagnosticRules.evaluate(snapshot(freeStorageBytes = 8L * 1024 * 1024)), "storage")
        assertEquals(CheckStatus.WARNING, check.status)
    }

    @Test
    fun `unwritable files dir is an ERROR`() {
        val check = find(DiagnosticRules.evaluate(snapshot(filesDirWritable = false)), "storage")
        assertEquals(CheckStatus.ERROR, check.status)
    }

    @Test
    fun `sixteen kb pages are reported as compatible`() {
        val check = find(DiagnosticRules.evaluate(snapshot(pageSizeBytes = 16384)), "page_size")
        assertEquals(CheckStatus.OK, check.status)
        assertTrue("must mention 16", check.detail.contains("16"))
    }

    @Test
    fun `four kb pages stay OK since libraries are aligned for both`() {
        val check = find(DiagnosticRules.evaluate(snapshot(pageSizeBytes = 4096)), "page_size")
        assertEquals(CheckStatus.OK, check.status)
    }

    @Test
    fun `every check has a stable id and a title`() {
        val checks = DiagnosticRules.evaluate(snapshot())
        assertTrue("at least 12 checks", checks.size >= 12)
        assertEquals("ids must be unique", checks.size, checks.map { it.id }.toSet().size)
        checks.forEach {
            assertTrue("id blank", it.id.isNotBlank())
            assertTrue("title blank for ${it.id}", it.title.isNotBlank())
            assertTrue("detail blank for ${it.id}", it.detail.isNotBlank())
        }
    }

    @Test
    fun `report counts and summary reflect errors`() {
        val checks = DiagnosticRules.evaluate(snapshot(wgLibPresent = false))
        val report = DiagnosticReport(checks, generatedAtMillis = 0, appVersion = "test")
        assertTrue(report.hasBlockingProblem)
        assertEquals(1, report.counts[CheckStatus.ERROR])
        assertTrue(report.summaryLine().contains("Проблем"))
    }

    @Test
    fun `clean report summary says no problems`() {
        val report = DiagnosticReport(
            DiagnosticRules.evaluate(snapshot()),
            generatedAtMillis = 0,
            appVersion = "test",
        )
        assertFalse(report.hasBlockingProblem)
        assertTrue(report.summaryLine().contains("не обнаружено"))
    }

    @Test
    fun `bytes are formatted with binary units`() {
        assertEquals("512 Б", DiagnosticRules.formatBytes(512))
        assertEquals("1 КБ", DiagnosticRules.formatBytes(1024))
        assertEquals("1.0 МБ", DiagnosticRules.formatBytes(1024L * 1024))
    }
}
