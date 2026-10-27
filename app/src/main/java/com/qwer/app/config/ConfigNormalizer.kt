package com.qwer.app.config

/**
 * Removes directives that belong to `wg-quick`, not to the WireGuard/AmneziaWG
 * protocol itself.
 *
 * WHY THIS EXISTS (verified against the real engine, not assumed):
 * the AmneziaWG config parser used by the Android tunnel *rejects the whole config*
 * with `BadConfigException` when it meets an unknown key. Its allow-list is:
 *
 *   [Interface] address, dns, mtu, listenport, privatekey,
 *               jc, jmin, jmax, s1..s4, h1..h4, i1..i5,
 *               headerprotectionkey, contentpaddingaddition,
 *               randomtrailers, disablecookies, keepalivetimeout,
 *               maxhandshakeattempts, rekeyaftertime, rekeytimeout,
 *               rejectaftertime, on, true, yes,
 *               includedapplications, excludedapplications
 *   [Peer]      publickey, presharedkey, endpoint, allowedips, persistentkeepalive
 *
 * `PostUp`, `PostDown`, `PreUp`, `PreDown`, `Table`, `SaveConfig`, `FwMark` are
 * wg-quick shell directives. They are meaningless in a userspace VpnService tunnel
 * — routing there is configured by the VpnService builder — but their mere presence
 * makes the parser throw, and the user sees only "engine refused the config".
 *
 * That is exactly the reported "AmneziaWG does not work": a perfectly valid tunnel
 * that fails to start because of a leftover line from a server-side export.
 */
object ConfigNormalizer {

    /** wg-quick-only keys. Case-insensitive, tolerant of spacing. */
    private val WG_QUICK_ONLY = setOf(
        "postup", "postdown", "preup", "predown",
        "table", "saveconfig", "fwmark",
    )

    data class Result(
        val config: String,
        val removedKeys: List<String>,
    ) {
        val hadChanges: Boolean get() = removedKeys.isNotEmpty()
    }

    /**
     * Default keepalive, in seconds.
     *
     * WHY 25 AND WHY IT IS INJECTED:
     * without a persistent keepalive a WireGuard peer sends nothing when idle, so NAT
     * and mobile carriers drop the mapping within 30-120 s. The tunnel then looks "up"
     * while every packet is black-holed, and only a probe or a user action reveals it —
     * that is the reported "тоннель зависает" after a network switch. 25 s is the
     * WireGuard convention: below the shortest common NAT timeout (30 s).
     *
     * A value from the config is kept unless it is 0 or unset; only then we add ours.
     */
    const val DEFAULT_KEEPALIVE_SECONDS = 25

    /**
     * Ensures every `[Peer]` has a non-zero `PersistentKeepalive`.
     *
     * Pure string surgery so it is unit-testable without an engine. Applied to the copy
     * handed to the engine; the stored config is never modified, so the user keeps their
     * original file.
     */
    fun ensureKeepalive(raw: String, seconds: Int = DEFAULT_KEEPALIVE_SECONDS): String {
        if (seconds <= 0) return raw
        val out = StringBuilder(raw.length + 64)
        var inPeer = false
        var peerHasKeepalive = false
        val peerBlock = StringBuilder()

        fun flushPeer() {
            if (!inPeer) return
            // Insert before the next section header: order inside [Peer] does not matter
            // to the engine, but appending keeps the diff readable.
            if (!peerHasKeepalive) {
                peerBlock.append("PersistentKeepalive = ").append(seconds).append('\n')
            }
            out.append(peerBlock)
        }

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.equals("[Peer]", ignoreCase = true) -> {
                    flushPeer()
                    inPeer = true
                    peerHasKeepalive = false
                    peerBlock.setLength(0)
                    peerBlock.append(line).append('\n')
                }
                trimmed.startsWith("[") -> {
                    flushPeer()
                    inPeer = false
                    peerBlock.setLength(0)
                    out.append(line).append('\n')
                }
                inPeer -> {
                    val eq = trimmed.indexOf('=')
                    val key = if (eq > 0) trimmed.substring(0, eq).trim().lowercase() else ""
                    if (key == "persistentkeepalive") {
                        val value = trimmed.substring(eq + 1).trim().toIntOrNull() ?: 0
                        if (value > 0) {
                            peerHasKeepalive = true
                            peerBlock.append(line).append('\n')
                        } else {
                            // Zero or garbage: replace with the default instead of
                            // keeping a line that disables keepalive.
                            peerHasKeepalive = true
                            peerBlock.append("PersistentKeepalive = ").append(seconds).append('\n')
                        }
                    } else {
                        peerBlock.append(line).append('\n')
                    }
                }
                else -> out.append(line).append('\n')
            }
        }
        flushPeer()
        return out.toString()
    }

    /**
     * Returns a config the engine will accept, plus the list of dropped directives so
     * the UI can tell the user what was ignored instead of silently editing their file.
     */
    fun sanitize(raw: String): Result {
        val removed = linkedSetOf<String>()
        val kept = StringBuilder(raw.length)

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0 && !trimmed.startsWith("#") && !trimmed.startsWith("[")) {
                val key = trimmed.substring(0, eq).trim().lowercase()
                if (key in WG_QUICK_ONLY) {
                    removed += key
                    continue
                }
            }
            kept.append(line).append('\n')
        }

        return Result(
            config = kept.toString().trimEnd() + "\n",
            removedKeys = removed.toList(),
        )
    }
}
