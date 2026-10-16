package com.cubevg.navmap.core

import kotlin.math.roundToInt

/**
 * Messages between NavBridge and the NavMap watch app. Each is a small map with a type in "t".
 * Arrays are sent as lists: the Connect IQ serializer takes List<Int>, not int[].
 *
 * Phone to watch:
 *  - nav:   {"t":"nav","s":seq,"i":instruction,"d":"350 m","dm":350,"e":"Arrive 10:01","ah":hash[,"a":[96 ints]]}
 *           "a" is only sent when the arrow changed; "a":[] with "ah":0 clears it.
 *  - route: {"t":"route","p":[lat·1e5, lng·1e5, …]}, an empty list clears the line.
 *  - end:   {"t":"end","ar":arrived}, navigation finished; the watch says "Arrived" or "Navigation ended", then closes.
 *  - where: {"t":"where"}, asks for the watch's GPS position.
 *  - idle:  {"t":"idle"}, the answer to a hello when Google Maps isn't navigating.
 *
 * Watch to phone:
 *  - hello: {"t":"hello","v":2[,"la":lat·1e5,"lo":lng·1e5]} when NavMap opens, repeated until the phone answers.
 *  - pos:   {"t":"pos"[,"la","lo"]}, the answer to "where".
 *  - bye:   {"t":"bye"} when NavMap closes.
 */
object WatchProtocol {
    const val VERSION = 2

    fun nav(seq: Int, info: NavInfo, arrow: IntArray?, arrowHash: Int, sendArrow: Boolean): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "t" to "nav",
            "s" to seq,
            "i" to info.instruction,
            "d" to (info.distance?.text ?: ""),
            "dm" to (info.distance?.meters ?: -1),
            "e" to info.trip,
            "ah" to arrowHash,
        )
        if (sendArrow) m["a"] = arrow?.toList() ?: emptyList<Int>()
        return m
    }

    fun route(points: List<Int>): Map<String, Any> = mapOf("t" to "route", "p" to points)

    fun end(arrived: Boolean): Map<String, Any> = mapOf("t" to "end", "ar" to arrived)

    fun where(): Map<String, Any> = mapOf("t" to "where")

    fun idle(): Map<String, Any> = mapOf("t" to "idle")

    sealed class FromWatch {
        data class Hello(val version: Int, val position: LatLng?) : FromWatch()

        data class Position(val position: LatLng?) : FromWatch()

        data object Bye : FromWatch()

        data class Unknown(val raw: Any?) : FromWatch()
    }

    /** Reads one message from the watch. The SDK hands over maps with Integer or Long numbers. */
    fun parse(raw: Any?): FromWatch {
        val map = raw as? Map<*, *> ?: return FromWatch.Unknown(raw)
        return when (map["t"]) {
            "hello" -> FromWatch.Hello((map["v"] as? Number)?.toInt() ?: 1, position(map))
            "pos" -> FromWatch.Position(position(map))
            "bye" -> FromWatch.Bye
            else -> FromWatch.Unknown(raw)
        }
    }

    fun toE5(p: LatLng): Pair<Int, Int> = (p.lat * 1e5).roundToInt() to (p.lng * 1e5).roundToInt()

    private fun position(map: Map<*, *>): LatLng? {
        val la = (map["la"] as? Number)?.toDouble() ?: return null
        val lo = (map["lo"] as? Number)?.toDouble() ?: return null
        val p = LatLng(la / 1e5, lo / 1e5)
        return if (p.lat in -90.0..90.0 && p.lng in -180.0..180.0 && !(la == 0.0 && lo == 0.0)) p else null
    }
}
