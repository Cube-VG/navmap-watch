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
        assertEquals(mapOf("t" to "route", "p" to listOf(1, 2, 3, 4), "rv" to 9), WatchProtocol.route(listOf(1, 2, 3, 4), 9))
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
        assertTrue((WatchProtocol.parse(mapOf("t" to "hello", "v" to 2, "r" to 1)) as WatchProtocol.FromWatch.Hello).roads)
        assertTrue((WatchProtocol.parse(mapOf("t" to "hello", "r" to 1L)) as WatchProtocol.FromWatch.Hello).roads)
        assertFalse((WatchProtocol.parse(mapOf("t" to "hello", "r" to 0)) as WatchProtocol.FromWatch.Hello).roads)
        assertEquals(WatchProtocol.FromWatch.Position(null), WatchProtocol.parse(mapOf("t" to "pos", "la" to 0, "lo" to 0)))
        assertTrue((WatchProtocol.parse(mapOf("t" to "hello", "g" to 1)) as WatchProtocol.FromWatch.Hello).glance)
        assertFalse((WatchProtocol.parse(mapOf("t" to "hello")) as WatchProtocol.FromWatch.Hello).glance)
        assertEquals("ab12", (WatchProtocol.parse(mapOf("t" to "hello", "kh" to "ab12")) as WatchProtocol.FromWatch.Hello).token)
        assertEquals(null, (WatchProtocol.parse(mapOf("t" to "hello", "kh" to 5)) as WatchProtocol.FromWatch.Hello).token)
        // NavMap before 0.12 says only bye; 0.12 adds its run, how long it ran and why it closed.
        assertEquals(WatchProtocol.FromWatch.Bye(), WatchProtocol.parse(mapOf("t" to "bye")))
        assertEquals(
            WatchProtocol.FromWatch.Bye(session = 81234, seconds = 120),
            WatchProtocol.parse(mapOf("t" to "bye", "id" to 81234, "up" to 120L)),
        )
        assertEquals(
            WatchProtocol.FromWatch.Bye(session = 5, seconds = 300, why = "u", suspended = true),
            WatchProtocol.parse(mapOf("t" to "bye", "id" to 5, "up" to 300, "why" to "u", "sus" to 1)),
        )
        assertTrue(WatchProtocol.parse("hello") is WatchProtocol.FromWatch.Unknown)
    }

    private val arrow = IntArray(96) { it * 1_000_003 - 7 }
    private val route = listOf(1297000, 7759000, 1297100, 7759100)

    private fun poll(ah: Int?, rv: Int?) = WatchProtocol.Poll("k", ah, rv, null)

    @Test
    fun navReplySendsOnlyWhatTheWatchLacks() {
        val m = WatchProtocol.navReply(info, arrow, 1234, route, 77, poll(1234, 77))
        assertEquals(
            mapOf("t" to "nav", "i" to "Turn left onto Elm St", "d" to "350 m", "dm" to 350, "e" to "Arrive 10:01", "ah" to 1234, "rv" to 77),
            m,
        )
        assertFalse(m.containsKey("s"))

        val fresh = WatchProtocol.navReply(info, arrow, 1234, route, 77, poll(null, null))
        assertEquals(arrow.toList(), fresh["a"])
        assertEquals(route, fresh["p"])

        val newArrow = WatchProtocol.navReply(info, arrow, 1234, route, 77, poll(99, 77))
        assertEquals(arrow.toList(), newArrow["a"])
        assertFalse(newArrow.containsKey("p"))
    }

    @Test
    fun navReplyClearsWhatsGone() {
        // No arrow (hash 0) and a cleared route (new version, no points).
        val m = WatchProtocol.navReply(info.copy(distance = null), null, 0, null, 5, poll(1234, 77))
        assertEquals(emptyList<Int>(), m["a"])
        assertEquals(emptyList<Int>(), m["p"])
        assertEquals("", m["d"])
        assertEquals(-1, m["dm"])
        // A watch that has nothing either gets nothing extra.
        val none = WatchProtocol.navReply(info, null, 0, null, 0, poll(0, 0))
        assertFalse(none.containsKey("a"))
        assertFalse(none.containsKey("p"))
    }

    @Test
    fun compactReplySendsOnlyWhatTheWatchLacks() {
        val routeText = NavCodec.route(route.toIntArray())
        val m = WatchProtocol.compactNavReply(info, arrow, 1234, routeText, 77, poll(1234, 77))
        assertEquals(mapOf("n" to "350|1234|77|350 m|Arrive 10:01|Turn left onto Elm St"), m)

        val fresh = WatchProtocol.compactNavReply(info, arrow, 1234, routeText, 77, poll(null, null))
        assertEquals(NavCodec.arrow(arrow), fresh["A"])
        assertEquals(routeText, fresh["P"])
        assertFalse(fresh.containsKey("a") || fresh.containsKey("p") || fresh.containsKey("t"))

        val newRoute = WatchProtocol.compactNavReply(info, arrow, 1234, routeText, 78, poll(1234, 77))
        assertFalse(newRoute.containsKey("A"))
        assertEquals(routeText, newRoute["P"])
    }

    @Test
    fun compactReplyClearsWhatsGone() {
        val m = WatchProtocol.compactNavReply(info.copy(distance = null), null, 0, null, 5, poll(1234, 77))
        assertEquals("-1|0|5||Arrive 10:01|Turn left onto Elm St", m["n"])
        assertEquals("", m["A"])
        assertEquals("", m["P"])
        val none = WatchProtocol.compactNavReply(info, null, 0, null, 0, poll(0, 0))
        assertEquals(setOf("n"), none.keys)
    }

    @Test
    fun endForAMinuteThenIdle() {
        assertEquals(mapOf("t" to "end", "ar" to true), WatchProtocol.notNavigatingReply(0, arrived = true))
        assertEquals(mapOf("t" to "end", "ar" to false), WatchProtocol.notNavigatingReply(59_999, arrived = false))
        assertEquals(mapOf("t" to "idle"), WatchProtocol.notNavigatingReply(60_000, arrived = true))
        assertEquals(mapOf("t" to "idle"), WatchProtocol.notNavigatingReply(null, arrived = true))
        assertEquals(mapOf("t" to "idle"), WatchProtocol.notNavigatingReply(-5, arrived = true))
    }

    @Test
    fun readsPolls() {
        assertEquals(
            WatchProtocol.Poll("0123abcd", -123, 5, LatLng(12.97, 77.59)),
            WatchProtocol.poll("k=0123abcd&ah=-123&rv=5&la=1297000&lo=7759000"),
        )
        // The first poll after NavMap starts may have nothing; numbers written unsigned or as floats still read.
        assertEquals(WatchProtocol.Poll(null, null, null, null), WatchProtocol.poll(null))
        assertEquals(WatchProtocol.Poll(null, null, null, null), WatchProtocol.poll(""))
        assertEquals(-123, WatchProtocol.poll("ah=4294967173").arrowHash)
        assertEquals(LatLng(12.97, 77.59), WatchProtocol.poll("la=1297000.0&lo=7759000.0").position)
        // No fix (0,0), half a position, or garbage: no position.
        assertEquals(null, WatchProtocol.poll("la=0&lo=0").position)
        assertEquals(null, WatchProtocol.poll("la=1297000").position)
        assertEquals(null, WatchProtocol.poll("la=NaN&lo=7759000").position)
        assertEquals(null, WatchProtocol.poll("la=99999999&lo=7759000").position)
        assertEquals(null, WatchProtocol.poll("ah=x&rv=").arrowHash)
        // NavMap 0.6 asks for the compact form.
        assertTrue(WatchProtocol.poll("k=a&ah=1&rv=2&c=1").compact)
        assertFalse(WatchProtocol.poll("k=a&ah=1&rv=2").compact)
        assertFalse(WatchProtocol.poll("c=0").compact)
        // NavMap 0.12 names its run.
        assertEquals(-81234, WatchProtocol.poll("k=a&s=-81234").session)
        assertEquals(null, WatchProtocol.poll("k=a").session)
        assertFalse(WatchProtocol.poll("c=yes").compact)
        // NavMap 0.9 says which Google Maps key it has.
        assertEquals("YZ_789", WatchProtocol.poll("k=a&kf=YZ_789").keyTail)
        assertEquals("none", WatchProtocol.poll("k=a&kf=none").keyTail)
        assertEquals(null, WatchProtocol.poll("k=a&ah=1").keyTail)
    }

    @Test
    fun readsQueryStrings() {
        assertEquals(mapOf("a" to "1", "b" to "x y", "c" to "", "d" to "é&"), WatchProtocol.query("a=1&b=x+y&c&d=%C3%A9%26&&a=2"))
        // A broken escape drops only that parameter.
        assertEquals(mapOf("ok" to "1"), WatchProtocol.query("bad=%zz&ok=1"))
    }
}
