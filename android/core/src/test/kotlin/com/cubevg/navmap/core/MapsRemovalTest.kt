package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsRemovalTest {
    @Test
    fun mapsEndingItEndsTheNavigation() {
        assertTrue(MapsRemoval.of(8).mapsStopped) // REASON_APP_CANCEL
        assertTrue(MapsRemoval.of(5).mapsStopped) // REASON_PACKAGE_CHANGED: force stop or update
        assertTrue(MapsRemoval.of(17).mapsStopped) // REASON_CHANNEL_BANNED: no more cards will come
    }

    @Test
    fun aCardGoneWhileMapsNavigatesDoesNot() {
        for (reason in listOf(1, 2, 3, 10, 11, 18, 19, 22, 23)) assertFalse("reason $reason", MapsRemoval.of(reason).mapsStopped)
        assertEquals("it timed out without an update", MapsRemoval.of(19).text)
        assertEquals("it was swiped away", MapsRemoval.of(2).text)
    }

    @Test
    fun unknownReasonsEndAsBefore() {
        val why = MapsRemoval.of(99)
        assertTrue(why.mapsStopped)
        assertEquals("Android gave reason 99", why.text)
    }
}
