package com.cubevg.navmap.core

import java.net.URLDecoder
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
 *  - roads: {"t":"roads","id":area,"c":chunk,"n":chunks,"o":[ox,oy,size],"p":[point, …]}, one chunk of the
 *           OpenStreetMap road lines in a square around the watch, so NavMap can draw its own map. See [RoadArea].
 *
 * Watch to phone:
 *  - hello: {"t":"hello","v":2[,"la":lat·1e5,"lo":lng·1e5][,"r":1]} when NavMap opens, repeated until the phone
 *           answers. "r":1 asks for roads; without it none are sent.
 *  - pos:   {"t":"pos"[,"la","lo"]}, the answer to "where".
 *  - bye:   {"t":"bye"} when NavMap closes.
 *
 * NavMap also polls NavBridge over HTTP, which works when Connect IQ messaging doesn't: Garmin Connect runs the
 * watch's makeWebRequest on the phone, and plain HTTP to 127.0.0.1 is allowed.
 *  - GET /nav?k=token&ah=arrowHash&rv=routeVersion[&la=lat·1e5&lo=lng·1e5][&c=1][&kf=keyTail] on 127.0.0.1:18165,
 *    with the watch's pairing token (see [WatchPairing]), the hash of the arrow and the version of the route line
 *    the watch has. NavMap 0.9+ adds kf, the last 6 characters of the Google Maps key it has ("none" for none); any
 *    answer then carries "mk", the key itself, while that differs (see [NavEndpoint.withMapsKey]). Any poll means
 *    NavMap is open.
 *  - The reply is always one JSON object (Garmin Connect rejects a top-level array):
 *    {"t":"nav","i","d","dm","e","ah","rv"[,"a":[96 ints]][,"p":[lat·1e5, lng·1e5, …]]} while navigating, with "a"
 *    only when the watch's arrow hash differs and "p" only when its route version does. Route version 0 is no
 *    route; a cleared route gets a new version and "p":[]. Then {"t":"end","ar":arrived} for a minute after
 *    navigation ends, and {"t":"idle"} otherwise.
 *  - With c=1 (NavMap 0.6+) the answer while navigating is compact, see [NavCodec]: {"n":"dm|ah|rv|d|e|i"} with
 *    "A" (arrow runs) instead of "a" and "P" (encoded polyline) instead of "p", under the same rules; "" clears
 *    either. "end" and "idle" don't change.
 */
object WatchProtocol {
    const val VERSION = 2
    const val HTTP_PORT = 18165
    const val HTTP_PATH = "/nav"
    const val END_SHOWN_MS = 60_000L

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

    fun roads(area: Int, chunk: Int, chunks: Int, square: RoadArea.Square, points: List<Int>): Map<String, Any> =
        linkedMapOf(
            "t" to "roads",
            "id" to area,
            "c" to chunk,
            "n" to chunks,
            "o" to listOf(square.ox, square.oy, square.size),
            "p" to points,
        )

    /** The answer to a poll while navigating: "a" only if the watch's arrow is stale, "p" only if its route line is. */
    fun navReply(info: NavInfo, arrow: IntArray?, arrowHash: Int, route: List<Int>?, routeVersion: Int, poll: Poll): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "t" to "nav",
            "i" to info.instruction,
            "d" to (info.distance?.text ?: ""),
            "dm" to (info.distance?.meters ?: -1),
            "e" to info.trip,
            "ah" to arrowHash,
            "rv" to routeVersion,
        )
        if (poll.arrowHash != arrowHash) m["a"] = arrow?.toList() ?: emptyList<Int>()
        if (poll.routeVersion != routeVersion) m["p"] = route ?: emptyList<Int>()
        return m
    }

    /** [navReply] for NavMap 0.6+, which asks with c=1: the turn as one line, the arrow and route line as text. */
    fun compactNavReply(info: NavInfo, arrow: IntArray?, arrowHash: Int, routeText: String?, routeVersion: Int, poll: Poll): Map<String, Any> {
        val line = NavCodec.navLine(info.distance?.meters ?: -1, arrowHash, routeVersion, info.distance?.text ?: "", info.trip, info.instruction)
        val m = linkedMapOf<String, Any>("n" to line)
        if (poll.arrowHash != arrowHash) m["A"] = arrow?.let(NavCodec::arrow) ?: ""
        if (poll.routeVersion != routeVersion) m["P"] = routeText ?: ""
        return m
    }

    /** The answer to a poll when Maps isn't navigating: "end" for a minute after navigation ended, then "idle". */
    fun notNavigatingReply(endedAgoMs: Long?, arrived: Boolean): Map<String, Any> =
        if (endedAgoMs != null && endedAgoMs in 0 until END_SHOWN_MS) end(arrived) else idle()

    /** NavMap's GET /nav, read from its query string. Missing or unreadable values are null. */
    data class Poll(
        val key: String?,
        val arrowHash: Int?,
        val routeVersion: Int?,
        val position: LatLng?,
        val compact: Boolean = false,
        val keyTail: String? = null,
    )

    fun poll(query: String?): Poll {
        val q = query(query)
        return Poll(
            key = q["k"],
            // Read as Long: a hash written unsigned still matches.
            arrowHash = q["ah"]?.toLongOrNull()?.toInt(),
            routeVersion = q["rv"]?.toIntOrNull(),
            position = position(q["la"]?.toDoubleOrNull(), q["lo"]?.toDoubleOrNull()),
            compact = q["c"] == "1",
            keyTail = q["kf"],
        )
    }

    /** The parameters of a URL query string. A name given twice keeps its first value. */
    fun query(query: String?): Map<String, String> {
        val params = linkedMapOf<String, String>()
        for (part in query.orEmpty().split('&')) {
            if (part.isEmpty()) continue
            val name = decode(part.substringBefore('=')) ?: continue
            val value = decode(part.substringAfter('=', "")) ?: continue
            params.putIfAbsent(name, value)
        }
        return params
    }

    private fun decode(s: String): String? =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

    sealed class FromWatch {
        data class Hello(val version: Int, val position: LatLng?, val roads: Boolean = false) : FromWatch()

        data class Position(val position: LatLng?) : FromWatch()

        data object Bye : FromWatch()

        data class Unknown(val raw: Any?) : FromWatch()
    }

    /** Reads one message from the watch. The SDK hands over maps with Integer or Long numbers. */
    fun parse(raw: Any?): FromWatch {
        val map = raw as? Map<*, *> ?: return FromWatch.Unknown(raw)
        return when (map["t"]) {
            "hello" -> FromWatch.Hello((map["v"] as? Number)?.toInt() ?: 1, position(map), (map["r"] as? Number)?.toInt() == 1)
            "pos" -> FromWatch.Position(position(map))
            "bye" -> FromWatch.Bye
            else -> FromWatch.Unknown(raw)
        }
    }

    fun toE5(p: LatLng): Pair<Int, Int> = (p.lat * 1e5).roundToInt() to (p.lng * 1e5).roundToInt()

    private fun position(map: Map<*, *>): LatLng? = position((map["la"] as? Number)?.toDouble(), (map["lo"] as? Number)?.toDouble())

    /** A position in lat·1e5 and lng·1e5; 0,0 is what the watch reports without a GPS fix. */
    private fun position(la: Double?, lo: Double?): LatLng? {
        if (la == null || lo == null) return null
        val p = LatLng(la / 1e5, lo / 1e5)
        return if (p.lat in -90.0..90.0 && p.lng in -180.0..180.0 && !(la == 0.0 && lo == 0.0)) p else null
    }
}
