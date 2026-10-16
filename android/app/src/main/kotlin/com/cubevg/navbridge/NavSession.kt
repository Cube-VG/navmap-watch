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
import kotlin.random.Random

/**
 * One navigation, relayed to the watch. When Google Maps starts navigating, NavMap is opened with the watch's
 * "Open NavMap?" prompt. NavMap then polls for the newest turn over HTTP ([NavServer], answered by [onPoll]); if it
 * also says hello over Connect IQ, each turn is pushed to it as well, one message at a time. A NavMap that asks for
 * them in its hello also gets the road lines around it ([Roads]).
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
    private const val POLL_GONE_MS = 20_000L
    private const val POLL_FRESH_MS = 8_000L

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

    /** NavMap answered over Connect IQ during this navigation, so turns are pushed to it too. */
    var watchReady = false
        private set

    /** NavMap is polling /nav: it's open, whatever Connect IQ messaging does. */
    var polling = false
        private set

    /** Polls answered during this navigation. */
    var polls = 0
        private set

    /** NavMap is open and gets this navigation, by either path. */
    val watchShowing get() = watchReady || polling
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
    private var roadsWanted = false
    private var shown = false
    private var polledAt = 0L
    private var endedAt = 0L

    // Route line, from a place shared to NavBridge or from the simulator.
    var destination: Destination? = null
        private set
    private var destinationAt = 0L
    private var route: List<Int>? = null
    private var routeVersion = 0
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
        endedAt = SystemClock.elapsedRealtime()
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
        polls = 0
        shown = false
        failures = 0
        openTries = 0
        deliveredArrowHash = -1
        idleDirty = false
        if (destination != null && SystemClock.elapsedRealtime() - destinationAt > DESTINATION_FRESH_MS) clearDestination()
        DebugLog.log("Navigation started" + (destination?.let { " to ${it.name ?: "shared place"}" } ?: ""))
        main.removeCallbacks(helloTimeout)
        if (polling && SystemClock.elapsedRealtime() - polledAt < POLL_FRESH_MS) {
            // NavMap is open already and fetches the turns itself.
            onShowing()
        } else {
            openOnWatch()
            main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
        }
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
            if (!active || watchShowing) return@openApp
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

    private val openAgain = Runnable { if (active && !watchShowing) openOnWatch() }

    private val helloTimeout = Runnable {
        if (active && !watchShowing) {
            setStatus("The watch hasn't opened NavMap")
            Alerts.openOnWatch(context)
        }
    }

    /** From the reminder notification or the setup screen. */
    fun retryOpen() {
        Alerts.clear(context)
        if (active && !watchShowing) {
            openTries = 0
            openOnWatch()
        } else if (!active) {
            CiqBridge.connectedWatch()?.let { device -> CiqBridge.openApp(device) { DebugLog.log("Open NavMap: $it") } }
        }
    }

    fun onWatchConnected(device: IQDevice) {
        if (active && !watchShowing) {
            openTries = 0
            openOnWatch()
        } else if (device == watch) {
            pump()
        }
    }

    override fun onWatchMessage(device: IQDevice, message: WatchProtocol.FromWatch) {
        when (message) {
            is WatchProtocol.FromWatch.Hello -> {
                DebugLog.log("Hello from NavMap v${message.version} on ${device.friendlyName}" + if (message.roads) ", wants roads" else "")
                roadsWanted = message.roads
                // NavMap has just started, so it has no roads yet.
                if (roadsWanted) Roads.restart()
                onAppOpen(device)
                val position = message.position
                if (position != null) {
                    onWatchPosition(position)
                } else if (roadsWanted) {
                    Locator.lastKnown(context)?.let(Roads::seed)
                }
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
                    if (active) {
                        shown = false
                        setStatus("NavMap was closed on the watch")
                    }
                }
            }
            is WatchProtocol.FromWatch.Unknown -> DebugLog.log("Unknown message from watch: ${message.raw}")
        }
    }

    private fun onWatchReady(device: IQDevice) {
        watch = device
        watchReady = true
        idleDirty = false
        onAppOpen(device)
        // NavMap may have just started: send everything again.
        deliveredArrowHash = -1
        navDirty = latest != null
        routeDirty = route != null
        onShowing()
        pump()
    }

    /** NavMap is open for this navigation, by either path: stop asking and drop the reminder. */
    private fun onShowing() {
        shown = true
        main.removeCallbacks(helloTimeout)
        main.removeCallbacks(openAgain)
        Alerts.clear(context)
        setStatus("Showing turns on ${watch?.friendlyName ?: "the watch"}")
        Simulator.onWatchReady()
        requestRoute()
    }

    // ---- NavMap polling /nav over HTTP ----

    /**
     * NavMap asked for the current turn ([NavServer]), so it's open: this counts as a hello, and its position
     * replaces "where". Returns the reply, from what's known right now.
     */
    fun onPoll(poll: WatchProtocol.Poll): Map<String, Any> {
        val now = SystemClock.elapsedRealtime()
        polledAt = now
        polls++
        main.removeCallbacks(pollsStopped)
        main.postDelayed(pollsStopped, POLL_GONE_MS)
        if (!polling) {
            polling = true
            DebugLog.log("NavMap is asking for turns")
        }
        if (active && latest != null && !shown) onShowing()
        poll.position?.let(::onWatchPosition)
        val info = latest
        return if (info != null) {
            WatchProtocol.navReply(info, arrow, arrowHash, route, routeVersion, poll)
        } else {
            WatchProtocol.notNavigatingReply(if (endedAt > 0) now - endedAt else null, endArrived)
        }
    }

    private val pollsStopped = Runnable {
        polling = false
        DebugLog.log("NavMap stopped asking for turns")
        if (active && !watchReady) {
            shown = false
            setStatus("NavMap was closed on the watch")
        }
    }

    // ---- NavMap open, navigating or not ----

    /** NavMap said hello or answered "where": it's open, so it's asked where it is every 15 s unless it polls. */
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
        roadsWanted = false
        main.removeCallbacks(pollWhere)
        Roads.stop()
    }

    // Where the watch is, for the road squares, and whether NavMap is still there to answer.
    private val pollWhere = object : Runnable {
        override fun run() {
            if (!appOpen) return
            if (polling) {
                // Its polls bring the position and show that it's open.
                wheresUnanswered = 0
            } else if (wheresUnanswered >= WHERE_MISSES && !active) {
                // Closed without a bye. While Maps is navigating, turns keep going regardless.
                DebugLog.log("NavMap stopped answering")
                onAppClosed()
                return
            } else {
                whereDirty = true
                pump()
            }
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
        val roads = if (appOpen && roadsWanted) Roads.next() else null
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
        setRouteLine(null)
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
        setRouteLine(points)
        pump()
    }

    /** A new line, or none: it gets a new version, so a polling NavMap fetches it. */
    private fun setRouteLine(points: List<Int>?) {
        if (points == null && route == null) return
        route = points
        routeDirty = points != null
        // Random, so a restarted NavBridge never reuses the version of a line the watch still has.
        val old = routeVersion
        do routeVersion = Random.nextInt(1, 1 shl 30) while (routeVersion == old)
    }

    private fun clearDestination() {
        destination = null
        setRouteLine(null)
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
        if (roadsWanted) Roads.onPosition(position)
    }

    // Repeats until the watch has a GPS fix to report.
    private val askWhere = object : Runnable {
        override fun run() {
            if (destination != null && watchPosition == null && watchReady && !polling) {
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
            // Without the phone's location, NavMap reports where the watch is: with its next poll, or asked.
            if (watchReady && !polling) main.post(askWhere)
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
                setRouteLine(Polyline.forWatch(r.points))
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
