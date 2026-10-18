package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    // The worked example from Google's encoded polyline documentation.
    private val example = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val examplePoints = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))

    @Test
    fun decode() {
        assertEquals(examplePoints, Polyline.decode(example))
        assertEquals(emptyList<LatLng>(), Polyline.decode(""))
    }

    @Test
    fun encode() {
        assertEquals(example, Polyline.encode(examplePoints))
        assertEquals(examplePoints, Polyline.decode(Polyline.encode(examplePoints)))
    }

    @Test
    fun simplifyDropsPointsOnAStraightLine() {
        val line = (0..100).map { LatLng(51.5, -0.1 + it * 0.0001) }
        assertEquals(listOf(line.first(), line.last()), Polyline.simplify(line, 3.0))
    }

    @Test
    fun simplifyKeepsCorners() {
        val corner = (0..50).map { LatLng(51.5, -0.1 + it * 0.0001) } + (1..50).map { LatLng(51.5 + it * 0.0001, -0.095) }
        val simple = Polyline.simplify(corner, 3.0)
        assertEquals(3, simple.size)
        assertEquals(LatLng(51.5, -0.095), simple[1])
    }

    @Test
    fun forWatchFitsTheLimit() {
        // A 20 km zig-zag with 4000 points.
        val wiggly = (0 until 4000).map { LatLng(48.0 + it * 0.00005, 11.0 + if (it % 2 == 0) 0.0 else 0.0004) }
        val flat = Polyline.forWatch(wiggly, maxPoints = 250)
        assertTrue(flat.size / 2 <= 250)
        assertEquals(4_800_000, flat[0])
        assertEquals(1_100_000, flat[1])
        assertEquals(0, flat.size % 2)
    }

    @Test
    fun distanceToALine() {
        // An east-west line about 1.1 km long at Bangalore's latitude.
        val line = listOf(LatLng(12.97, 77.59), LatLng(12.97, 77.60))
        assertEquals(0.0, Polyline.distanceTo(line, LatLng(12.97, 77.595)), 0.01)
        // 0.0009 degrees north is about 100 m.
        assertEquals(99.5, Polyline.distanceTo(line, LatLng(12.9709, 77.595)), 0.5)
        // Past the end, the distance is to the end point: 0.001 degrees east is about 108 m there.
        assertEquals(108.5, Polyline.distanceTo(line, LatLng(12.97, 77.601)), 0.5)
        // The nearest of several segments counts.
        val bend = line + LatLng(12.98, 77.60)
        assertEquals(0.0, Polyline.distanceTo(bend, LatLng(12.975, 77.60)), 0.01)
        assertEquals(54.2, Polyline.distanceTo(bend, LatLng(12.975, 77.6005)), 0.5)
        assertEquals(Double.POSITIVE_INFINITY, Polyline.distanceTo(emptyList(), LatLng(12.97, 77.59)), 0.0)
        assertEquals(0.0, Polyline.distanceTo(listOf(LatLng(12.97, 77.59)), LatLng(12.97, 77.59)), 0.01)
    }
}
