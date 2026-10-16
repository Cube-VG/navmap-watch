package com.cubevg.navmap.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

data class LatLng(val lat: Double, val lng: Double)

/** Google's encoded polylines, simplification, and the compact form NavMap draws as the blue route line. */
object Polyline {
    /** NavMap redraws the whole line every second, so keep it short. */
    const val MAX_WATCH_POINTS = 250

    fun decode(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0
        var lng = 0
        fun next(): Int? {
            var result = 0
            var shift = 0
            while (i < encoded.length) {
                val b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            return null
        }
        while (i < encoded.length) {
            val dLat = next() ?: break
            val dLng = next() ?: break
            lat += dLat
            lng += dLng
            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }

    fun encode(points: List<LatLng>): String {
        val sb = StringBuilder()
        var pLat = 0
        var pLng = 0
        fun put(v: Int) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f)) + 63).toChar())
                x = x ushr 5
            }
            sb.append((x + 63).toChar())
        }
        for (p in points) {
            val lat = (p.lat * 1e5).roundToInt()
            val lng = (p.lng * 1e5).roundToInt()
            put(lat - pLat)
            put(lng - pLng)
            pLat = lat
            pLng = lng
        }
        return sb.toString()
    }

    /** Douglas–Peucker: drops points that lie within [toleranceM] metres of the simplified line. */
    fun simplify(points: List<LatLng>, toleranceM: Double): List<LatLng> {
        if (points.size <= 2) return points
        val kx = 111_320.0 * cos(Math.toRadians(points[0].lat))
        val ky = 110_574.0
        val xs = DoubleArray(points.size) { points[it].lng * kx }
        val ys = DoubleArray(points.size) { points[it].lat * ky }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = segmentDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Flattens a route to [lat·1e5, lng·1e5, …], simplifying harder until it fits [maxPoints]. */
    fun forWatch(points: List<LatLng>, maxPoints: Int = MAX_WATCH_POINTS): List<Int> {
        val distinct = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }
        var tolerance = 3.0
        var line = simplify(distinct, tolerance)
        while (line.size > maxPoints) {
            tolerance *= 2
            line = simplify(distinct, tolerance)
        }
        return line.flatMap { listOf((it.lat * 1e5).roundToInt(), (it.lng * 1e5).roundToInt()) }
    }

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
