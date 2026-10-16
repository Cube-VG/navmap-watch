package com.cubevg.navbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.Mvt
import com.cubevg.navmap.core.RoadArea
import com.cubevg.navmap.core.RoadPlanner
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * OpenStreetMap road lines for NavMap to draw itself: a 2400 m square around the watch ([RoadArea]), built from
 * OpenFreeMap tiles and sent after every other message, one chunk at a time. [RoadPlanner] decides when the
 * next square is due. Main thread; tiles are fetched and decoded on a worker thread.
 *
 * The log has sizes and timings only, no positions: it's shown on the setup screen, and screenshots get shared.
 */
object Roads {
    private const val RETRY_MS = 30_000L

    private class Square(val id: Int, val centre: LatLng, val messages: List<Map<String, Any>>) {
        val points = messages.sumOf { (it["p"] as List<*>).size }

        /** Serialized size, estimated as tools/roads.py does. */
        val bytes = messages.sumOf { 5 * (it["p"] as List<*>).size + 60 }
        var next = 0
        var startedAt = 0L
        var sendingMs = 0L
        val sent get() = next >= messages.size
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val planner = RoadPlanner()
    private lateinit var tiles: RoadTiles
    private var square: Square? = null
    private var building = false
    private var retryAt = 0L
    private var lastError: String? = null

    /** Called on the main thread when a new square is ready to send. */
    var onReady: () -> Unit = {}

    /** One line for the setup screen; empty until the first square. */
    var status = ""
        private set

    fun init(context: Context) {
        tiles = RoadTiles(File(context.cacheDir, "roads"), "NavBridge/${BuildConfig.VERSION_NAME} (+https://github.com/Cube-VG/navmap-watch)")
    }

    /** A position from the watch. */
    fun onPosition(p: LatLng) {
        planner.onPosition(p, SystemClock.elapsedRealtime())
        plan()
    }

    /** The phone's own position: starts the first square before the watch has a GPS fix. */
    fun seed(p: LatLng) {
        if (square == null && !building) build(p)
    }

    /**
     * NavMap (re)started and has no roads, so the newest square goes again from its first chunk, unless the next
     * position shows it's out of date. The speed from before is stale.
     */
    fun restart() {
        planner.reset()
        val s = square ?: return
        s.next = 0
        s.startedAt = 0
        s.sendingMs = 0
    }

    /** NavMap closed; its speed is stale by the time it opens again. */
    fun stop() {
        planner.reset()
    }

    /** The next chunk to send, or null. */
    fun next(): Map<String, Any>? = square?.let { it.messages.getOrNull(it.next) }

    /** [message], from [next], is on its way. */
    fun onSending(message: Map<String, Any>) {
        val s = square ?: return
        if (s.startedAt == 0L && s.messages.firstOrNull() === message) s.startedAt = SystemClock.elapsedRealtime()
    }

    /** [message] reached the watch, [ms] after it was handed to Garmin Connect. */
    fun onDelivered(message: Map<String, Any>, ms: Long) {
        val s = square ?: return
        if (s.messages.getOrNull(s.next) !== message) return
        s.next++
        s.sendingMs += ms
        if (!s.sent) {
            status = "${s.next} of ${s.messages.size} chunks on the watch"
            return
        }
        val all = SystemClock.elapsedRealtime() - s.startedAt
        status = "on the watch (${s.messages.size} chunks, ${s.points} points, ${seconds(s.sendingMs)} to send)"
        DebugLog.log(
            "Road square ${s.id} on the watch: ${s.messages.size} chunks, ~${kb(s.bytes)}, " +
                "${seconds(s.sendingMs)} sending (${seconds(all)} with other messages)",
        )
        plan()
    }

    /** The watch can't take [message] (too large or malformed), so its square is given up. */
    fun onRejected(message: Map<String, Any>) {
        val s = square ?: return
        if (s.messages.getOrNull(s.next) !== message) return
        s.next = s.messages.size
        status = "the watch refused square ${s.id}"
        DebugLog.log("Road square ${s.id}: the watch refused chunk ${message["c"]}")
    }

    private fun plan() {
        if (building || SystemClock.elapsedRealtime() < retryAt) return
        val s = square
        val centre = planner.nextCentre(s?.centre, partlySent = s != null && s.next > 0 && !s.sent) ?: return
        build(centre)
    }

    private fun build(centre: LatLng) {
        building = true
        if (square == null) status = "building the first square"
        val began = SystemClock.elapsedRealtime()
        worker.execute {
            val result = runCatching { make(centre) }
            val downloaded = tiles.downloaded
            val downloadedBytes = tiles.downloadedBytes
            main.post {
                building = false
                val took = seconds(SystemClock.elapsedRealtime() - began)
                result.onSuccess { (s, tileCount) ->
                    square = s
                    lastError = null
                    val fetched = if (downloaded > 0) ", $downloaded downloaded (${kb(downloadedBytes.toInt())})" else ""
                    if (s.messages.isEmpty()) {
                        status = "no roads around the watch"
                        DebugLog.log("Road square ${s.id}: no roads in it ($tileCount tiles$fetched, $took)")
                    } else {
                        status = "sending ${s.messages.size} chunks"
                        DebugLog.log(
                            "Road square ${s.id}: ${s.messages.size} chunks, ${s.points} points, ~${kb(s.bytes)}, " +
                                "from $tileCount tiles$fetched in $took",
                        )
                        onReady()
                    }
                }.onFailure { e ->
                    retryAt = SystemClock.elapsedRealtime() + RETRY_MS
                    val error = e.message ?: e.javaClass.simpleName
                    status = "failed ($error), trying again"
                    // Offline, this would repeat every half minute.
                    if (error != lastError) DebugLog.log("Road square failed: $error")
                    lastError = error
                }
            }
        }
    }

    /** Worker thread: the square around [centre], and how many tiles it took. */
    private fun make(centre: LatLng): Pair<Square, Int> {
        tiles.resetCounts()
        val area = RoadArea.square(centre)
        val tileList = area.tiles()
        val lines = ArrayList<RoadArea.TileLine>()
        for ((tx, ty) in tileList) {
            val bytes = tiles.tile(tx, ty)
            val layer = try {
                Mvt.lines(bytes, RoadArea.LAYER)
            } catch (e: Exception) {
                // Most likely cut off while downloading: fetch it again next time.
                tiles.forget(tx, ty)
                throw IOException("broken tile $tx/$ty", e)
            }
            if (layer != null) lines += RoadArea.tileLines(tx, ty, layer)
        }
        tiles.trim()
        return Square(area.id, centre, RoadArea.messages(area, lines)) to tileList.size
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

    private fun kb(bytes: Int) = String.format(Locale.US, "%.1f KB", bytes / 1000.0)
}
