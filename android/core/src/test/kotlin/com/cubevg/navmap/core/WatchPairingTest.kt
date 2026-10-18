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

    @Test
    fun fingerprintIsSha256sFirstHalf() {
        // printf %s 0123456789abcdef0123456789abcdef | sha256sum
        assertEquals("3eb1bd439947eb762998e566ccc2e099", WatchPairing.fingerprint(watch))
    }

    @Test
    fun aWatchThatSaidHelloReplacesThePairedOneWhilePairingIsOpen() {
        val p = pairing(kept = watch)
        assertFalse(p.offer(WatchPairing.fingerprint(watch)))
        assertFalse(p.offer("not a fingerprint"))
        // A hello with the token itself doesn't count.
        assertFalse(p.offer(other.take(16)))
        p.openWindow()
        assertFalse(p.accepts(other))
        // Not while pairing is closed.
        p.openWindow(0)
        assertTrue(p.offer(WatchPairing.fingerprint(other).uppercase()))
        assertFalse(p.accepts(other))
        p.openWindow()
        // Nor a token that wasn't in the hello.
        assertFalse(p.accepts("00112233445566778899aabbccddeeff"))
        assertTrue(p.accepts(other))
        assertEquals(listOf<String?>(other), saved)
        assertFalse(p.accepts(watch))
        assertTrue(p.accepts(other))
        // With no watch paired, a hello isn't needed, as before.
        val fresh = pairing()
        fresh.openWindow()
        assertTrue(fresh.accepts(watch))
    }
}
