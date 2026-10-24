package com.qwer.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Injects routing lines into a WireGuard/AmneziaWG config.
 *
 * Kept as a pure function so the string surgery is testable without an engine: a
 * malformed injection would produce a config the engine rejects, and that failure would
 * only surface at connect time on a real device.
 */
object RoutingInjector {

    private val ROUTING_KEYS = listOf("excludedapplications", "includedapplications")

    /**
     * @param config raw config text
     * @param lines routing lines from [AppRouting.configLines]
     * @return config with previous routing keys replaced by [lines]
     */
    fun apply(config: String, lines: List<String>): String {
        val withoutRouting = config.lineSequence()
            .filterNot { line ->
                val key = line.substringBefore('=', "").trim().lowercase()
                key in ROUTING_KEYS
            }
            .joinToString("\n")

        if (lines.isEmpty()) return withoutRouting.trimEnd() + "\n"

        val out = withoutRouting.lines().toMutableList()
        val idx = out.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (idx < 0) {
            // No [Interface]: the importer already rejects such configs, so returning the
            // input unchanged is safer than inventing a section.
            return config
        }
        // Insert in reverse so the final order matches the input list.
        lines.asReversed().forEach { out.add(idx + 1, it) }
        return out.joinToString("\n").trimEnd() + "\n"
    }

    /** Reads back what a config currently declares, for diagnostics and tests. */
    fun declaredPackages(config: String): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for (line in config.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            if (key !in ROUTING_KEYS) continue
            result[key] = line.substring(eq + 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return result
    }

    // ---------------------------------------------------------------- persistence

    /** Serialises routing into profile JSON. Absent keys mean [AppRouting.All]. */
    fun toJson(routing: AppRouting, o: JSONObject) {
        when (routing) {
            is AppRouting.All -> {
                o.put("routingMode", "ALL")
            }
            is AppRouting.Only -> {
                o.put("routingMode", "ONLY")
                o.put("routingPackages", JSONArray(routing.packages.toList()))
            }
            is AppRouting.Except -> {
                o.put("routingMode", "EXCEPT")
                o.put("routingPackages", JSONArray(routing.packages.toList()))
            }
        }
    }

    /** Reads routing from profile JSON; unknown/missing values degrade to [AppRouting.All]. */
    fun fromJson(o: JSONObject): AppRouting {
        val mode = o.optString("routingMode", "ALL").uppercase()
        val packages = o.optJSONArray("routingPackages")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }.toSet()
        } ?: emptySet()

        return when (mode) {
            "ONLY" -> if (packages.isEmpty()) AppRouting.All else AppRouting.Only(packages)
            "EXCEPT" -> if (packages.isEmpty()) AppRouting.All else AppRouting.Except(packages)
            else -> AppRouting.All
        }
    }
}
