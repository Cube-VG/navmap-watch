package com.cubevg.navbridge

import android.os.Handler
import android.os.Looper
import com.cubevg.navmap.core.LoopbackServer
import com.cubevg.navmap.core.NavEndpoint
import com.cubevg.navmap.core.WatchProtocol
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves GET /nav on 127.0.0.1:18165, which NavMap polls with makeWebRequest. Garmin Connect makes those requests on
 * the phone, so turns reach the watch even when Connect IQ messaging doesn't (Garmin bug CIQQA-4631). Loopback
 * only, and only with the key built into both apps: the replies show where the rider is going, and any app on the
 * phone can connect. [NavSession] builds each reply on the main thread from what it knows right then.
 */
object NavServer {
    private const val ANSWER_TIMEOUT_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private val wrongKeys = AtomicInteger()
    private var started = false

    @Volatile
    private var listening = "Not answering NavMap yet"

    private val server = LoopbackServer(WatchProtocol.HTTP_PORT, ::answer) { status ->
        listening = status.replace("Listening", "Answering NavMap")
        DebugLog.log(listening)
    }

    /** One line for the setup screen. */
    val status: String
        get() = listening + wrongKeys.get().let { if (it > 0) " ($it refused: wrong key)" else "" }

    /** Main thread. Listens for as long as the process runs. */
    fun start() {
        if (started) return
        started = true
        server.start()
    }

    /** Worker thread. */
    private fun answer(method: String, target: String): Pair<Int, Any> {
        val (code, body) = NavEndpoint.answer(method, target, BuildConfig.NAVBRIDGE_TOKEN, ::onMain)
        if (code == 403 && wrongKeys.getAndIncrement() == 0) {
            DebugLog.log("Refused a request for turns with the wrong key. Are the watch and phone apps built with the same NAVBRIDGE_TOKEN?")
        }
        return code to body
    }

    private fun onMain(poll: WatchProtocol.Poll): Map<String, Any>? {
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
