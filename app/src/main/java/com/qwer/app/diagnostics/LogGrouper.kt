package com.qwer.app.diagnostics

/**
 * Collapses repeated adjacent log lines for display.
 *
 * WHY
 * ---
 * The field log from a real device contained 14 `NETWORK_CHANGED transport=wifi` lines in
 * 11 minutes and long runs of identical `PROBE_RESULT ok=true`. At 500 buffered lines the
 * useful events (tunnel start, failover) get pushed out visually.
 *
 * RULES
 *  - Only *adjacent* lines are collapsed. A repeat that happens after a different event
 *    stays visible, because the ordering itself carries information.
 *  - The comparison key is the line with its timestamp removed. Two `PROBE_RESULT ok=true`
 *    at different times are the same event; two `PROBE_RESULT ok=false fails=2` and
 *    `fails=3` are not, and must stay separate.
 *  - The group keeps the FIRST timestamp and the LAST timestamp, so the user can tell how
 *    long the repetition lasted.
 *  - Unique events are never merged away: a group of one renders exactly as before.
 */
object LogGrouper {

    /** One displayed row, possibly representing several identical events. */
    data class Group(
        val count: Int,
        val firstTimestamp: String?,
        val lastTimestamp: String?,
        /** The representative line, with its timestamp retained for a group of one. */
        val line: String,
    ) {
        val isRepeated: Boolean get() = count > 1
    }

    /**
     * @param lines raw buffer lines in the format "HH:MM:SS  <event>" (see Diagnostics).
     */
    fun group(lines: List<String>): List<Group> {
        if (lines.isEmpty()) return emptyList()

        val result = ArrayList<Group>(lines.size)
        var currentKey: String? = null
        var currentLines = mutableListOf<String>()
        var currentTs: String? = null
        var lastTs: String? = null

        fun flush() {
            val first = currentLines.firstOrNull() ?: return
            result += Group(
                count = currentLines.size,
                firstTimestamp = currentTs,
                lastTimestamp = lastTs,
                line = first,
            )
            currentLines = mutableListOf()
        }

        for (raw in lines) {
            val ts = timestampOf(raw)
            val key = keyOf(raw)
            if (key == currentKey) {
                currentLines += raw
                lastTs = ts
            } else {
                flush()
                currentKey = key
                currentLines = mutableListOf(raw)
                currentTs = ts
                lastTs = ts
            }
        }
        flush()
        return result
    }

    /**
     * Timestamp as printed by Diagnostics: the first token when it looks like a clock.
     * Anything else yields null and the line is still grouped correctly.
     */
    fun timestampOf(line: String): String? {
        val trimmed = line.trimStart()
        val firstSpace = trimmed.indexOf(' ')
        if (firstSpace <= 0) return null
        val candidate = trimmed.substring(0, firstSpace)
        // "HH:MM:SS" is the only shape Diagnostics emits.
        val parts = candidate.split(':')
        if (parts.size != 3) return null
        if (parts.any { it.isEmpty() || it.length != 2 || it.any { c -> !c.isDigit() } }) return null
        return candidate
    }

    /** The line with its leading timestamp and warning marker removed, for comparison. */
    fun keyOf(line: String): String {
        var s = line.trim()
        s = s.removePrefix("!")
        s = s.trim()
        timestampOf(s)?.let { ts -> s = s.removePrefix(ts).trim() }
        return s
    }
}
