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
