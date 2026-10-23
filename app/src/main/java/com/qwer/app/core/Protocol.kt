package com.qwer.app.core

/**
 * Protocol families the app can drive.
 *
 * IMPORTANT: [WIREGUARD] and [AMNEZIAWG] are served by the *same* native engine
 * (org.amnezia.awg). The AmneziaWG engine parses a plain WireGuard config and
 * simply leaves the obfuscation fields empty, so a plain WG profile runs as WG.
 * Keeping one engine avoids the `libwg-go.so` / `libwg.so` / `libwg-quick.so`
 * packaging collision that occurs when both AARs are shipped in one APK.
 */
enum class Protocol(val displayName: String) {
    WIREGUARD("WireGuard"),
    AMNEZIAWG("AmneziaWG"),
    WDTT_PLUS("WDTT Plus");

    val isEngineNative: Boolean get() = this != WDTT_PLUS

    companion object {
        /**
         * AmneziaWG-only obfuscation keys. Any of these in the [Interface] section
         * means the profile must be driven as AWG (they are meaningless for plain WG).
         */
        private val AWG_KEYS = setOf(
            "jc", "jmin", "jmax",
            "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4",
            "i1", "i2", "i3", "i4", "i5",
            "headerprotectionkey", "contentpaddingaddition",
        )

        /**
         * Classifies an imported config as WireGuard or AmneziaWG.
         *
         * BUG FIXED HERE: the previous version searched for the literal strings
         * "jc =", "s1 =", … which required a space before `=`. Real-world configs
         * (and every generator I could find) also emit `Jc=4` without spaces, so
         * those profiles were silently treated as plain WireGuard. The engine then
         * parsed them WITHOUT obfuscation, connected to an AWG server that expects
         * it, and the handshake never completed — which is exactly the reported
         * "AmneziaWG does not work".
         *
         * Parsing is now whitespace- and case-insensitive: split on the first '='
         * and compare the trimmed lowercase key.
         */
        fun classifyConfig(rawConfig: String): Protocol {
            var inInterface = false
            for (rawLine in rawConfig.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[")) {
                    inInterface = line.equals("[Interface]", ignoreCase = true)
                    continue
                }
                if (!inInterface) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                if (key in AWG_KEYS) return AMNEZIAWG
            }
            return WIREGUARD
        }
    }
}
