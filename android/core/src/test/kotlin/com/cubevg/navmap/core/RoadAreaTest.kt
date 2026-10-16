package com.cubevg.navmap.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class RoadAreaTest {
    private fun resource(name: String): String =
        GZIPInputStream(javaClass.getResourceAsStream("/roads/$name")!!).bufferedReader().use { it.readText() }

    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }

    /** The decoded tile lines of the fixture, in the order tools/roads.py processed them. */
    private fun fixtureLines(input: JSONObject): List<RoadArea.TileLine> {
        val arr = input.getJSONArray("lines")
        return (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val tile = l.getJSONArray("tile")
            val pts = l.getJSONArray("points")
            RoadArea.TileLine(tile.getInt(0), tile.getInt(1), l.getInt("class"), DoubleArray(2 * pts.length()) { pts.getJSONArray(it / 2).getDouble(it % 2) })
        }
    }

    private fun assertMessages(name: String, expected: JSONArray, messages: List<Map<String, Any>>) {
        assertEquals(name, expected.length(), messages.size)
        for ((i, m) in messages.withIndex()) {
            val e = expected.getJSONObject(i)
            assertEquals("roads", m["t"])
            assertEquals(name, e.getInt("id"), m["id"])
            assertEquals(i, m["c"])
            assertEquals(name, e.getInt("n"), m["n"])
            assertEquals(name, ints(e.getJSONArray("o")), m["o"])
            assertEquals("$name chunk $i", ints(e.getJSONArray("p")), m["p"])
        }
    }

    /** The Connect IQ SDK hands a Long on as a Monkey C Long, and the watch drops a roads message that holds one. */
    private fun assertOnlyInts(v: Any?) {
        when (v) {
            is Map<*, *> -> v.values.forEach(::assertOnlyInts)
            is List<*> -> v.forEach(::assertOnlyInts)
            is Number -> assertTrue("${v.javaClass.simpleName} $v", v is Int)
        }
    }

    private fun square(input: JSONObject) = RoadArea.square(LatLng(input.getDouble("lat"), input.getDouble("lon")), input.getDouble("size_m"))

    /** Central Bangalore, 2400 m: tools/roads.py build 12.9716 77.5946 --lines … --json … (see resources/roads/README.md). */
    @Test
    fun matchesRoadsPy() {
        val input = JSONObject(resource("blr-lines.json.gz"))
        val square = square(input)
        val messages = RoadArea.messages(square, fixtureLines(input))
        assertMessages("blr", JSONArray(resource("blr-messages.json.gz")), messages)
        messages.forEach(::assertOnlyInts)
    }

    /**
     * The whole pipeline, from real tiles to messages, against roads.py: the bundled square, plus every
     * <name>-lines.json and <name>-messages.json pair in NAVMAP_ROADS_SPOTS (roads.py build … --lines … --json …).
     * Needs the tiles roads.py downloaded, NAVMAP_ROADS_TILES=~/.cache/navmap-roads/<version>; skipped without them.
     */
    @Test
    fun buildsFromTilesLikeRoadsPy() {
        val tiles = System.getenv("NAVMAP_ROADS_TILES")?.let(::File)
        assumeTrue(tiles != null && tiles.isDirectory)
        val spots = mutableListOf(Triple("blr", JSONObject(resource("blr-lines.json.gz")), JSONArray(resource("blr-messages.json.gz"))))
        val more = System.getenv("NAVMAP_ROADS_SPOTS")?.let(::File)?.listFiles { f -> f.name.endsWith("-lines.json") }.orEmpty()
        for (f in more.sorted()) {
            val name = f.name.removeSuffix("-lines.json")
            spots.add(Triple(name, JSONObject(f.readText()), JSONArray(File(f.parentFile, "$name-messages.json").readText())))
        }
        for ((name, input, expected) in spots) {
            val square = square(input)
            val decoded = square.tiles().flatMap { (tx, ty) ->
                val layer = Mvt.lines(File(tiles, "14_${tx}_$ty.pbf").readBytes(), RoadArea.LAYER)
                if (layer == null) emptyList() else RoadArea.tileLines(tx, ty, layer)
            }
            // roads.py also lists parts of one point, which the decoder drops; they never reach a message.
            val lines = fixtureLines(input).filter { it.points.size >= 4 }
            assertEquals(name, lines.size, decoded.size)
            for ((e, d) in lines.zip(decoded)) {
                assertEquals(name, e.cls, d.cls)
                assertArrayEquals(name, e.points, d.points, 0.0)
            }
            assertMessages(name, expected, RoadArea.messages(square, decoded))
        }
    }

    @Test
    fun squareIdAndTiles() {
        val sq = RoadArea.Square(4096 * 3 - 10, 4096 * 5 + 4000, 200)
        assertEquals((((sq.ox.toLong() * 31 + sq.oy) xor 200L) and 0x3FFFFFFF).toInt(), sq.id)
        assertEquals(listOf(2 to 5, 2 to 6, 3 to 5, 3 to 6), sq.tiles())
        // 2400 m at 13°N is about 4124 z18 pixels, centred on the point.
        val bangalore = RoadArea.square(LatLng(12.9716, 77.5946))
        assertEquals(4124, bangalore.size)
        val (cx, cy) = RoadArea.world18(12.9716, 77.5946)
        assertEquals(cx, bangalore.ox + 2062.0, 0.5)
        assertEquals(cy, bangalore.oy + 2062.0, 0.5)
        // Far north the square shrinks to fit 13 bits.
        assertEquals(RoadArea.MAX_SIZE, RoadArea.square(LatLng(70.0, 20.0)).size)
    }

    @Test
    fun clipRoundsHalfToEvenAndSplitsRuns() {
        // In, out over the right edge (at y 51.5) and back in: two runs. 50.5 rounds to 50, 51.5 to 52.
        val runs = RoadArea.clip(doubleArrayOf(-10.0, 50.5, 50.0, 50.5, 150.0, 52.5, 150.0, 80.0, 50.0, 80.0), 100)
        assertEquals(2, runs.size)
        assertArrayEquals(intArrayOf(0, 50, 50, 50, 100, 52), runs[0])
        assertArrayEquals(intArrayOf(100, 80, 50, 80), runs[1])
        assertTrue(RoadArea.clip(doubleArrayOf(-5.0, -5.0, -1.0, 200.0), 100).isEmpty())
    }

    @Test
    fun mergeJoinsSameClassEnds() {
        val merged = RoadArea.merge(
            listOf(
                1 to intArrayOf(0, 0, 10, 0),
                2 to intArrayOf(20, 0, 30, 0),
                1 to intArrayOf(10, 0, 20, 0),
                1 to intArrayOf(0, 10, 0, 0),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(1, merged[0].first)
        assertArrayEquals(intArrayOf(0, 10, 0, 0, 10, 0, 20, 0), merged[0].second)
        assertEquals(2, merged[1].first)
    }

    @Test
    fun simplifyKeepsOnlyPointsBeyondTwoPixels() {
        // (1,-2) is exactly 2 px from the line (0,0)-(3,4): dropped. (1,-3) is 2.6 px away: kept.
        assertArrayEquals(intArrayOf(0, 0, 3, 4), RoadArea.simplify(intArrayOf(0, 0, 1, -2, 3, 4), 2.0))
        assertArrayEquals(intArrayOf(0, 0, 1, -3, 3, 4), RoadArea.simplify(intArrayOf(0, 0, 1, -3, 3, 4), 2.0))
        // A closed loop: distances are measured from the shared end point.
        assertArrayEquals(intArrayOf(5, 5, 8, 9, 5, 5), RoadArea.simplify(intArrayOf(5, 5, 6, 5, 8, 9, 5, 5), 2.0))
    }

    private fun zigzag(tx: Int, ty: Int, y0: Int, n: Int, step: Int) =
        RoadArea.TileLine(tx, ty, 3, DoubleArray(2 * n) { if (it % 2 == 0) tx * 4096.0 + (it / 2) * step else ty * 4096.0 + y0 + (it / 2 % 2) * 10 })

    @Test
    fun longLinesAreSplitAndPacked() {
        val square = RoadArea.Square(100 * 4096, 200 * 4096, 4124)
        val chunks = RoadArea.chunks(square, listOf(zigzag(100, 200, 50, 1100, 3)))
        // 1100 points: 500, 500 and 102, the pieces sharing their end points.
        assertEquals(listOf(102, 500, 500), chunks.map { it.size }.sorted())
        for (c in chunks) {
            assertEquals((1 shl 29) or (3 shl 26), c[0] and (0xF shl 26))
            assertTrue(c.drop(1).all { it ushr 26 == 0 })
        }
        val first = chunks.first { it.size == 500 && (it[0] and 0x1FFF) == 0 }
        assertEquals(50, (first[0] ushr 13) and 0x1FFF)
        assertEquals(3, first[1] and 0x1FFF)
        assertEquals(60, (first[1] ushr 13) and 0x1FFF)
    }

    @Test
    fun atMostThirtyChunksNearestFirst() {
        val square = RoadArea.Square(100 * 4096, 200 * 4096, 4096)
        val lines = (0 until 31).map { k -> zigzag(100, 200, 20 + 100 * k, 500, 8) }
        val chunks = RoadArea.chunks(square, lines)
        assertEquals(RoadArea.MAX_CHUNKS, chunks.size)
        val ys = chunks.map { (it[0] ushr 13) and 0x1FFF }
        // Nearest to the centre (y 2048) first; the farthest line (y 20) is left out.
        assertEquals(2020, ys[0])
        assertTrue(20 !in ys)
    }
}
