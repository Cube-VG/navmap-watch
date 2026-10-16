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
                message.position?.let(::onWatchPosition)
                if (active) {
                    onWatchReady(device)
                } else {
                    // NavMap was opened by hand: tell it the phone is here so it stops asking.
                    CiqBridge.send(device, WatchProtocol.idle()) {}
                }
            }
            is WatchProtocol.FromWatch.Position -> message.position?.let(::onWatchPosition)
            WatchProtocol.FromWatch.Bye -> {
                DebugLog.log("NavMap closed on ${device.friendlyName}")
                if (device == watch) {
                    watchReady = false
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
        // NavMap may have just started: send everything again.
        deliveredArrowHash = -1
        navDirty = latest != null
        routeDirty = route != null
        setStatus("Showing turns on ${device.friendlyName}")
        Simulator.onWatchReady()
        requestRoute()
        pump()
    }

    // ---- Sending, one message at a time ----

    private enum class Kind { NAV, ROUTE, WHERE, END }

    private fun pump() {
        if (!watchReady || inFlight) return
        val device = watch ?: return
        val info = latest
        val kind: Kind
        val message: Map<String, Any>
        val sentArrowHash = arrowHash
        val sentRoute = route
        when {
            endPending -> {
                kind = Kind.END
                message = WatchProtocol.end(endArrived)
            }
            navDirty && info != null -> {
                kind = Kind.NAV
                message = WatchProtocol.nav(++seq, info, arrow, arrowHash, sendArrow = arrowHash != deliveredArrowHash)
            }
            whereDirty -> {
                kind = Kind.WHERE
                message = WatchProtocol.where()
            }
            routeDirty && sentRoute != null -> {
                kind = Kind.ROUTE
                message = WatchProtocol.route(sentRoute)
            }
            else -> return
        }
        inFlight = true
        val id = ++sendId
        main.postDelayed({ if (inFlight && id == sendId) onSent(id, kind, null, info, sentArrowHash, sentRoute) }, SEND_TIMEOUT_MS)
        CiqBridge.send(device, message) { result -> onSent(id, kind, result, info, sentArrowHash, sentRoute) }
    }

    private fun onSent(id: Int, kind: Kind, result: IQMessageStatus?, info: NavInfo?, hash: Int, sentRoute: List<Int>?) {
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
                Kind.WHERE -> whereDirty = false
                Kind.END -> if (endPending) {
                    finish()
                    return
                } // else navigation came back while "end" was on its way: carry on
            }
            pump()
        } else {
            failures++
            DebugLog.log("Send $kind failed: ${result ?: "timeout"}")
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
