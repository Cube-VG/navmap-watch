package com.cubevg.navmap.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The compact /nav encodings against what tools/navcodec.py makes of the same input (tools/navcodec-vectors.py). */
class NavCodecTest {
    private val vectors = JSONObject(javaClass.getResourceAsStream("/navcodec/vectors.json")!!.bufferedReader().use { it.readText() })

    private fun vectors(group: String): List<JSONObject> =
        vectors.getJSONArray(group).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun JSONArray.ints(): IntArray = IntArray(length()) { getInt(it) }

    private fun latLngs(flat: IntArray): List<LatLng> = (0 until flat.size / 2).map { LatLng(flat[2 * it] / 1e5, flat[2 * it + 1] / 1e5) }

    @Test
    fun arrowsAsNavcodecWritesThem() {
        val arrows = vectors("arrows")
        assertTrue(arrows.size > 5)
        for (v in arrows) {
            assertEquals(v.getString("name"), v.getString("A"), NavCodec.arrow(v.getJSONArray("bits").ints()))
        }
    }

    @Test
    fun routesAsNavcodecThinsAndWritesThem() {
        val routes = vectors("routes")
        assertTrue(routes.size > 5)
        for (v in routes) {
            val name = v.getString("name")
            val flat = v.getJSONArray("flat").ints()
            val simple = NavCodec.simplify(flat, 2.0)
            assertArrayEquals(name, v.getJSONArray("simple").ints(), simple)
            assertEquals(name, v.getString("P"), NavCodec.route(simple))
            assertEquals(name, v.getString("full"), NavCodec.route(flat))
            // The same text as a standard encoder, and it reads back.
            assertEquals(name, Polyline.encode(latLngs(flat)), NavCodec.route(flat))
            assertEquals(name, flat.toList(), Polyline.decode(NavCodec.route(flat)).flatMap { listOf((it.lat * 1e5).roundToInt(), (it.lng * 1e5).roundToInt()) })
        }
    }

    @Test
    fun linesAsNavcodecWritesThem() {
        for (v in vectors("lines")) {
            val line = NavCodec.navLine(v.getInt("dm"), v.getInt("ah"), v.getInt("rv"), v.getString("d"), v.getString("e"), v.getString("i"))
            assertEquals(v.getString("n"), line)
        }
    }

    @Test
    fun onlyTheInstructionMayHoldABar() {
        assertEquals("5|1|2|a/b|c/d|e|f", NavCodec.navLine(5, 1, 2, "a|b", "c|d", "e|f"))
    }

    @Test
    fun arrowRunsCoverTheSamePixels() {
        // Read back the way NavMap reads "A" (at 1x), against the bits.
        val random = java.util.Random(4)
        repeat(20) {
            val bits = IntArray(96) { if (it % 2 == 0) random.nextInt() else random.nextInt() and 0xFFFF }
            val text = NavCodec.arrow(bits)
            assertTrue(text.all { it.code in 63..126 })
            val set = Array(48) { BooleanArray(48) }
            val b = text.map { it.code - 63 }
            var i = 0
            var row = 0
            while (i + 1 < b.size) {
                val rows = b[i]
                val runs = b[i + 1]
                i += 2
                repeat(runs) {
                    for (y in row until row + rows) for (x in b[i] until b[i + 1]) set[y][x] = true
                    i += 2
                }
                row += rows
            }
            assertEquals(48, row)
            for (y in 0 until 48) for (x in 0 until 48) assertEquals("$x,$y", ArrowEncoder.isSet(bits, x, y), set[y][x])
        }
    }

    @Test
    fun routeForWatchIsThinnedTo2m() {
        for (v in vectors("routes").filter { it.getString("name").startsWith("ride") }) {
            val points = latLngs(v.getJSONArray("flat").ints())
            assertEquals(v.getString("P"), NavCodec.routeForWatch(points))
        }
        assertEquals("", NavCodec.routeForWatch(emptyList()))
    }

    @Test
    fun longRoutesAreThinnedHarder() {
        // A 40 km ride, about 1000 points at 2 m.
        val random = java.util.Random(9)
        var lat = 12.97633
        var lng = 77.59292
        var heading = 0.0
        val points = (0 until 4000).map { i ->
            heading += random.nextGaussian() * 0.15 + if (i % 120 == 60) Math.PI / 2 else 0.0
            lat += 9e-5 * cos(heading)
            lng += 9e-5 * sin(heading) / cos(Math.toRadians(12.97633))
            LatLng(lat, lng)
        }
        val line = Polyline.decode(NavCodec.routeForWatch(points))
        assertTrue("${line.size} points", line.size in 20..Polyline.MAX_WATCH_POINTS)
        assertEquals(points.first().lat, line.first().lat, 1e-5)
        assertEquals(points.last().lng, line.last().lng, 1e-5)
        val longer = Polyline.decode(NavCodec.routeForWatch(points, 1000))
        assertTrue("${longer.size} points", longer.size in line.size + 1..1000)
    }
}
