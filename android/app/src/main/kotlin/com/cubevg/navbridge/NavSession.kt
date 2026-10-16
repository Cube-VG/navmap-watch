package com.cubevg.navbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cubevg.navmap.core.ArrowEncoder
import com.cubevg.navmap.core.Destination
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.NavInfo
import com.cubevg.navmap.core.NavKind
import com.cubevg.navmap.core.Polyline
import com.cubevg.navmap.core.WatchProtocol
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.ConnectIQ.IQOpenApplicationStatus
import com.garmin.android.connectiq.IQDevice

/**
 * One navigation, relayed to the watch. When Google Maps starts navigating, NavMap is opened with the watch's
 * "Open NavMap?" prompt; once it says hello, the watch always gets the newest turn, one message at a time.
 * Whenever NavMap is open, navigating or not, it also gets the road lines around it ([Roads]).
 * Main thread only.
 */
object NavSession : CiqBridge.Listener {
    private const val HELLO_TIMEOUT_MS = 30_000L
    private const val SEND_TIMEOUT_MS = 12_000L
    private const val END_GRACE_MS = 6_000L
    private const val END_GIVE_UP_MS = 30_000L
    private const val OPEN_TRIES = 3
    private const val DESTINATION_FRESH_MS = 10 * 60_000L
    private const val REROUTE_GAP_MS = 20_000L
    private const val WHERE_RETRY_MS = 8_000L
    private const val WHERE_EVERY_MS = 15_000L
    private const val WHERE_MISSES = 4

    /** The watch can never take a message that failed like this, so it isn't retried. */
    private val REFUSED = setOf(
        IQMessageStatus.FAILURE_MESSAGE_TOO_LARGE,
        IQMessageStatus.FAILURE_INVALID_FORMAT,
        IQMessageStatus.FAILURE_UNSUPPORTED_TYPE,
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context

    /** Google Maps (or the simulator) is navigating. */
    var active = false
        private set

    /** NavMap answered on the watch during this navigation. */
    var watchReady = false
        private set
    var watch: IQDevice? = null
        private set

    /** NavMap is open on the watch, navigating or not: it said hello or answers "where". */
    var appOpen = false
        private set
    var status = "Waiting for Google Maps navigation"
        private set
    var sent = 0
        private set
    var latest: NavInfo? = null
        private set

    private var arrow: IntArray? = null
    private var arrowHash = 0
    private var deliveredArrowHash = -1
    private var navDirty = false
    private var seq = 0
    private var openTries = 0
    private var failures = 0
    private var inFlight = false
    private var sendId = 0
    private var endPending = false
    private var endArrived = false
    private var whereDirty = false
    private var idleDirty = false
    private var wheresUnanswered = 0

    // Route line, from a place shared to NavBridge or from the simulator.
    var destination: Destination? = null
        private set
    private var destinationAt = 0L
    private var route: List<Int>? = null
    private var routeDirty = false
    private var routing = false
    private var rerouteWanted = false
    private var lastRouteAt = 0L
    private var phoneOrigin: LatLng? = null
    var watchPosition: LatLng? = null
        private set
    var routeSummary = ""
        private set

    fun init(appContext: Context) {
        context = appContext.applicationContext
        CiqBridge.listener = this
        Roads.init(context)
        Roads.onReady = ::pump
    }

    // ---- Google Maps ----

    /** A navigation notification from Google Maps (or the simulator) was posted or updated. */
    fun onNav(info: NavInfo, arrowBits: IntArray?) {
        main.removeCallbacks(endNow)
        main.removeCallbacks(giveUpEnd)
        endPending = false
        latest = info
        arrow = arrowBits
        arrowHash = arrowBits?.let(ArrowEncoder::hash) ?: 0
        navDirty = true
        if (!active) begin()
        if (info.kind == NavKind.REROUTING) onReroute()
        pump()
    }

    /** The navigation notification went away. Wait a moment in case Maps is only replacing it. */
    fun onNavEnded() {
        if (!active) return
        main.removeCallbacks(endNow)
        main.postDelayed(endNow, END_GRACE_MS)
    }

    private val endNow = Runnable {
        DebugLog.log("Navigation ended")
        endArrived = latest?.kind == NavKind.ARRIVED
        latest = null
        navDirty = false
        if (watchReady) {
            endPending = true
            main.postDelayed(giveUpEnd, END_GIVE_UP_MS)
            pump()
        } else {
            finish()
        }
    }

    private val giveUpEnd = Runnable { finish() }

    private fun begin() {
        active = true
        watchReady = false
        seq = 0
        sent = 0
        failures = 0
        openTries = 0
        deliveredArrowHash = -1
        idleDirty = false
        if (destination != null && SystemClock.elapsedRealtime() - destinationAt > DESTINATION_FRESH_MS) clearDestination()
        DebugLog.log("Navigation started" + (destination?.let { " to ${it.name ?: "shared place"}" } ?: ""))
        openOnWatch()
        main.removeCallbacks(helloTimeout)
        main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
    }

    private fun finish() {
        main.removeCallbacks(helloTimeout)
        main.removeCallbacks(giveUpEnd)
        main.removeCallbacks(openAgain)
        active = false
        watchReady = false
        endPending = false
        latest = null
        arrow = null
        clearDestination()
        Alerts.clear(context)
        setStatus("Waiting for Google Maps navigation")
    }

    // ---- Watch ----

    private fun openOnWatch() {
        val device = CiqBridge.connectedWatch()
        if (device == null) {
            setStatus("No watch connected to Garmin Connect")
            return // retried from onWatchConnected
        }
        watch = device
        openTries++
        setStatus("Asking ${device.friendlyName} to open NavMap")
        CiqBridge.openApp(device) { result ->
            if (!active || watchReady) return@openApp
            DebugLog.log("Open NavMap: $result")
            when (result) {
                IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING -> onWatchReady(device)
                IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> setStatus("Press the button on the watch to open NavMap")
                IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> {
                    setStatus("NavMap isn't installed on ${device.friendlyName}")
                    Alerts.notInstalled(context)
                }
                else -> if (openTries < OPEN_TRIES) main.postDelayed(openAgain, 4_000)
            }
        }
    }

    private val openAgain = Runnable { if (active && !watchReady) openOnWatch() }

    private val helloTimeout = Runnable {
        if (active && !watchReady) {
            setStatus("The watch hasn't opened NavMap")
            Alerts.openOnWatch(context)
        }
    }

    /** From the reminder notification or the setup screen. */
    fun retryOpen() {
        Alerts.clear(context)
        if (active && !watchReady) {
            openTries = 0
            openOnWatch()
        } else if (!active) {
            CiqBridge.connectedWatch()?.let { device -> CiqBridge.openApp(device) { DebugLog.log("Open NavMap: $it") } }
        }
    }

    fun onWatchConnected(device: IQDevice) {
        if (active && !watchReady) {
            openTries = 0
            openOnWatch()
        } else if (device == watch) {
            pump()
        }
    }

    override fun onWatchMessage(device: IQDevice, message: WatchProtocol.FromWatch) {
        when (message) {
            is WatchProtocol.FromWatch.Hello -> {
                DebugLog.log("Hello from NavMap v${message.version} on ${device.friendlyName}")
                // NavMap has just started, so it has no roads yet.
                Roads.restart()
                onAppOpen(device)
                val position = message.position
                if (position != null) onWatchPosition(position) else Locator.lastKnown(context)?.let(Roads::seed)
                if (active) {
                    onWatchReady(device)
                } else {
                    // NavMap was opened by hand: tell it the phone is here so it stops asking.
                    idleDirty = true
                    pump()
                }
            }
            is WatchProtocol.FromWatch.Position -> {
                onAppOpen(device)
                message.position?.let(::onWatchPosition)
            }
            WatchProtocol.FromWatch.Bye -> {
                DebugLog.log("NavMap closed on ${device.friendlyName}")
                if (device == watch) {
                    watchReady = false
                    onAppClosed()
                    if (active) setStatus("NavMap was closed on the watch")
                }
            }
            is WatchProtocol.FromWatch.Unknown -> DebugLog.log("Unknown message from watch: ${message.raw}")
        }
    }

    private fun onWatchReady(device: IQDevice) {
        main.removeCallbacks(helloTimeout)
        main.removeCallbacks(openAgain)
        Alerts.clear(context)
        watch = device
        watchReady = true
        idleDirty = false
        onAppOpen(device)
        // NavMap may have just started: send everything again.
        deliveredArrowHash = -1
        navDirty = latest != null
        routeDirty = route != null
        setStatus("Showing turns on ${device.friendlyName}")
        Simulator.onWatchReady()
        requestRoute()
        pump()
    }

    // ---- NavMap open, navigating or not ----

    /** NavMap said hello or answered "where": it's open, so it's asked where it is every 15 s and gets roads. */
    private fun onAppOpen(device: IQDevice) {
        wheresUnanswered = 0
        watch = device
        if (appOpen) return
        appOpen = true
        main.removeCallbacks(pollWhere)
        main.postDelayed(pollWhere, WHERE_EVERY_MS)
    }

    private fun onAppClosed() {
        appOpen = false
        idleDirty = false
        main.removeCallbacks(pollWhere)
        Roads.stop()
    }

    // Where the watch is, for the road squares, and whether NavMap is still there to answer.
    private val pollWhere = object : Runnable {
        override fun run() {
            if (!appOpen) return
            if (wheresUnanswered >= WHERE_MISSES && !active) {
                // Closed without a bye. While Maps is navigating, turns keep going regardless.
                DebugLog.log("NavMap stopped answering")
                onAppClosed()
                return
            }
            whereDirty = true
            pump()
            main.postDelayed(this, WHERE_EVERY_MS)
        }
    }

    // ---- Sending, one message at a time ----

    // Most urgent first. A road chunk is about 2.5 KB, so a turn waits for at most one.
    private enum class Kind { END, NAV, IDLE, WHERE, ROUTE, ROADS }

    private fun pump() {
        if (inFlight || (!watchReady && !appOpen)) return
        val device = watch ?: return
        val info = latest
        val kind: Kind
        val message: Map<String, Any>
        val sentArrowHash = arrowHash
        val sentRoute = route
        val roads = if (appOpen) Roads.next() else null
        when {
            watchReady && endPending -> {
                kind = Kind.END
                message = WatchProtocol.end(endArrived)
            }
            watchReady && navDirty && info != null -> {
                kind = Kind.NAV
                message = WatchProtocol.nav(++seq, info, arrow, arrowHash, sendArrow = arrowHash != deliveredArrowHash)
            }
            idleDirty && !active -> {
                kind = Kind.IDLE
                message = WatchProtocol.idle()
            }
            whereDirty -> {
                kind = Kind.WHERE
                message = WatchProtocol.where()
            }
            watchReady && routeDirty && sentRoute != null -> {
                kind = Kind.ROUTE
                message = WatchProtocol.route(sentRoute)
            }
            roads != null -> {
                kind = Kind.ROADS
                message = roads
                Roads.onSending(roads)
            }
            else -> return
        }
        inFlight = true
        val id = ++sendId
        val sentAt = SystemClock.elapsedRealtime()
        main.postDelayed({ if (inFlight && id == sendId) onSent(id, kind, null, info, sentArrowHash, sentRoute, message, sentAt) }, SEND_TIMEOUT_MS)
        CiqBridge.send(device, message) { result -> onSent(id, kind, result, info, sentArrowHash, sentRoute, message, sentAt) }
    }

    private fun onSent(
        id: Int,
        kind: Kind,
        result: IQMessageStatus?,
        info: NavInfo?,
        hash: Int,
        sentRoute: List<Int>?,
        message: Map<String, Any>,
        sentAt: Long,
    ) {
        if (id != sendId || !inFlight) return
        inFlight = false
        if (result == IQMessageStatus.SUCCESS) {
            sent++
            failures = 0
            when (kind) {
                Kind.NAV -> {
                    deliveredArrowHash = hash
                    if (latest === info && arrowHash == hash) navDirty = false
                }
                Kind.ROUTE -> if (route === sentRoute) routeDirty = false
                Kind.IDLE -> idleDirty = false
                Kind.WHERE -> {
                    whereDirty = false
                    wheresUnanswered++
                }
                Kind.ROADS -> Roads.onDelivered(message, SystemClock.elapsedRealtime() - sentAt)
                Kind.END -> if (endPending) {
                    finish()
                    return
                } // else navigation came back while "end" was on its way: carry on
            }
            pump()
        } else {
            failures++
            DebugLog.log("Send $kind failed: ${result ?: "timeout"}")
            if (kind == Kind.ROADS && result in REFUSED) {
                Roads.onRejected(message)
                pump()
                return
            }
            if (result == IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED) {
                // Resumes from onWatchConnected; the slow retry covers a missed connection event.
                setStatus("Watch disconnected")
                main.postDelayed(::pump, 10_000)
                return
            }
            main.postDelayed(::pump, minOf(1_000L * failures, 5_000L))
        }
    }

    // ---- Route line ----

    /** A place was shared to NavBridge; navigation in Google Maps is about to start. */
    fun setDestination(place: Destination, origin: LatLng?) {
        destination = place
        destinationAt = SystemClock.elapsedRealtime()
        phoneOrigin = origin
        route = null
        routeDirty = false
        routeSummary = ""
        rerouteWanted = false
        DebugLog.log("Destination: ${place.name ?: place}")
        requestRoute()
    }

    fun setPhoneOrigin(origin: LatLng) {
        phoneOrigin = origin
        requestRoute()
    }

    /** For the simulator: a route made up on the phone. */
    fun setRoute(points: List<Int>) {
        route = points
        routeDirty = true
        pump()
    }

    private fun clearDestination() {
        destination = null
        route = null
        routeDirty = false
        routeSummary = ""
        phoneOrigin = null
        rerouteWanted = false
        whereDirty = false
        main.removeCallbacks(askWhere)
    }

    private fun onReroute() {
        if (destination == null || routing) return
        if (SystemClock.elapsedRealtime() - lastRouteAt < REROUTE_GAP_MS) return
        rerouteWanted = true
        phoneOrigin = null // only good for the first route
        watchPosition = null // ask for a fresh one
        requestRoute()
    }

    private fun onWatchPosition(position: LatLng) {
        watchPosition = position
        main.removeCallbacks(askWhere)
        requestRoute()
        Roads.onPosition(position)
    }

    // Repeats until the watch has a GPS fix to report.
    private val askWhere = object : Runnable {
        override fun run() {
            if (destination != null && watchPosition == null && watchReady) {
                whereDirty = true
                pump()
                main.postDelayed(this, WHERE_RETRY_MS)
            }
        }
    }

    private fun requestRoute() {
        val place = destination ?: return
        if (routing || (route != null && !rerouteWanted)) return
        val key = Prefs.routesKey(context)
        if (key.isEmpty()) {
            routeSummary = "No Routes API key, so no route line"
            return
        }
        val origin = phoneOrigin ?: watchPosition
        if (origin == null) {
            // Without the phone's location, NavMap reports where the watch is.
            if (watchReady) main.post(askWhere)
            return
        }
        routing = true
        rerouteWanted = false
        val mode = Prefs.travelMode(context)
        DebugLog.log("Getting route ($mode)")
        RoutesClient.computeRoute(key, origin, place, mode) { result ->
            routing = false
            if (destination !== place) return@computeRoute
            lastRouteAt = SystemClock.elapsedRealtime()
            result.onSuccess { r ->
                route = Polyline.forWatch(r.points)
                routeDirty = true
                routeSummary = r.summary()
                DebugLog.log("Route: ${r.summary()}, ${route!!.size / 2} points for the watch")
                pump()
            }.onFailure { e ->
                routeSummary = "Route failed: ${e.message}"
                DebugLog.log(routeSummary)
            }
        }
    }

    private fun setStatus(text: String) {
        if (status != text) DebugLog.log(text)
        status = text
    }
}
