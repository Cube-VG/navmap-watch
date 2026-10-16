package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    private val info = NavInfo(NavKind.NAVIGATING, "Turn left onto Elm St", Distance("350 m", 350), "Arrive 10:01", "10:01")

    @Test
    fun navWithArrow() {
        val arrow = IntArray(96) { it }
        val m = WatchProtocol.nav(7, info, arrow, 1234, sendArrow = true)
        assertEquals("nav", m["t"])
        assertEquals(7, m["s"])
        assertEquals("Turn left onto Elm St", m["i"])
        assertEquals("350 m", m["d"])
        assertEquals(350, m["dm"])
        assertEquals("Arrive 10:01", m["e"])
        assertEquals(1234, m["ah"])
        assertEquals(arrow.toList(), m["a"])
        assertTrue(m["a"] is List<*>)
    }

    @Test
    fun navWithoutArrowOrDistance() {
        val m = WatchProtocol.nav(1, info.copy(distance = null), null, 0, sendArrow = false)
        assertFalse(m.containsKey("a"))
        assertEquals("", m["d"])
        assertEquals(-1, m["dm"])
        assertEquals(emptyList<Int>(), WatchProtocol.nav(1, info, null, 0, sendArrow = true)["a"])
    }

    @Test
    fun otherMessages() {
        assertEquals(mapOf("t" to "end", "ar" to true), WatchProtocol.end(arrived = true))
        assertEquals(mapOf("t" to "route", "p" to listOf(1, 2, 3, 4)), WatchProtocol.route(listOf(1, 2, 3, 4)))
        assertEquals(mapOf("t" to "where"), WatchProtocol.where())
        assertEquals(mapOf("t" to "idle"), WatchProtocol.idle())
    }

    @Test
    fun parseFromWatch() {
        assertEquals(
            WatchProtocol.FromWatch.Hello(2, LatLng(51.50722, -0.1276)),
            WatchProtocol.parse(mapOf("t" to "hello", "v" to 2, "la" to 5150722, "lo" to -12760L)),
        )
        assertEquals(WatchProtocol.FromWatch.Hello(1, null), WatchProtocol.parse(mapOf("t" to "hello")))
        assertEquals(WatchProtocol.FromWatch.Position(null), WatchProtocol.parse(mapOf("t" to "pos", "la" to 0, "lo" to 0)))
        assertEquals(WatchProtocol.FromWatch.Bye, WatchProtocol.parse(mapOf("t" to "bye")))
        assertTrue(WatchProtocol.parse("hello") is WatchProtocol.FromWatch.Unknown)
    }
}
