package com.qwer.app.backup

import com.qwer.app.core.Protocol
import com.qwer.app.core.VpnProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Payload parsing must reject anything it cannot restore faithfully. */
/**
 * Robolectric is required here: `org.json` ships as a stub in the Android SDK, and
 * under a plain JVM unit test every `JSONObject.put` returns null. The production
 * code is correct; the stub is not. BackupCodecTest needs no runner because it uses
 * only javax.crypto.
 */
@RunWith(RobolectricTestRunner::class)
class BackupPayloadTest {

    private fun payload(active: String? = null, profiles: List<BackupPayload.BackupProfile>) =
        BackupPayload(schema = BackupPayload.SCHEMA, profiles = profiles, activeProfileId = active)

    private fun entry(
        id: String = "p1",
        name: String = "Дом",
        protocol: Protocol = Protocol.WIREGUARD,
        config: String? = "[Interface]\nPrivateKey = abc\n",
        failover: Boolean = false,
        endpoint: String? = "1.2.3.4:51820",
    ) = BackupPayload.BackupProfile(
        profile = VpnProfile(
            id = id,
            name = name,
            protocol = protocol,
            endpointSummary = endpoint,
            createdAtMillis = 111L,
            isFailoverTarget = failover,
        ),
        rawConfig = config,
    )

    @Test
    fun `round trip through json preserves every field`() {
        val original = payload(active = "p1", profiles = listOf(entry()))
        val parsed = BackupPayload.parse(original.toJson())
        assertTrue(parsed is BackupParseResult.Ok)
        val p = (parsed as BackupParseResult.Ok).payload

        assertEquals(BackupPayload.SCHEMA, p.schema)
        assertEquals("p1", p.activeProfileId)
        assertEquals(1, p.profiles.size)
        val restored = p.profiles[0]
        assertEquals("p1", restored.profile.id)
        assertEquals("Дом", restored.profile.name)
        assertEquals(Protocol.WIREGUARD, restored.profile.protocol)
        assertEquals("1.2.3.4:51820", restored.profile.endpointSummary)
        assertEquals(111L, restored.profile.createdAtMillis)
        assertEquals("[Interface]\nPrivateKey = abc\n", restored.rawConfig)
    }

    @Test
    fun `amneziawg protocol is preserved`() {
        val original = payload(profiles = listOf(entry(protocol = Protocol.AMNEZIAWG)))
        val p = (BackupPayload.parse(original.toJson()) as BackupParseResult.Ok).payload
        assertEquals(Protocol.AMNEZIAWG, p.profiles[0].profile.protocol)
    }

    @Test
    fun `failover flag is preserved`() {
        val original = payload(profiles = listOf(entry(failover = true)))
        val p = (BackupPayload.parse(original.toJson()) as BackupParseResult.Ok).payload
        assertTrue(p.profiles[0].profile.isFailoverTarget)
    }

    @Test
    fun `null active profile survives`() {
        val original = payload(active = null, profiles = listOf(entry()))
        val p = (BackupPayload.parse(original.toJson()) as BackupParseResult.Ok).payload
        assertNull(p.activeProfileId)
    }

    @Test
    fun `profile without config is still parsed but marked unusable`() {
        val original = payload(profiles = listOf(entry(config = null)))
        val p = (BackupPayload.parse(original.toJson()) as BackupParseResult.Ok).payload
        assertEquals(1, p.profiles.size)
        assertNull(p.profiles[0].rawConfig)
        assertTrue("must be flagged unusable", !p.profiles[0].isUsable)
    }

    @Test
    fun `future schema is rejected rather than half applied`() {
        val json = JSONObject(payload(profiles = listOf(entry())).toJson())
            .put("schema", BackupPayload.SCHEMA + 1)
            .toString()
        val r = BackupPayload.parse(json)
        assertTrue(r is BackupParseResult.Failed)
        assertTrue("must mention the newer version",
            (r as BackupParseResult.Failed).reason.contains("новой", ignoreCase = true))
    }

    @Test
    fun `missing schema is rejected`() {
        val json = JSONObject().put("profiles", org.json.JSONArray()).toString()
        assertTrue(BackupPayload.parse(json) is BackupParseResult.Failed)
    }

    @Test
    fun `garbage json is rejected`() {
        val r = BackupPayload.parse("not json at all {{{")
        assertTrue(r is BackupParseResult.Failed)
    }

    @Test
    fun `empty profile list is rejected`() {
        val json = JSONObject()
            .put("schema", BackupPayload.SCHEMA)
            .put("profiles", org.json.JSONArray())
            .toString()
        val r = BackupPayload.parse(json)
        assertTrue(r is BackupParseResult.Failed)
        assertTrue((r as BackupParseResult.Failed).reason.contains("профилей"))
    }

    @Test
    fun `profile without id is skipped`() {
        val json = JSONObject()
            .put("schema", BackupPayload.SCHEMA)
            .put(
                "profiles",
                org.json.JSONArray().put(
                    JSONObject().put("name", "no id").put("protocol", "WIREGUARD")
                        .put("config", "[Interface]")
                )
            )
            .toString()
        assertTrue(BackupPayload.parse(json) is BackupParseResult.Failed)
    }

    @Test
    fun `unknown protocol falls back to wireguard instead of crashing`() {
        val json = JSONObject()
            .put("schema", BackupPayload.SCHEMA)
            .put(
                "profiles",
                org.json.JSONArray().put(
                    JSONObject().put("id", "x").put("name", "X")
                        .put("protocol", "SOME_FUTURE_PROTO").put("config", "[Interface]")
                )
            )
            .toString()
        val p = (BackupPayload.parse(json) as BackupParseResult.Ok).payload
        assertEquals(Protocol.WIREGUARD, p.profiles[0].profile.protocol)
    }

    @Test
    fun `multiple profiles all survive`() {
        val original = payload(
            active = "p3",
            profiles = listOf(
                entry(id = "p1", name = "A"),
                entry(id = "p2", name = "B", protocol = Protocol.AMNEZIAWG),
                entry(id = "p3", name = "C", protocol = Protocol.WDTT_PLUS),
            ),
        )
        val p = (BackupPayload.parse(original.toJson()) as BackupParseResult.Ok).payload
        assertEquals(3, p.profiles.size)
        assertEquals(listOf("p1", "p2", "p3"), p.profiles.map { it.profile.id })
        assertEquals("p3", p.activeProfileId)
        assertNotNull(p.profiles[2].rawConfig)
    }
}
