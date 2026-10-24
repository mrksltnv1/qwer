package com.qwer.app.core

/**
 * Per-app routing for a profile.
 *
 * WHAT ANDROID ACTUALLY SUPPORTS
 * ------------------------------
 * A `VpnService` can express exactly two things:
 *  - `addAllowedApplication(pkg)`  → only these apps are inside the tunnel;
 *  - `addDisallowedApplication(pkg)` → these apps bypass the tunnel.
 *
 * They are mutually exclusive in practice: mixing them throws
 * `UnsupportedOperationException` from the builder. So this model is a single choice, not
 * a pair of lists, and [toConfigLines] emits only one of the two keys.
 *
 * IMPORTANT LIMITATION, STATED HONESTLY: "excluded" means *not routed through this VPN*.
 * Android cannot guarantee that an excluded app goes out over Wi-Fi/cellular directly —
 * if another VPN is active or the OEM routes differently, the traffic goes wherever the
 * system sends it. The UI must not promise otherwise.
 */
sealed interface AppRouting {

    /** Everything goes through the tunnel. Default, and what every existing profile has. */
    data object All : AppRouting

    /** Only the listed apps use the tunnel; everything else stays outside. */
    data class Only(val packages: Set<String>) : AppRouting

    /** Everything uses the tunnel except the listed apps. */
    data class Except(val packages: Set<String>) : AppRouting

    companion object {
        /**
         * Builds the `[Interface]` lines for a routing mode.
         *
         * @param alwaysExcluded packages that must never enter the tunnel regardless of
         *        the mode (our own package: the WDTT child process shares our UID and
         *        would otherwise feed its TURN traffic back into the tunnel).
         *
         * Rules:
         *  - [All] emits nothing unless [alwaysExcluded] is non-empty, because a profile
         *    with no routing keys must keep working exactly as before.
         *  - [Only] with an empty list is *not* emitted: an empty allow-list would put
         *    nothing inside the tunnel, which silently breaks connectivity. It falls back
         *    to [All].
         *  - [Except] never drops [alwaysExcluded]; those are merged in.
         */
        fun configLines(routing: AppRouting, alwaysExcluded: Set<String> = emptySet()): List<String> {
            return when (routing) {
                is All -> if (alwaysExcluded.isEmpty()) {
                    emptyList()
                } else {
                    listOf("ExcludedApplications = ${alwaysExcluded.sorted().joinToString(", ")}")
                }

                is Only -> {
                    val keep = routing.packages.filter { it.isNotBlank() }.toSet()
                    if (keep.isEmpty()) {
                        // Nothing selected: behave like a normal full tunnel instead of a
                        // tunnel that carries no app at all.
                        configLines(All, alwaysExcluded)
                    } else {
                        // Our own package cannot be in the allow-list AND outside it; the
                        // engine uses allow-list semantics here, so it is simply included.
                        val allowed = (keep + alwaysExcluded).sorted()
                        listOf("IncludedApplications = ${allowed.joinToString(", ")}")
                    }
                }

                is Except -> {
                    val merged = (routing.packages.filter { it.isNotBlank() }.toSet() + alwaysExcluded)
                    if (merged.isEmpty()) {
                        emptyList()
                    } else {
                        listOf("ExcludedApplications = ${merged.sorted().joinToString(", ")}")
                    }
                }
            }
        }

        /** Number of apps affected, for the UI summary. */
        fun selectedCount(routing: AppRouting): Int = when (routing) {
            is All -> 0
            is Only -> routing.packages.size
            is Except -> routing.packages.size
        }

        /** True when the mode needs a package list to make sense. */
        fun isUnsupportedEmpty(routing: AppRouting): Boolean =
            routing is Only && routing.packages.isEmpty()
    }
}
