package com.cubevg.navmap.core

/**
 * NavMap's GET /nav over HTTP (see [WatchProtocol]): checks the path and the key, then hands the poll to [onPoll],
 * which returns the reply, or null if it couldn't answer in time.
 */
object NavEndpoint {
    fun answer(method: String, target: String, key: String, onPoll: (WatchProtocol.Poll) -> Map<String, Any>?): Pair<Int, Map<String, Any>> {
        if (method != "GET" || target.substringBefore('?') != WatchProtocol.HTTP_PATH) return 404 to mapOf("t" to "none")
        val poll = WatchProtocol.poll(target.substringAfter('?', ""))
        if (!keyMatches(poll.key, key)) return 403 to mapOf("t" to "denied")
        val reply = onPoll(poll) ?: return 503 to mapOf("t" to "busy")
        return 200 to reply
    }

    /**
     * Compared in constant time, so the key can't be guessed from how long a refusal takes. An empty [expected] is
     * a build without a key, which accepts anything; tools/build-phone.sh never makes one.
     */
    fun keyMatches(given: String?, expected: String): Boolean {
        if (expected.isEmpty()) return true
        if (given == null || given.length != expected.length) return false
        var diff = 0
        for (i in given.indices) diff = diff or (given[i].code xor expected[i].code)
        return diff == 0
    }
}
