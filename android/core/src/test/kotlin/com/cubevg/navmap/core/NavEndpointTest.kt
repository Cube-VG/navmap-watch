package com.cubevg.navmap.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** NavMap's /nav through a real [LoopbackServer], over HTTP like Garmin Connect makes it. */
class NavEndpointTest {
    private val key = "0123456789abcdef0123456789abcdef"

    // The user's Google Maps key as NavBridge keeps it; "" until set up. Not a real one.
    private var mapsKey = ""

    // Whether the user has agreed to Google's terms and to the use of their location in NavBridge.
    private var consented = true
    private val info = NavInfo(NavKind.NAVIGATING, "Turn right onto Café Road", Distance("150 m", 150), "Arrive 10:01", "10:01")
    private val arrow = IntArray(96) { it * 7919 - 300_000 }
    private val polls = CopyOnWriteArrayList<WatchProtocol.Poll>()

    private val route = listOf(1297000, 7759000, 1297100, 7759100)

    // What NavSession would answer: navigating, with an arrow and route line version 42.
    private var onPoll: (WatchProtocol.Poll) -> Map<String, Any>? = { poll ->
        polls += poll
        if (poll.compact) {
            WatchProtocol.compactNavReply(info, arrow, 555, NavCodec.route(route.toIntArray()), 42, poll)
        } else {
            WatchProtocol.navReply(info, arrow, 555, route, 42, poll)
        }
    }
    private lateinit var server: LoopbackServer
    private var port = -1

    @Before
    fun start() {
        val listening = CountDownLatch(1)
        server = LoopbackServer(0, { method, target ->
            NavEndpoint.answer(method, target, consented, { NavEndpoint.keyMatches(it, key) }, mapsKey) { onPoll(it) }
        }) {
            if (it.startsWith("Listening")) listening.countDown()
        }
        server.start()
        assertTrue("server didn't start", listening.await(5, TimeUnit.SECONDS))
        port = server.localPort
    }

    @After
    fun stop() = server.stop()

    private class Reply(val code: Int, val type: String?, val body: String)

    private fun get(path: String): Reply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 5_000
        try {
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            return Reply(code, conn.contentType, stream.use { String(it.readBytes(), Charsets.US_ASCII) })
        } finally {
            conn.disconnect()
        }
    }

    /** The bytes on the wire, read until the server closes the connection. */
    private fun raw(request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            val out = ByteArrayOutputStream()
            s.getInputStream().copyTo(out)
            String(out.toByteArray(), Charsets.ISO_8859_1)
        }

    @Test
    fun answersPolls() {
        val first = get("/nav?k=$key&ah=0&rv=0&la=1297000&lo=7759000")
        assertEquals(200, first.code)
        assertEquals("application/json", first.type)
        val json = JSONObject(first.body)
        assertEquals("nav", json.getString("t"))
        assertEquals("Turn right onto Café Road", json.getString("i"))
        assertEquals(150, json.getInt("dm"))
        assertEquals(555, json.getInt("ah"))
        assertEquals(42, json.getInt("rv"))
        assertEquals(arrow.toList(), json.getJSONArray("a").toList())
        assertEquals(route, json.getJSONArray("p").toList())
        assertEquals(WatchProtocol.Poll(key, 0, 0, LatLng(12.97, 77.59)), polls.single())

        // The watch now has both: only the turn comes back.
        val next = JSONObject(get("/nav?k=$key&ah=555&rv=42").body)
        assertFalse(next.has("a"))
        assertFalse(next.has("p"))
        assertEquals(null, polls.last().position)
    }

    @Test
    fun answersCompactPolls() {
        val reply = get("/nav?k=$key&ah=0&rv=0&c=1")
        assertEquals(200, reply.code)
        // Ascii on the wire; the arrow text holds backslashes, which JSON escapes.
        assertTrue(reply.body.all { it.code in 0x20..0x7e })
        val json = JSONObject(reply.body)
        assertEquals(setOf("n", "A", "P"), json.keySet())
        assertEquals("150|555|42|150 m|Arrive 10:01|Turn right onto Caf\u00e9 Road", json.getString("n"))
        assertEquals(NavCodec.arrow(arrow), json.getString("A"))
        assertEquals(route, Polyline.decode(json.getString("P")).flatMap { listOf(WatchProtocol.toE5(it).first, WatchProtocol.toE5(it).second) })
        assertTrue(polls.single().compact)

        assertEquals(setOf("n"), JSONObject(get("/nav?k=$key&ah=555&rv=42&c=1").body).keySet())
        // Without c=1, the 0.4 answer.
        assertEquals("nav", JSONObject(get("/nav?k=$key&ah=555&rv=42").body).getString("t"))
    }

    @Test
    fun handsOverTheMapsKey() {
        mapsKey = "test-maps-key-0123456789-abcdefXYZ_789"
        // NavMap 0.9 without a key, then with an old one: the key comes along, in either form of the answer.
        assertEquals(mapsKey, JSONObject(get("/nav?k=$key&ah=555&rv=42&kf=none").body).getString("mk"))
        assertEquals(setOf("n", "mk"), JSONObject(get("/nav?k=$key&ah=555&rv=42&c=1&kf=Q12345").body).keySet())
        // Once the watch has it, or for NavMap 0.8, which sends no kf and has the key built in: not again.
        assertFalse(JSONObject(get("/nav?k=$key&ah=555&rv=42&c=1&kf=YZ_789").body).has("mk"))
        assertFalse(JSONObject(get("/nav?k=$key&ah=555&rv=42&c=1").body).has("mk"))
        // A refused watch never sees it.
        assertEquals("""{"t":"denied"}""", get("/nav?k=${key.reversed()}&kf=none").body)
        // Nothing to hand over before the user has set one.
        mapsKey = ""
        assertFalse(JSONObject(get("/nav?k=$key&ah=555&rv=42&kf=none").body).has("mk"))
        assertEquals(mapOf("t" to "idle"), NavEndpoint.withMapsKey(mapOf("t" to "idle"), "", ""))
    }

    @Test
    fun asksForConsentFirst() {
        consented = false
        mapsKey = "test-maps-key-0123456789-abcdefXYZ_789"
        // Paired or not, with or without a position: no key, no turns, and NavSession never sees the poll.
        val reply = get("/nav?k=$key&ah=0&rv=0&c=1&kf=none&la=1297000&lo=7759000")
        assertEquals(200, reply.code)
        assertEquals("""{"t":"consent"}""", reply.body)
        assertEquals("""{"t":"consent"}""", get("/nav?k=${key.reversed()}&kf=none").body)
        assertEquals(404, get("/navigation?k=$key").code)
        assertTrue("polls without consent reached NavSession", polls.isEmpty())

        consented = true
        assertEquals(mapsKey, JSONObject(get("/nav?k=$key&ah=555&rv=42&kf=none").body).getString("mk"))
        assertEquals(1, polls.size)
    }

    @Test
    fun refusesOtherRequests() {
        assertEquals(403, get("/nav?ah=0&rv=0").code)
        assertEquals("""{"t":"denied"}""", get("/nav?k=${key.reversed()}&ah=0").body)
        assertEquals(403, get("/nav?k=${key}0").code)
        assertEquals(404, get("/").code)
        assertEquals(404, get("/nav/x?k=$key").code)
        assertEquals(404, get("/navigation?k=$key").code)
        assertTrue(raw("POST /nav?k=$key HTTP/1.1\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 404 "))
        assertTrue(raw("nonsense\r\n\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue("refused requests reached NavSession", polls.isEmpty())
    }

    @Test
    fun busyAndBroken() {
        onPoll = { null }
        assertEquals(503, get("/nav?k=$key").code)
        onPoll = { throw IllegalStateException("bug") }
        assertEquals(500, get("/nav?k=$key").code)
        // Still serving after both.
        onPoll = { WatchProtocol.idle() }
        assertEquals("""{"t":"idle"}""", get("/nav?k=$key").body)
    }

    @Test
    fun wireFormat() {
        val reply = raw("GET http://127.0.0.1:$port/nav?k=$key&ah=555&rv=42 HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n\r\n")
        val (head, body) = reply.split("\r\n\r\n", limit = 2)
        val lines = head.split("\r\n")
        assertEquals("HTTP/1.1 200 OK", lines[0])
        assertTrue(lines.contains("Content-Type: application/json"))
        assertTrue(lines.contains("Content-Length: ${body.length}"))
        assertTrue(lines.contains("Connection: close"))
        assertTrue(lines.contains("Cache-Control: no-store"))
        // Ascii only, whatever the street is called.
        assertTrue(body.contains("Caf\\u00e9 Road"))
        assertTrue(body.all { it.code in 0x20..0x7e })
        // Bare \n line ends work too.
        assertTrue(raw("GET /nav?k=$key HTTP/1.0\n\n").startsWith("HTTP/1.1 200 OK\r\n"))
    }

    @Test
    fun survivesBadClients() {
        // Connects and goes away, sends half a request and goes away, sends a huge head.
        Socket("127.0.0.1", port).close()
        Socket("127.0.0.1", port).use { it.getOutputStream().write("GET /nav?k=$key HTTP/1.1\r\n".toByteArray()) }
        // Too long: refused or cut off, either is fine.
        runCatching { raw("GET /nav HTTP/1.1\r\nX: ${"y".repeat(10_000)}\r\n\r\n") }
        repeat(30) { assertEquals(200, get("/nav?k=$key&ah=555&rv=42").code) }
    }

    @Test
    fun keys() {
        assertTrue(NavEndpoint.keyMatches(key, key))
        assertFalse(NavEndpoint.keyMatches(null, key))
        assertFalse(NavEndpoint.keyMatches("", key))
        assertFalse(NavEndpoint.keyMatches(key.uppercase(), key))
        assertFalse(NavEndpoint.keyMatches(key.dropLast(1), key))
        // An empty expected key takes anything; WatchPairing never passes one.
        assertTrue(NavEndpoint.keyMatches(null, ""))
    }

    @Test
    fun portInUseIsRetriedNotFatal() {
        val statuses = CopyOnWriteArrayList<String>()
        val failed = CountDownLatch(1)
        val second = LoopbackServer(port, { _, _ -> 200 to WatchProtocol.idle() }) {
            statuses += it
            if (it.startsWith("Can't listen")) failed.countDown()
        }
        second.start()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(-1, second.localPort)
        second.stop()
        // The first one is unaffected.
        assertEquals(200, get("/nav?k=$key").code)
    }

    private fun org.json.JSONArray.toList(): List<Int> = (0 until length()).map { getInt(it) }
}
