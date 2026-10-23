package com.myvpn.app.config

import com.myvpn.app.core.Protocol

/**
 * Result of validating pasted/imported config text.
 * Deliberately carries no key material — only structural facts safe for UI and logs.
 */
sealed interface ImportResult {
    data class Valid(
        val protocol: Protocol,
        val interfaceAddresses: List<String>,
        val dnsServers: List<String>,
        val peerCount: Int,
        val endpoint: String?,
        val allowedIps: List<String>,
    ) : ImportResult

    data class Invalid(val reason: String) : ImportResult
}

/**
 * Validates WireGuard / AmneziaWG configuration text before it is stored or used.
 *
 * This is a *safety* parser, not a full one: it enforces structure and size limits and
 * refuses anything that does not look like a WG/AWG config. The authoritative parse is
 * done by the engine when the tunnel starts.
 */
object ConfigImporter {

    /** A WG/AWG config is a few KB at most. Refuse anything wildly larger. */
    const val MAX_CONFIG_BYTES = 64 * 1024

    private val REQUIRED_INTERFACE_KEYS = listOf("privatekey", "address")
    private val REQUIRED_PEER_KEYS = listOf("publickey", "allowedips")

    fun validate(raw: String): ImportResult {
        if (raw.isBlank()) return ImportResult.Invalid("Конфигурация пуста")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
            return ImportResult.Invalid("Конфигурация слишком большая")
        }
        // Reject binary / control characters that never appear in a valid config.
        if (raw.any { it.code < 0x09 }) {
            return ImportResult.Invalid("Конфигурация содержит недопустимые символы")
        }

        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.none { it.equals("[Interface]", ignoreCase = true) }) {
            return ImportResult.Invalid("Отсутствует секция [Interface]")
        }
        if (lines.none { it.equals("[Peer]", ignoreCase = true) }) {
            return ImportResult.Invalid("Отсутствует секция [Peer]")
        }

        val interfaceLines = section(lines, "[Interface]")
        val peerLines = section(lines, "[Peer]")

        val ifaceMap = toKeyMap(interfaceLines)
        val missingIface = REQUIRED_INTERFACE_KEYS.filter { !ifaceMap.containsKey(it) }
        if (missingIface.isNotEmpty()) {
            return ImportResult.Invalid("В [Interface] не хватает: ${missingIface.joinToString()}")
        }

        val peerMaps = peerBlocks(lines)
        if (peerMaps.isEmpty()) return ImportResult.Invalid("Нет ни одного пира")
        for (peer in peerMaps) {
            val missing = REQUIRED_PEER_KEYS.filter { !peer.containsKey(it) }
            if (missing.isNotEmpty()) {
                return ImportResult.Invalid("В [Peer] не хватает: ${missing.joinToString()}")
            }
        }

        val privateKey = ifaceMap["privatekey"].orEmpty()
        if (!looksLikeKey(privateKey)) return ImportResult.Invalid("Некорректный PrivateKey")

        val peerPublic = peerMaps.first()["publickey"].orEmpty()
        if (!looksLikeKey(peerPublic)) return ImportResult.Invalid("Некорректный PublicKey")

        val addresses = ifaceMap["address"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (addresses.isEmpty()) return ImportResult.Invalid("Не указан Address")

        val dns = ifaceMap["dns"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val allowed = peerMaps.first()["allowedips"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return ImportResult.Valid(
            protocol = Protocol.classifyConfig(raw),
            interfaceAddresses = addresses,
            dnsServers = dns,
            peerCount = peerMaps.size,
            endpoint = peerMaps.first()["endpoint"],
            allowedIps = allowed,
        )
    }

    private fun section(lines: List<String>, header: String): List<String> {
        val start = lines.indexOfFirst { it.equals(header, ignoreCase = true) }
        if (start < 0) return emptyList()
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("[") }
        return if (end < 0) rest else rest.take(end)
    }

    private fun toKeyMap(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null
            else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()

    private fun splitPeers(lines: List<String>): List<Map<String, String>> = peerBlocks(lines)

    /**
     * Collects EVERY [Peer] block, not just the first one. Multi-peer configs are legal
     * (site-to-site, several endpoints), and a parser that silently drops peers would
     * under-report what the engine is actually going to do.
     */
    private fun peerBlocks(lines: List<String>): List<Map<String, String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            if (line.startsWith("[")) {
                if (line.equals("[Peer]", ignoreCase = true)) {
                    current = mutableListOf()
                    blocks.add(current)
                } else {
                    // Any other section header (e.g. a trailing [Interface]) ends the peer.
                    current = null
                }
                continue
            }
            current?.add(line)
        }
        return blocks.map { toKeyMap(it) }
    }

    /** Base64 keys are 44 chars (32 bytes) with a trailing '='. */
    private fun looksLikeKey(value: String): Boolean {
        if (value.length < 40 || value.length > 64) return false
        if (!value.endsWith("=")) return false
        return value.dropLast(1).all { it.isLetterOrDigit() || it == '+' || it == '/' }
    }
}
