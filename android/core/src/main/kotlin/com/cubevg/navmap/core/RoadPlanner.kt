package com.cubevg.navmap.core

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * When NavBridge builds the watch a new road square ([RoadArea]), and where it's centred.
 *
 * A 2400 m square covers NavMap's screen while the rider is within about 970 m of its centre at z16 (below
 * 30 km/h) and 740 m at z15 (30-65 km/h). The next square has to be in before the rider gets that far, which takes
 * up to one position report (the phone asks every 15 s), plus building the square, plus 10-15 KB over Bluetooth.
 *
 * So the planner looks ahead: from the last two positions it estimates where the rider will be in [LOOK_AHEAD_S]
 * seconds, and asks for a square centred there once that point is more than [NEXT_AT_M] from the current
 * square's centre. At 65 km/h the rider is then about 600 m from the old centre when the new square is in, if
 * sending it takes 20 s. A square that is partly sent is finished first, unless the rider has already left it:
 * the watch drops a square that is still arriving as soon as a newer one starts.
 */
class RoadPlanner {
    private var base: LatLng? = null
    private var baseAt = 0L
    private var last: LatLng? = null
    private var vEast = 0.0
    private var vNorth = 0.0

    /** A position from the watch, at [atMs] on a monotonic clock. */
    fun onPosition(p: LatLng, atMs: Long) {
        val b = base
        if (b == null || atMs - baseAt > MAX_GAP_MS) {
            // First position, or too long ago to tell the speed.
            vEast = 0.0
            vNorth = 0.0
            base = p
            baseAt = atMs
        } else if (atMs - baseAt >= MIN_GAP_MS) {
            val dt = (atMs - baseAt) / 1000.0
            val (east, north) = metres(b, p)
            vEast = east / dt
            vNorth = north / dt
            base = p
            baseAt = atMs
        }
        last = p
    }

    /** Forgets the positions, e.g. when NavMap closes. */
    fun reset() {
        base = null
        last = null
        vEast = 0.0
        vNorth = 0.0
    }

    /** Speed from the last two positions, m/s. */
    val speed: Double get() = sqrt(vEast * vEast + vNorth * vNorth)

    /** Where the rider should be in [LOOK_AHEAD_S] seconds (at most [MAX_AHEAD_M] away), or null before any position. */
    fun ahead(): LatLng? {
        val p = last ?: return null
        var east = vEast * LOOK_AHEAD_S
        var north = vNorth * LOOK_AHEAD_S
        val d = sqrt(east * east + north * north)
        if (d > MAX_AHEAD_M) {
            east *= MAX_AHEAD_M / d
            north *= MAX_AHEAD_M / d
        }
        return offset(p, east, north)
    }

    /**
     * Where to centre the next square, or null while the current one will do. [current] is the newest square's
     * centre, or null if there's none; [partlySent] means some but not all of its chunks have reached the watch.
     */
    fun nextCentre(current: LatLng?, partlySent: Boolean): LatLng? {
        val p = last ?: return null
        val ahead = ahead() ?: return null
        return when {
            current == null -> ahead
            partlySent -> if (distance(p, current) > LEFT_M) ahead else null
            else -> if (distance(ahead, current) > NEXT_AT_M) ahead else null
        }
    }

    companion object {
        const val LOOK_AHEAD_S = 30.0
        const val MAX_AHEAD_M = 900.0
        const val NEXT_AT_M = 500.0

        /** Farther than this from its centre, a square no longer covers the screen even at z16. */
        const val LEFT_M = 1000.0
        const val MIN_GAP_MS = 4_000L
        const val MAX_GAP_MS = 60_000L
        private const val EARTH_M = 6_371_008.8
        private const val M_PER_DEG = Math.PI / 180.0 * EARTH_M

        /** Metres east and north from [a] to [b]; flat-earth, which is plenty over a few kilometres. */
        fun metres(a: LatLng, b: LatLng): Pair<Double, Double> =
            (b.lng - a.lng) * M_PER_DEG * cos((a.lat + b.lat) / 2 * Math.PI / 180.0) to (b.lat - a.lat) * M_PER_DEG

        fun distance(a: LatLng, b: LatLng): Double {
            val (east, north) = metres(a, b)
            return sqrt(east * east + north * north)
        }

        fun offset(p: LatLng, east: Double, north: Double): LatLng {
            val lat = p.lat + north / M_PER_DEG
            return LatLng(lat, p.lng + east / (M_PER_DEG * cos((p.lat + lat) / 2 * Math.PI / 180.0)))
        }
    }
}
