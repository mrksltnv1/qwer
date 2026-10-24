package com.qwer.app.backup

import com.qwer.app.core.Protocol
import com.qwer.app.core.VpnProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialisable backup payload.
 *
 * WHAT IS INSIDE
 *  - `schema`: version of *this* structure, separate from the container format version.
 *    They move at different speeds: the container may change (new KDF) while the payload
 *    stays identical, and vice versa.
 *  - profiles with their metadata **and** raw configs. A config carries the private key,
 *    so the whole file is always encrypted — there is no plaintext mode, deliberately.
 *  - `activeProfileId`, so the restored install behaves like the original.
 *
 * WHAT IS NOT INSIDE
 *  Logs, crash data, cache, update state, UI preferences. Restoring them would resurrect
 *  stale diagnostics and confuse the user into thinking old errors are current.
 */
data class BackupPayload(
    val schema: Int,
    val profiles: List<BackupProfile>,
    val activeProfileId: String?,
) {
    data class BackupProfile(
        val profile: VpnProfile,
        /** Raw config text. May be null if the secret was already lost on the device. */
        val rawConfig: String?,
    ) {
        /** Validation for restore: a profile without a config is useless but not fatal. */
        val isUsable: Boolean get() = !rawConfig.isNullOrBlank()
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", schema)
        root.put("app", "qwer")
        root.put("activeProfileId", activeProfileId ?: JSONObject.NULL)
        val arr = JSONArray()
        for (entry in profiles) {
            val p = entry.profile
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("protocol", p.protocol.name)
                    put("endpoint", p.endpointSummary ?: "")
                    put("created", p.createdAtMillis)
                    put("failover", p.isFailoverTarget)
                    if (entry.rawConfig != null) {
                        put("config", entry.rawConfig)
                    } else {
                        put("config", JSONObject.NULL)
                    }
                }
            )
        }
        root.put("profiles", arr)
        return root.toString()
    }

    companion object {
        /** Current payload schema. Bump when the shape changes in a breaking way. */
        const val SCHEMA = 1

        /**
         * Parses and validates. Unknown/future schema is rejected rather than
         * half-applied: restoring a subset of an unknown format is worse than refusing.
         */
        fun parse(json: String): BackupParseResult {
            val root = runCatching { JSONObject(json) }.getOrElse {
                return BackupParseResult.Failed("Файл не является резервной копией qwer")
            }

            val schema = root.optInt("schema", -1)
            if (schema <= 0) return BackupParseResult.Failed("В файле нет версии формата")
            if (schema > SCHEMA) {
                return BackupParseResult.Failed(
                    "Копия создана более новой версией приложения (схема $schema)"
                )
            }

            val arr = root.optJSONArray("profiles")
                ?: return BackupParseResult.Failed("В файле нет профилей")

            val profiles = mutableListOf<BackupProfile>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                val protocol = runCatching { Protocol.valueOf(o.getString("protocol")) }
                    .getOrDefault(Protocol.WIREGUARD)
                val rawConfig = if (o.isNull("config")) null else o.optString("config").takeIf { it.isNotBlank() }

                profiles += BackupProfile(
                    profile = VpnProfile(
                        id = id,
                        name = name,
                        protocol = protocol,
                        endpointSummary = o.optString("endpoint").takeIf { it.isNotBlank() },
                        createdAtMillis = o.optLong("created", System.currentTimeMillis()),
                        isFailoverTarget = o.optBoolean("failover", false),
                    ),
                    rawConfig = rawConfig,
                )
            }

            if (profiles.isEmpty()) return BackupParseResult.Failed("В файле нет корректных профилей")

            val active = if (root.isNull("activeProfileId")) {
                null
            } else {
                root.optString("activeProfileId").takeIf { it.isNotBlank() }
            }

            return BackupParseResult.Ok(
                BackupPayload(schema = schema, profiles = profiles, activeProfileId = active)
            )
        }
    }
}

/**
 * Outcome of parsing a backup payload.
 *
 * Declared at FILE level, not inside [BackupPayload.Companion]: Kotlin cannot resolve a
 * nested type inside a companion object as `BackupPayload.ParseResult`, and the compiler
 * then reports a wall of "unresolved reference" errors. Same trap as WdttLink.Result.
 */
sealed interface BackupParseResult {
    data class Ok(val payload: BackupPayload) : BackupParseResult
    data class Failed(val reason: String) : BackupParseResult
}
