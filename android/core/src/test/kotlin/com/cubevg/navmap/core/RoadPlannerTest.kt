package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoadPlannerTest {
    private val start = LatLng(12.9716, 77.5946)

    private fun east(m: Double) = RoadPlanner.offset(start, m, 0.0)

    @Test
    fun offsetAndDistanceAgree() {
        val p = RoadPlanner.offset(start, 300.0, -400.0)
        assertEquals(500.0, RoadPlanner.distance(start, p), 0.01)
        val (e, n) = RoadPlanner.metres(start, p)
        assertEquals(300.0, e, 0.01)
        assertEquals(-400.0, n, 0.01)
    }

    @Test
    fun firstSquareIsCentredOnTheWatch() {
        val planner = RoadPlanner()
        assertNull(planner.nextCentre(null, false))
        planner.onPosition(start, 0)
        assertEquals(start, planner.nextCentre(null, false))
        // Standing still: the square will do.
        planner.onPosition(start, 15_000)
        assertNull(planner.nextCentre(start, false))
    }

    @Test
    fun movingLooksThirtySecondsAhead() {
        val planner = RoadPlanner()
        planner.onPosition(start, 0)
        planner.onPosition(east(150.0), 15_000) // 10 m/s east
        assertEquals(10.0, planner.speed, 0.01)
        // 300 m ahead is 450 m from the first square's centre: not yet.
        assertEquals(450.0, RoadPlanner.distance(start, planner.ahead()!!), 0.1)
        assertNull(planner.nextCentre(start, false))
        planner.onPosition(east(300.0), 30_000)
        val next = assertNotNullAnd(planner.nextCentre(start, false))
        assertEquals(600.0, RoadPlanner.distance(start, next), 0.1)
        assertEquals(0.0, RoadPlanner.metres(start, next).second, 0.1)
    }

    @Test
    fun aheadIsCappedAndForgottenAfterAGap() {
        val planner = RoadPlanner()
        planner.onPosition(start, 0)
        planner.onPosition(east(750.0), 15_000) // 50 m/s
        assertEquals(750.0 + RoadPlanner.MAX_AHEAD_M, RoadPlanner.distance(start, planner.ahead()!!), 0.5)
        // Positions closer than 4 s apart don't change the speed.
        planner.onPosition(east(760.0), 16_000)
        assertEquals(50.0, planner.speed, 0.01)
        // After more than a minute without positions the speed is unknown.
        planner.onPosition(east(800.0), 90_000)
        assertEquals(0.0, planner.speed, 0.0)
        assertEquals(east(800.0), planner.ahead())
    }

    @Test
    fun aPartlySentSquareIsFinishedUnlessTheRiderLeftIt() {
        val planner = RoadPlanner()
        planner.onPosition(start, 0)
        planner.onPosition(east(150.0), 15_000)
        planner.onPosition(east(600.0), 30_000) // 30 m/s, 900 m ahead
        assertNull(planner.nextCentre(start, true))
        assertNotNull(planner.nextCentre(start, false))
        planner.onPosition(east(1050.0), 45_000)
        assertNotNull(planner.nextCentre(start, true))
    }

    private fun assertNotNullAnd(p: LatLng?): LatLng {
        assertNotNull(p)
        return p!!
    }
}
