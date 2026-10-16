package com.cubevg.navmap.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenStreetMap road lines in a square around the watch, packed into "roads" messages so NavMap can draw its own
 * map. A port of tools/roads.py, step by step and with the same arithmetic, so both give the same messages for
 * the same tiles (RoadAreaTest checks this against roads.py's output).
 *
 * Coordinates are z18 Web Mercator pixels: 256-pixel tiles, so the world is 2^26 pixels wide. A z14 tile is 4096
 * of them, the same as its vector-tile extent, so tile coordinates need no scaling.
 *
 * A point is one Int: bit 29 = first point of a line, bits 26-28 = road class (first point only),
 * bits 13-25 = y - oy, bits 0-12 = x - ox.
 */
object RoadArea {
    const val WORLD = 1 shl 26
    const val TILE_Z = 14
    const val TILE_PX = WORLD shr TILE_Z
    const val LAYER = "transportation"
    const val SIZE_M = 2400.0
    const val MAX_SIZE = 8191
    const val SIMPLIFY_PX = 2.0
    const val CHUNK_POINTS = 500
    const val MAX_CHUNKS = 30

    /** OpenMapTiles road classes NavMap draws. Service roads, paths, tracks and rail aren't sent. */
    val CLASSES = mapOf("minor" to 1, "tertiary" to 2, "secondary" to 3, "primary" to 4, "trunk" to 5, "motorway" to 6)

    /** Top-left corner and side of the square, in z18 pixels. */
    data class Square(val ox: Int, val oy: Int, val size: Int) {
        /** The same square always gets the same id (roads.py's formula). */
        val id: Int get() = (((ox.toLong() * 31 + oy) xor size.toLong()) and 0x3FFFFFFFL).toInt()

        /** The z14 tiles it touches, x-major then y. */
        fun tiles(): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            for (tx in ox / TILE_PX..(ox + size) / TILE_PX) {
                for (ty in oy / TILE_PX..(oy + size) / TILE_PX) out.add(tx to ty)
            }
            return out
        }
    }

    /** A road line as decoded from z14 tile ([tx], [ty]): its class and points in z18 world pixels, x0, y0, x1, y1, … */
    class TileLine(val tx: Int, val ty: Int, val cls: Int, val points: DoubleArray)

    // Python's math.radians multiplies by the constant pi/180; Math.toRadians differs between Java versions.
    private fun radians(deg: Double) = deg * (Math.PI / 180.0)

    fun world18(lat: Double, lng: Double): Pair<Double, Double> {
        val s = sin(radians(lat))
        return (lng + 180.0) / 360.0 * WORLD to (0.5 - ln((1 + s) / (1 - s)) / (4 * Math.PI)) * WORLD
    }

    fun metresPerPx(lat: Double): Double = 40075016.686 * cos(radians(lat)) / WORLD

    /** The square of side [sizeM] centred on [center]. Far north or south it's smaller, so its side fits 13 bits. */
    fun square(center: LatLng, sizeM: Double = SIZE_M): Square {
        val (cx, cy) = world18(center.lat, center.lng)
        val size = Math.rint(sizeM / metresPerPx(center.lat)).toInt().coerceAtMost(MAX_SIZE)
        return Square(Math.rint(cx).toInt() - size / 2, Math.rint(cy).toInt() - size / 2, size)
    }

    /** Step 1 for one tile: its road lines in file order, with their classes. */
    fun tileLines(tx: Int, ty: Int, layer: Mvt.Layer): List<TileLine> {
        val k = TILE_PX.toDouble() / layer.extent
        val out = ArrayList<TileLine>()
        for (line in layer.lines) {
            val cls = CLASSES[line.tags["class"] as? String] ?: continue
            for (part in line.parts) {
                out.add(TileLine(tx, ty, cls, DoubleArray(part.size) { i -> (if (i % 2 == 0) tx else ty) * TILE_PX + part[i] * k }))
            }
        }
        return out
    }

    /** The messages for [square], from the lines of its [Square.tiles] in that order. */
    fun messages(square: Square, lines: List<TileLine>): List<Map<String, Any>> {
        val chunks = chunks(square, lines)
        return chunks.mapIndexed { i, points -> WatchProtocol.roads(square.id, i, chunks.size, square, points) }
    }

    /** Steps 2 to 6: clip to their own tile and then to the square, merge, simplify, sort nearest first, fill chunks. */
    fun chunks(square: Square, lines: List<TileLine>): List<List<Int>> {
        val size = square.size
        val clipped = ArrayList<Pair<Int, IntArray>>()
        for (line in lines) {
            val bx = line.tx * TILE_PX
            val by = line.ty * TILE_PX
            val local = DoubleArray(line.points.size) { i -> line.points[i] - if (i % 2 == 0) bx else by }
            for (part in clip(local, TILE_PX)) {
                val rel = DoubleArray(part.size) { i ->
                    (if (i % 2 == 0) part[i] + bx - square.ox else part[i] + by - square.oy).toDouble()
                }
                for (run in clip(rel, size)) clipped.add(line.cls to run)
            }
        }
        val simplified = merge(clipped).map { (cls, pts) -> cls to simplify(pts, SIMPLIFY_PX) }

        // Lines too long for one chunk are split; the halves share a point.
        val split = ArrayList<Pair<Int, IntArray>>()
        for ((cls, line) in simplified) {
            var pts = line
            while (pts.size / 2 > CHUNK_POINTS) {
                split.add(cls to pts.copyOfRange(0, 2 * CHUNK_POINTS))
                pts = pts.copyOfRange(2 * (CHUNK_POINTS - 1), pts.size)
            }
            split.add(cls to pts)
        }
        val half = size / 2.0
        val sorted = split.sortedBy { (_, pts) ->
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until pts.size / 2) {
                val dx = pts[2 * i] - half
                val dy = pts[2 * i + 1] - half
                val d = dx * dx + dy * dy
                if (d < best) best = d
            }
            best
        }

        val chunks = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for ((cls, pts) in sorted) {
            if (cur.isNotEmpty() && cur.size + pts.size / 2 > CHUNK_POINTS) {
                chunks.add(cur)
                cur = ArrayList()
            }
            for (i in 0 until pts.size / 2) {
                val first = if (i == 0) (1 shl 29) or (cls shl 26) else 0
                cur.add(first or (pts[2 * i + 1] shl 13) or pts[2 * i])
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        return if (chunks.size > MAX_CHUNKS) chunks.subList(0, MAX_CHUNKS) else chunks
    }

    /**
     * Liang-Barsky against [0, size]²: writes the clipped segment to [out] (x0, y0, x1, y1), or returns false if
     * the segment misses the square. Same steps as roads.py's clip_segment, including Python's max/min tie rules.
     */
    private fun clipSegment(x0: Double, y0: Double, x1: Double, y1: Double, size: Double, out: DoubleArray): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0.0
        var t1 = 1.0
        for (k in 0 until 4) {
            val p = when (k) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
            val q = when (k) { 0 -> x0; 1 -> size - x0; 2 -> y0; else -> size - y0 }
            if (p == 0.0) {
                if (q < 0) return false
            } else {
                val t = q / p
                if (p < 0) {
                    if (t > t0) t0 = t
                } else if (t < t1) {
                    t1 = t
                }
            }
        }
        if (t0 > t1) return false
        out[0] = x0 + t0 * dx
        out[1] = y0 + t0 * dy
        out[2] = x0 + t1 * dx
        out[3] = y0 + t1 * dy
        return true
    }

    /**
     * The runs of a line (x0, y0, …) inside [0, size]², each rounded half to even, clamped to the square and
     * without repeated points. Runs shorter than two points are dropped.
     */
    internal fun clip(pts: DoubleArray, size: Int): List<IntArray> {
        val s = size.toDouble()
        val runs = ArrayList<ArrayList<Double>>()
        var cur = ArrayList<Double>()
        val seg = DoubleArray(4)
        for (k in 0 until pts.size / 2 - 1) {
            val bx = pts[2 * k + 2]
            val by = pts[2 * k + 3]
            if (!clipSegment(pts[2 * k], pts[2 * k + 1], bx, by, s, seg)) {
                if (cur.isNotEmpty()) {
                    runs.add(cur)
                    cur = ArrayList()
                }
                continue
            }
            if (cur.isEmpty() || cur[cur.size - 2] != seg[0] || cur[cur.size - 1] != seg[1]) {
                if (cur.isNotEmpty()) runs.add(cur)
                cur = arrayListOf(seg[0], seg[1])
            }
            cur.add(seg[2])
            cur.add(seg[3])
            if (seg[2] != bx || seg[3] != by) { // left the square
                runs.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) runs.add(cur)

        val out = ArrayList<IntArray>()
        for (run in runs) {
            val ints = ArrayList<Int>()
            for (i in 0 until run.size / 2) {
                val x = Math.rint(run[2 * i]).toInt().coerceIn(0, size)
                val y = Math.rint(run[2 * i + 1]).toInt().coerceIn(0, size)
                if (ints.isEmpty() || ints[ints.size - 2] != x || ints[ints.size - 1] != y) {
                    ints.add(x)
                    ints.add(y)
                }
            }
            if (ints.size >= 4) out.add(ints.toIntArray())
        }
        return out
    }

    /**
     * Joins lines of the same class that meet end to end, so shared points are sent once: classes in ascending
     * order, lines in their order; each line grows forward, then backward, taking the first unused line at its end.
     */
    internal fun merge(lines: List<Pair<Int, IntArray>>): List<Pair<Int, IntArray>> {
        val byClass = LinkedHashMap<Int, ArrayList<IntArray>>()
        for ((cls, pts) in lines) byClass.getOrPut(cls) { ArrayList() }.add(pts)
        val out = ArrayList<Pair<Int, IntArray>>()
        for (cls in byClass.keys.sorted()) {
            val group = byClass.getValue(cls)
            val ends = HashMap<Long, ArrayList<Int>>()
            for ((i, pts) in group.withIndex()) {
                ends.getOrPut(key(pts, 0)) { ArrayList() }.add(i)
                ends.getOrPut(key(pts, pts.size / 2 - 1)) { ArrayList() }.add(i)
            }
            val used = BooleanArray(group.size)
            fun take(p: Long, skip: Int): Int {
                for (j in ends[p].orEmpty()) if (!used[j] && j != skip) return j
                return -1
            }
            for ((i, pts) in group.withIndex()) {
                if (used[i]) continue
                used[i] = true
                var line = pts
                for (forward in booleanArrayOf(true, false)) {
                    while (true) {
                        val end = if (forward) key(line, line.size / 2 - 1) else key(line, 0)
                        val j = take(end, i)
                        if (j < 0) break
                        used[j] = true
                        val other = if (key(group[j], 0) == end) group[j] else reversed(group[j])
                        line = if (forward) line + other.copyOfRange(2, other.size) else reversed(other).copyOfRange(0, other.size - 2) + line
                        if (key(line, 0) == key(line, line.size / 2 - 1)) break
                    }
                }
                out.add(cls to line)
            }
        }
        return out
    }

    private fun key(pts: IntArray, i: Int): Long = (pts[2 * i].toLong() shl 32) or pts[2 * i + 1].toLong()

    private fun reversed(pts: IntArray): IntArray {
        val n = pts.size / 2
        return IntArray(pts.size) { i -> pts[2 * (n - 1 - i / 2) + i % 2] }
    }

    /** Douglas-Peucker, keeping points more than [tol] pixels from the line through the ends of each stretch. */
    internal fun simplify(pts: IntArray, tol: Double): IntArray {
        val n = pts.size / 2
        if (n < 3) return pts
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = pts[2 * a]
            val ay = pts[2 * a + 1]
            val dx = (pts[2 * b] - ax).toLong()
            val dy = (pts[2 * b + 1] - ay).toLong()
            // The square root of an exact integer is correctly rounded, so this matches Python to the last bit.
            val length = sqrt((dx * dx + dy * dy).toDouble())
            var best = -1.0
            var bi = -1
            for (i in a + 1 until b) {
                val px = (pts[2 * i] - ax).toLong()
                val py = (pts[2 * i + 1] - ay).toLong()
                val d = if (length != 0.0) abs(dy * px - dx * py).toDouble() / length else sqrt((px * px + py * py).toDouble())
                if (d > best) {
                    best = d
                    bi = i
                }
            }
            if (best > tol) {
                keep[bi] = true
                stack.addLast(a to bi)
                stack.addLast(bi to b)
            }
        }
        val out = IntArray(2 * keep.count { it })
        var j = 0
        for (i in 0 until n) {
            if (keep[i]) {
                out[j++] = pts[2 * i]
                out[j++] = pts[2 * i + 1]
            }
        }
        return out
    }
}
