package com.cubevg.navbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cubevg.navmap.core.LoopbackServer
import com.cubevg.navmap.core.NavEndpoint
import com.cubevg.navmap.core.WatchPairing
import com.cubevg.navmap.core.WatchProtocol
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves GET /nav on 127.0.0.1:18165, which NavMap polls with makeWebRequest. Garmin Connect makes those requests on
 * the phone, so turns reach the watch even when Connect IQ messaging doesn't (Garmin bug CIQQA-4631). Loopback
 * only, only once the user has agreed ([Prefs.consentedAt]), and only for the paired watch ([pairing]): the replies
 * show where the rider is going and carry the Google Maps key, and any app on the phone can connect. [NavSession]
 * builds each reply on the main thread from what it knows right then.
 */
object NavServer {
    private const val ANSWER_TIMEOUT_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private val refusals = AtomicInteger()
    private var started = false
    private lateinit var appContext: Context

    /** Which watch gets answers. Set up in [start] from the token NavBridge kept last time. */
    lateinit var pairing: WatchPairing
        private set

    /** When the paired watch last asked (elapsedRealtime), 0 for never. */
    @Volatile
    var lastPollAt = 0L
        private set

    /** Whether the paired watch's last poll showed it has the current Google Maps key. */
    @Volatile
    var watchHasKey = false
        private set

    @Volatile
    private var listening = "Not answering NavMap yet"

    private val server = LoopbackServer(WatchProtocol.HTTP_PORT, ::answer) { status ->
        listening = status.replace("Listening", "Answering NavMap")
        DebugLog.log(listening)
    }

    /** Lines for the setup screen. */
    val status: String
        get() = buildString {
            append(listening)
            refusals.get().let { if (it > 0) append(" ($it refused: not the paired watch)") }
            if (::appContext.isInitialized && !Prefs.consented(appContext)) {
                append("\nNavMap gets no map or turns until you agree (Privacy and terms)")
            } else if (::pairing.isInitialized) {
                append(
                    when {
                        pairing.paired -> "\nPaired with NavMap"
                        pairing.windowOpen -> "\nReady to pair: open NavMap on the watch"
                        else -> "\nNo watch paired: open NavBridge, then NavMap on the watch"
                    },
                )
            }
        }

    /** Main thread. Listens for as long as the process runs. */
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        pairing = WatchPairing(Prefs.watchToken(appContext), { token ->
            Prefs.setWatchToken(appContext, token)
            if (token != null) DebugLog.log("Paired with NavMap")
        })
        server.start()
    }

    /** Worker thread. */
    private fun answer(method: String, target: String): Pair<Int, Any> {
        val consented = Prefs.consented(appContext)
        if (!consented) watchHasKey = false // NavMap drops its key when told to ask for consent
        val (code, body) = NavEndpoint.answer(method, target, consented, pairing::accepts, Prefs.mapsKey(appContext), ::onMain)
        if (code == 403) {
            if (refusals.getAndIncrement() == 0) {
                DebugLog.log("Refused a request for turns from a watch that isn't paired. Open NavBridge, or tap Pair again after changing watches.")
            }
            // Refused, but a NavMap is open: NavBridge mustn't ask the watch to open it, which would close it.
            val session = WatchProtocol.poll(target.substringAfter('?', "")).session
            main.post { NavSession.onRefusedPoll(session) }
        }
        return code to body
    }

    private fun onMain(poll: WatchProtocol.Poll): Map<String, Any>? {
        lastPollAt = SystemClock.elapsedRealtime()
        val tail = poll.keyTail
        watchHasKey = !tail.isNullOrEmpty() && tail == Prefs.mapsKey(appContext).takeLast(6)
        val task = FutureTask { NavSession.onPoll(poll) }
        main.post(task)
        return try {
            task.get(ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(false)
            null
        } catch (e: ExecutionException) {
            DebugLog.log("Answering NavMap failed: ${e.cause}")
            throw e
        }
    }
}
