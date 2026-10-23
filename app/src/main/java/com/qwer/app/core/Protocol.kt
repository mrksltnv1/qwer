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
         * Classifies an imported config as WireGuard or AmneziaWG by looking for the
         * AmneziaWG-only obfuscation keys in the [Interface] section.
         */
        fun classifyConfig(rawConfig: String): Protocol {
            val interfaceSection = rawConfig
                .lineSequence()
                .dropWhile { it.trim() != "[Interface]" }
                .takeWhile { it.trim() != "[Peer]" }
                .joinToString("\n")
                .lowercase()
            val awgKeys = listOf("jc =", "jmin =", "jmax =", "s1 =", "s2 =",
                "h1 =", "h2 =", "h3 =", "h4 =", "i1 =", "i2 =")
            return if (awgKeys.any { interfaceSection.contains(it) }) AMNEZIAWG else WIREGUARD
        }
    }
}
