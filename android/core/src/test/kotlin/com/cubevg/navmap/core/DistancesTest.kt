package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistancesTest {
    private fun meters(s: String) = Distances.parse(s)?.meters

    @Test
    fun metric() {
        assertEquals(350, meters("350 m"))
        assertEquals(350, meters("350m"))
        assertEquals(1200, meters("1.2 km"))
        assertEquals(1200, meters("1,2 km"))
        assertEquals(800, meters("800 metres"))
        assertEquals(150, meters("150 公尺"))
        assertEquals(1900, meters("1.9 公里"))
        assertEquals(300, meters("300 м"))
    }

    @Test
    fun imperial() {
        assertEquals(152, meters("500 ft"))
        assertEquals(152, meters("500 pi"))
        assertEquals(46, meters("50 yd"))
        assertEquals(483, meters("0.3 mi"))
        assertEquals(305, meters("1,000 ft"))
    }

    @Test
    fun keepsTextAsShown() {
        assertEquals(Distance("350 m", 350), Distances.parse("‎350 m"))
        assertEquals(Distance("2,6 km", 2600), Distances.parse("2,6 km"))
    }

    @Test
    fun rejectsOtherText() {
        assertNull(Distances.parse("Turn left"))
        assertNull(Distances.parse("5 min"))
        assertNull(Distances.parse("12:30"))
        assertNull(Distances.parse("350 m · Turn left"))
        assertNull(Distances.parse(""))
        assertNull(Distances.parse(null))
    }
}
