package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPairingTest {
    private val watch = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private var now = 1_000_000L
    private val saved = mutableListOf<String?>()

    private fun pairing(kept: String? = null) = WatchPairing(kept, { saved += it }, { now })

    @Test
    fun keepsTheFirstWatchWhilePairingIsOpen() {
        val p = pairing()
        // Closed until NavBridge opens it.
        assertFalse(p.accepts(watch))
        assertFalse(p.paired)
        p.openWindow()
        assertTrue(p.windowOpen)
        assertTrue(p.accepts(watch))
        assertEquals(listOf<String?>(watch), saved)
        assertTrue(p.paired)
        assertFalse(p.windowOpen)
        // From then on only that watch, even with pairing opened again.
        p.openWindow()
        assertFalse(p.accepts(other))
        assertFalse(p.accepts(null))
        assertTrue(p.accepts(watch))
        assertEquals(1, saved.size)
    }

    @Test
    fun pairingCloses() {
        val p = pairing()
        p.openWindow()
        now += WatchPairing.WINDOW_MS
        assertFalse(p.windowOpen)
        assertFalse(p.accepts(watch))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun takesOnlyWellFormedTokens() {
        val p = pairing()
        p.openWindow()
        for (bad in listOf(null, "", "short", "x".repeat(65), "0123456789abcdef-123", "0123456789abcdé0")) {
            assertFalse("took $bad", p.accepts(bad))
        }
        assertFalse(p.paired)
        // The token that NavMap 0.8 had built in still pairs.
        assertTrue(p.accepts("A1b2C3d4E5f6G7h8"))
    }

    @Test
    fun remembersAndForgets() {
        val p = pairing(kept = watch)
        assertTrue(p.paired)
        assertTrue(p.accepts(watch))
        assertFalse(p.accepts(other))
        // Pair again: the next watch to ask is kept, for the next 10 minutes.
        p.forget()
        assertEquals(listOf<String?>(null), saved)
        assertTrue(p.windowOpen)
        assertTrue(p.accepts(other))
        assertFalse(p.accepts(watch))
        // A broken saved token counts as none.
        assertFalse(pairing(kept = "not a token").paired)
    }
}
