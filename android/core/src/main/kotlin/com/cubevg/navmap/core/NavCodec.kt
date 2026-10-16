package com.cubevg.navmap.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The compact /nav answer NavMap 0.6 asks for with c=1. A port of tools/navcodec.py, which is the spec; NavCodecTest
 * checks it gives the same text (tools/navcodec-vectors.py). Garmin Connect re-serializes JSON for the watch at 5
 * bytes a number and 1 byte an ASCII character, so the bulky parts travel as text, with the characters '?'..'~'
 * (63..126) standing for 0..63.
 *  - "n": the turn as one line, "dm|ah|rv|d|e|i". The instruction is last, so it may itself hold '|'.
 *  - "A": the 48x48 arrow as runs of rows.
 *  - "P": the route line as a Google encoded polyline, thinned to 2 m.
 */
object NavCodec {
    private const val ZERO = 63

    /** The distance text and arrival time can't hold '|': the watch splits on the first five. */
    fun navLine(dm: Int, ah: Int, rv: Int, d: String, e: String, i: String): String =
        "$dm|$ah|$rv|${d.replace('|', '/')}|${e.replace('|', '/')}|$i"

    /**
     * The 96-int arrow (see [ArrowEncoder]) as runs: blocks of identical rows, each written as its number of rows,
     * its number of runs, then x0 and x1 (exclusive) of each run. "" when no pixel is set.
     */
    fun arrow(bits: IntArray): String {
        require(bits.size == ArrowEncoder.SIZE * 2) { "arrow of ${bits.size} ints" }
        val rows = List(ArrowEncoder.SIZE) { r -> runs(bits[r * 2], bits[r * 2 + 1]) }
        if (rows.all { it.isEmpty() }) return ""
        val sb = StringBuilder()
        var r = 0
        while (r < rows.size) {
            var n = 1
            while (r + n < rows.size && rows[r + n] == rows[r]) n++
            sb.put(n).put(rows[r].size / 2)
            for (x in rows[r]) sb.put(x)
            r += n
        }
        return sb.toString()
    }

    /** One row's filled runs as x0, x1, x0, x1, …: int 0 bit 31 is x0, int 1 bit 15 is x32. */
    private fun runs(w0: Int, w1: Int): List<Int> {
        val out = ArrayList<Int>()
        var start = -1
        for (x in 0..48) {
            val on = when {
                x < 32 -> ((w0 ushr (31 - x)) and 1) == 1
                x < 48 -> ((w1 ushr (47 - x)) and 1) == 1
                else -> false
            }
            if (on && start < 0) {
                start = x
            } else if (!on && start >= 0) {
                out += start
                out += x
                start = -1
            }
        }
        return out
    }

    /**
     * The route line for "P": thinned to 2 m, or harder while it's longer than [maxPoints], the limit of the "p"
     * list, because NavMap redraws every point each second.
     */
    fun routeForWatch(points: List<LatLng>, maxPoints: Int = Polyline.MAX_WATCH_POINTS): String {
        require(maxPoints >= 2)
        val flat = IntArray(points.size * 2) { k ->
            val p = points[k / 2]
            ((if (k % 2 == 0) p.lat else p.lng) * 1e5).roundToInt()
        }
        var tolerance = 2.0
        var line = simplify(flat, tolerance)
        while (line.size / 2 > maxPoints) {
            tolerance *= 2
            line = simplify(flat, tolerance)
        }
        return route(line)
    }

    /**
     * Douglas-Peucker on [lat·1e5, lng·1e5, …]: drops points that move the line by less than [toleranceM] metres.
     * Step for step navcodec.simplify, so both thin a route alike.
     */
    fun simplify(flat: IntArray, toleranceM: Double = 2.0): IntArray {
        val n = flat.size / 2
        if (n < 3) return flat.copyOf()
        // Metres per 1e-5 degree.
        val kx = 1.11320 * cos(flat[0] / 1e5 * (PI / 180.0))
        val xs = DoubleArray(n) { flat[2 * it + 1] * kx }
        val ys = DoubleArray(n) { flat[2 * it] * 1.11320 }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        // Spans still to check, as pairs of point indexes.
        val stack = ArrayDeque(listOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            val a = stack.removeLast()
            val ax = xs[a]
            val ay = ys[a]
            val dx = xs[b] - ax
            val dy = ys[b] - ay
            val ll = dx * dx + dy * dy
            var best = -1.0
            var far = -1
            for (i in a + 1 until b) {
                val px = xs[i]
                val py = ys[i]
                val dist = if (ll == 0.0) {
                    hypot(px - ax, py - ay)
                } else {
                    val t = maxOf(0.0, minOf(1.0, ((px - ax) * dx + (py - ay) * dy) / ll))
                    hypot(px - ax - t * dx, py - ay - t * dy)
                }
                if (dist > best) {
                    best = dist
                    far = i
                }
            }
            if (best > toleranceM) {
                keep[far] = true
                stack.addAll(listOf(a, far, far, b))
            }
        }
        val out = IntArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            out[k++] = flat[2 * i]
            out[k++] = flat[2 * i + 1]
        }
        return out
    }

    /** [lat·1e5, lng·1e5, …] as a Google encoded polyline: the same text PolyUtil.encode gives. */
    fun route(flat: IntArray): String {
        val sb = StringBuilder()
        var lat = 0
        var lng = 0
        var k = 0
        while (k + 1 < flat.size) {
            sb.putDelta(flat[k] - lat)
            sb.putDelta(flat[k + 1] - lng)
            lat = flat[k]
            lng = flat[k + 1]
            k += 2
        }
        return sb.toString()
    }

    private fun StringBuilder.putDelta(d: Int) {
        var z = if (d < 0) (d shl 1).inv() else d shl 1
        while (z >= 0x20) {
            put(0x20 or (z and 0x1f))
            z = z shr 5
        }
        put(z)
    }

    private fun StringBuilder.put(v: Int): StringBuilder = append((ZERO + v).toChar())
}
