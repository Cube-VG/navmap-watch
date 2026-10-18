package com.cubevg.navmap.core

/**
 * Which NavMap NavBridge answers. Nothing is built into either app, so one download works for everyone: NavMap makes
 * up its own token the first time it runs and sends it as "k" with every /nav request. While pairing is open, the
 * first well-formed token is kept; after that only that watch gets answers, because they show where the rider is
 * going and carry the Google Maps key, and any app on the phone can connect to 127.0.0.1. NavBridge opens pairing
 * for [WINDOW_MS] when it's on screen, when setup.sh asks, and when it asks the watch to open NavMap.
 *
 * NavMap 0.13+ also sends a fingerprint of its token ([fingerprint]) in the hello it sends through Garmin Connect
 * ([offer]); not the token, as any phone app can listen to those. While pairing is open, a NavMap whose token matches
 * that hello replaces the paired one, so a reinstalled NavMap, or another copy on the watch, pairs without the user
 * tapping Pair. A poll alone still can't replace the paired watch.
 */
class WatchPairing(kept: String?, private val save: (String?) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var token: String? = kept?.takeIf(::wellFormed)

    @Volatile
    private var openUntil = 0L

    @Volatile
    private var offered: String? = null

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

    /** NavMap said hello through Garmin Connect with its token's [fingerprint]. Returns true if it isn't the paired watch's. */
    fun offer(fingerprint: String?): Boolean {
        val f = fingerprint?.lowercase()?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return false
        offered = f
        val kept = token
        return kept != null && !NavEndpoint.keyMatches(f, fingerprint(kept))
    }

    /**
     * True for the paired watch's token. While pairing is open, keeps [given] if no watch is paired, or if NavMap
     * offered it in a hello.
     */
    @Synchronized
    fun accepts(given: String?): Boolean {
        val kept = token
        if (kept != null && NavEndpoint.keyMatches(given, kept)) return true
        if (given == null || !wellFormed(given) || clock() >= openUntil) return false
        if (kept != null && offered?.let { NavEndpoint.keyMatches(fingerprint(given), it) } != true) return false
        token = given
        save(given)
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L

        /** NavMap makes 32 hex characters; this also takes the NAVBRIDGE_TOKEN built into NavMap up to 0.8. */
        /** What NavMap sends for its token in a hello: the first 16 bytes of the token's SHA-256, in lower-case hex. */
        fun fingerprint(token: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }

        fun wellFormed(token: String): Boolean =
            token.length in 16..64 && token.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }
}
