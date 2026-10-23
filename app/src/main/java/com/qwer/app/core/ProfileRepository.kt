package com.qwer.app.core

import android.content.Context
import com.qwer.app.diagnostics.Diagnostics
import com.qwer.app.diagnostics.Diagnostics.Event
import com.qwer.app.secure.ConfigStore
import com.qwer.app.secure.KeystoreConfigStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores profile *metadata* in plain preferences and profile *configs* (secrets) in
 * [ConfigStore] (Keystore-encrypted). Metadata is intentionally non-secret.
 */
class ProfileRepository(
    context: Context,
    private val configStore: ConfigStore = KeystoreConfigStore(context),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<VpnProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse {
            Diagnostics.warn(Event.PROFILE_IMPORT_REJECTED, it, "stage" to "read")
            emptyList()
        }
    }

    fun find(id: String): VpnProfile? = all().firstOrNull { it.id == id }

    fun failoverTarget(): VpnProfile? =
        all().firstOrNull { it.isFailoverTarget && it.protocol == Protocol.WDTT_PLUS }

    /**
     * Marks [id] as the only failover target, or clears the flag when null.
     *
     * Without this the release build had NO way to designate a fallback profile:
     * `isFailoverTarget = true` was only ever set inside a `BuildConfig.DEBUG` block,
     * so `failoverTarget()` returned null for every real user and the automatic
     * WG/AWG -> WDTT switch could never fire. It silently reported "резервный профиль
     * не настроен" no matter what the user imported.
     */
    fun setFailoverTarget(id: String?) {
        val updated = all().map { it.copy(isFailoverTarget = it.id == id) }
        persist(updated)
        Diagnostics.event(
            Event.FAILOVER_TARGET,
            "profile" to (id ?: "none"),
        )
    }

    fun activeProfile(): VpnProfile? {
        val id = prefs.getString(KEY_ACTIVE, null) ?: return null
        return find(id)
    }

    fun setActive(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    /** Adds or replaces a profile and stores its config atomically enough for our purpose. */
    fun save(profile: VpnProfile, rawConfig: String?) {
        val existing = all().filterNot { it.id == profile.id }
        // Only one profile may be the failover target.
        val normalized = if (profile.isFailoverTarget) {
            existing.map { it.copy(isFailoverTarget = false) } + profile
        } else {
            existing + profile
        }
        persist(normalized)
        if (rawConfig != null) configStore.putConfig(profile.id, rawConfig)
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
        configStore.removeConfig(id)
        if (prefs.getString(KEY_ACTIVE, null) == id) setActive(null)
    }

    fun configFor(id: String): String? = configStore.getConfig(id)

    /** True when a profile exists but its secret could not be decrypted (re-import needed). */
    fun isConfigMissing(id: String): Boolean = configStore.getConfig(id) == null

    private fun persist(profiles: List<VpnProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun toJson(p: VpnProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("protocol", p.protocol.name)
        put("endpoint", p.endpointSummary ?: "")
        put("created", p.createdAtMillis)
        put("failover", p.isFailoverTarget)
    }

    private fun fromJson(o: JSONObject) = VpnProfile(
        id = o.getString("id"),
        name = o.getString("name"),
        protocol = runCatching { Protocol.valueOf(o.getString("protocol")) }
            .getOrDefault(Protocol.WIREGUARD),
        endpointSummary = o.optString("endpoint").takeIf { it.isNotBlank() },
        createdAtMillis = o.optLong("created", 0L),
        isFailoverTarget = o.optBoolean("failover", false),
    )

    private companion object {
        const val PREFS = "vpn_profiles"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE = "active_id"
    }
}
