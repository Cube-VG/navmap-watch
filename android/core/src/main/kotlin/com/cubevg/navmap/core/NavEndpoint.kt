package com.cubevg.navmap.core

/**
 * NavMap's GET /nav over HTTP (see [WatchProtocol]): checks the path, then that the user has agreed to Google's terms
 * and to the use of their location ([consented]), then the watch's token with [accepts] (see [WatchPairing]), then
 * hands the poll to [onPoll], which returns the reply, or null if it couldn't answer in time.
 */
object NavEndpoint {
    fun answer(
        method: String,
        target: String,
        consented: Boolean,
        accepts: (String?) -> Boolean,
        mapsKey: String,
        onPoll: (WatchProtocol.Poll) -> Map<String, Any>?,
    ): Pair<Int, Map<String, Any>> {
        if (method != "GET" || target.substringBefore('?') != WatchProtocol.HTTP_PATH) return 404 to mapOf("t" to "none")
        // Before anything else, pairing included: until the user agrees, nothing from the poll is read, and the watch
        // gets neither the key nor turns. NavMap then says to open NavBridge.
        if (!consented) return 200 to mapOf("t" to "consent")
        val poll = WatchProtocol.poll(target.substringAfter('?', ""))
        if (!accepts(poll.key)) return 403 to mapOf("t" to "denied")
        val reply = onPoll(poll) ?: return 503 to mapOf("t" to "busy")
        return 200 to withMapsKey(reply, poll.keyTail, mapsKey)
    }

    /**
     * Adds the Google Maps key as "mk" for a NavMap that sent "kf" (0.9 and later: the last 6 characters of the key it
     * has, "none" for none) and has none or another one. Its map pictures need it, and nothing is built into NavMap.
     */
    fun withMapsKey(reply: Map<String, Any>, tail: String?, mapsKey: String): Map<String, Any> =
        if (tail == null || mapsKey.isEmpty() || tail == mapsKey.takeLast(6)) reply else reply + ("mk" to mapsKey)

    /**
     * Compared in constant time, so the key can't be guessed from how long a refusal takes. An empty [expected]
     * accepts anything; [WatchPairing] never passes one.
     */
    fun keyMatches(given: String?, expected: String): Boolean {
        if (expected.isEmpty()) return true
        if (given == null || given.length != expected.length) return false
        var diff = 0
        for (i in given.indices) diff = diff or (given[i].code xor expected[i].code)
        return diff == 0
    }
}
