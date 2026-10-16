package com.cubevg.navmap.core

/**
 * Which NavMap NavBridge answers. Nothing is built into either app, so one download works for everyone: NavMap makes
 * up its own token the first time it runs and sends it as "k" with every /nav request. While pairing is open, the
 * first well-formed token is kept; after that only that watch gets answers, because they show where the rider is
 * going and carry the Google Maps key, and any app on the phone can connect to 127.0.0.1. NavBridge opens pairing
 * for [WINDOW_MS] when it's on screen, when setup.sh asks, and when it asks the watch to open NavMap.
 */
class WatchPairing(kept: String?, private val save: (String?) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var token: String? = kept?.takeIf(::wellFormed)

    @Volatile
    private var openUntil = 0L

    val paired: Boolean get() = token != null

    /** True while no watch is paired and a new one may pair. */
    val windowOpen: Boolean get() = token == null && clock() < openUntil

    /** Lets a watch pair during the next [forMs], if none is paired yet. */
    fun openWindow(forMs: Long = WINDOW_MS) {
        openUntil = clock() + forMs
    }

    /** Forgets the paired watch and opens pairing, so the next NavMap that asks is kept. */
    @Synchronized
    fun forget() {
        token = null
        save(null)
        openWindow()
    }

    /** True for the paired watch's token. With no watch paired, keeps [given] if pairing is open. */
    @Synchronized
    fun accepts(given: String?): Boolean {
        val kept = token
        if (kept != null) return NavEndpoint.keyMatches(given, kept)
        if (given == null || !wellFormed(given) || clock() >= openUntil) return false
        token = given
        save(given)
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L

        /** NavMap makes 32 hex characters; this also takes the NAVBRIDGE_TOKEN built into NavMap up to 0.8. */
        fun wellFormed(token: String): Boolean =
            token.length in 16..64 && token.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }
}
