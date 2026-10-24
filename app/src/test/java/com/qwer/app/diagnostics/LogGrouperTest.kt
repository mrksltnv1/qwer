package com.qwer.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping must simplify reading without ever hiding a unique event or reordering.
 */
class LogGrouperTest {

    @Test
    fun `empty input yields no groups`() {
        assertTrue(LogGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `unique lines stay unique`() {
        val lines = listOf(
            "10:00:01  TUNNEL_START protocol=WIREGUARD",
            "10:00:02  PROBE_RESULT ok=true fails=0",
            "10:00:03  TUNNEL_STOP reason=user",
        )
        val groups = LogGrouper.group(lines)
        assertEquals(3, groups.size)
        assertTrue(groups.all { !it.isRepeated })
        assertEquals("10:00:03", groups[2].firstTimestamp)
    }

    @Test
    fun `adjacent identical events collapse into one group`() {
        val lines = (1..40).map { "10:00:%02d  PROBE_RESULT ok=true fails=0".format(it % 60) }
        val groups = LogGrouper.group(lines)
        assertEquals("forty lines become one row", 1, groups.size)
        assertEquals(40, groups[0].count)
        assertEquals("10:00:01", groups[0].firstTimestamp)
        assertEquals("10:00:40", groups[0].lastTimestamp)
    }

    @Test
    fun `the field log repetition collapses but keeps the real transitions`() {
        // Replay of the reported NETWORK_CHANGED burst pattern.
        val lines = listOf(
            "13:53:09  NETWORK_EVENT kind=available",
            "13:53:09  NETWORK_EVENT kind=changed transport=wifi",
            "13:53:10  NETWORK_EVENT kind=changed transport=wifi",
            "13:53:12  NETWORK_EVENT kind=changed transport=wifi",
            "13:53:19  WDTT_ENGINE stage=self_excluded pkg=com.qwer.app",
            "13:53:22  NETWORK_EVENT kind=changed transport=wifi",
            "13:53:23  PROBE_RESULT ok=true fails=0",
        )
        val groups = LogGrouper.group(lines)
        // available | changed x3 | self_excluded | changed x1 | probe
        assertEquals(5, groups.size)
        assertEquals(3, groups[1].count)
        assertEquals("13:53:09", groups[1].firstTimestamp)
        assertEquals("13:53:12", groups[1].lastTimestamp)
        // The later lone NETWORK_EVENT did NOT merge with the earlier burst.
        assertEquals(1, groups[3].count)
        assertEquals("13:53:22", groups[3].firstTimestamp)
    }

    @Test
    fun `different failure counts are not merged`() {
        val lines = listOf(
            "10:00:01  PROBE_RESULT ok=false fails=1",
            "10:00:02  PROBE_RESULT ok=false fails=2",
            "10:00:03  PROBE_RESULT ok=false fails=3",
        )
        val groups = LogGrouper.group(lines)
        assertEquals("loss severity must stay visible", 3, groups.size)
    }

    @Test
    fun `warnings are distinguishable from normal lines`() {
        val lines = listOf(
            "10:00:01  ! TUNNEL_START_FAILED code=X",
            "10:00:02  TUNNEL_START_FAILED code=X",
        )
        val groups = LogGrouper.group(lines)
        // Same text but one is a warning: must not be merged.
        assertEquals(2, groups.size)
    }

    @Test
    fun `timestamps are parsed from the standard format`() {
        assertEquals("10:00:01", LogGrouper.timestampOf("10:00:01  EVENT"))
        assertNull(LogGrouper.timestampOf("no timestamp here"))
        assertNull(LogGrouper.timestampOf("1:2:3  bad"))
    }

    @Test
    fun `key ignores the timestamp so identical events match`() {
        assertEquals(
            LogGrouper.keyOf("10:00:01  PROBE_RESULT ok=true"),
            LogGrouper.keyOf("10:59:59  PROBE_RESULT ok=true"),
        )
    }

    @Test
    fun `ordering is preserved exactly`() {
        val lines = listOf(
            "10:00:01  A",
            "10:00:02  A",
            "10:00:03  B",
            "10:00:04  A",
        )
        val groups = LogGrouper.group(lines)
        assertEquals(3, groups.size)
        assertTrue(groups[0].line.contains("A"))
        assertTrue(groups[1].line.contains("B"))
        assertTrue(groups[2].line.contains("A"))
    }

    @Test
    fun `lines without timestamps still group`() {
        val groups = LogGrouper.group(listOf("SAME", "SAME", "OTHER"))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].count)
        assertNull(groups[0].firstTimestamp)
    }

    @Test
    fun `five hundred lines collapse to a readable count`() {
        // 480 identical probes + 20 unique transitions.
        val lines = buildList {
            repeat(20) { i ->
                add("10:00:%02d  PROBE_RESULT ok=true fails=0".format(i))
                repeat(24) { add("10:01:%02d  PROBE_RESULT ok=true fails=0".format(it)) }
            }
        }
        val groups = LogGrouper.group(lines)
        assertTrue("500 lines must collapse well below 50 rows, got ${groups.size}", groups.size < 50)
        assertEquals(500, groups.sumOf { it.count })
    }
}
