package com.cubevg.navbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cubevg.navmap.core.ArrowEncoder
import com.cubevg.navmap.core.Destination
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.NavCodec
import com.cubevg.navmap.core.NavInfo
import com.cubevg.navmap.core.NavKind
import com.cubevg.navmap.core.Polyline
import com.cubevg.navmap.core.RawNotification
import com.cubevg.navmap.core.WatchProtocol
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.ConnectIQ.IQOpenApplicationStatus
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
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
    private const val CARD_STALE_MS = 30 * 60_000L
    private const val OPEN_TRIES = 3
    private const val DESTINATION_FRESH_MS = 10 * 60_000L
    private const val REROUTE_GAP_MS = 20_000L
    private const val WHERE_RETRY_MS = 8_000L
    private const val WHERE_EVERY_MS = 15_000L
    private const val WHERE_MISSES = 4
    private const val POLL_GONE_MS = 20_000L
    private const val POLL_FRESH_MS = 8_000L
    private const val STARTUP_QUIET_MS = 10_000L
    private const val OFF_ROUTE_M = 60.0
    private const val OFF_ROUTE_MS = 8_000L
    private const val OFF_ROUTE_GAP_MS = 30_000L
    private const val ROUTE_RETRY_MS = 15_000L

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
    private var endWhy = "" // why the navigation is ending, for the log
    private var cardGone = false // Maps' card went away but Maps didn't stop, see onNavEnded
    private var unreadableLogged = false
    private var whereDirty = false
    private var idleDirty = false
    private var wheresUnanswered = 0
    private var roadsWanted = false
    private var shown = false
    private var polledAt = 0L
    private var endedAt = 0L
    private var closedSession: Int? = null // the NavMap run that last said bye
    private var promptedAt = 0L // when the watch last showed "Open NavMap?"
    private var heardAt = 0L // when NavMap was last heard from: a poll, refused or not, or a Connect IQ message; 0 after its bye
    private var closeTipShown = false
    private var startedAt = 0L // when this process started, see justStarted()
    private var crashLogged = 0L // the unclosed NavMap run last logged, see onPoll

    // Route line, from a place shared to NavBridge or from the simulator.
    var destination: Destination? = null
        private set
    private var destinationAt = 0L
    private var route: List<Int>? = null
    private var routeText: String? = null // the same line for NavMap 0.6+, see NavCodec
    private var routeVersion = 0
    private var routeDirty = false
    private var routing = false
    private var rerouteWanted = false
    private var lastRouteAt = 0L
    private var routePoints: List<LatLng>? = null // the whole line, to tell when the watch has left it
    private var offRouteSince = 0L
    private var offRouteTries = 0 // new lines in a row that the watch hasn't been on yet
    private var routeFails = 0
    private var routeRetryAt = 0L
    private var phoneOrigin: LatLng? = null
    var watchPosition: LatLng? = null
        private set
    var routeSummary = ""
        private set

    fun init(appContext: Context) {
        context = appContext.applicationContext
        startedAt = SystemClock.elapsedRealtime()
        CiqBridge.listener = this
        Roads.init(context)
        Roads.onReady = ::pump
    }

    // ---- Google Maps ----

    /** A navigation notification from Google Maps (or the simulator) was posted or updated. */
    fun onNav(info: NavInfo, arrowBits: IntArray?) {
        main.removeCallbacks(endNow)
        main.removeCallbacks(giveUpEnd)
        main.removeCallbacks(cardStale)
        endPending = false
        if (active && cardGone) DebugLog.log("Google Maps' notification is back")
        cardGone = false
        unreadableLogged = false
        latest = info
        arrow = arrowBits
        arrowHash = arrowBits?.let(ArrowEncoder::hash) ?: 0
        navDirty = true
        if (!active) begin()
        if (info.kind == NavKind.REROUTING) onReroute()
        pump()
    }

    /**
     * The navigation notification went away, [why]. If Maps stopped navigating, wait a moment in case it's only
     * replacing the card, then end. If not (the card timed out while the phone sat still, say, or was swiped away),
     * the watch keeps the last turn until Maps updates the card, which it does as soon as you move.
     */
    fun onNavEnded(why: String, mapsStopped: Boolean) {
        if (!active) return
        endWhy = why
        main.removeCallbacks(endNow)
        if (mapsStopped) {
            main.postDelayed(endNow, END_GRACE_MS)
        } else {
            cardGone = true
            DebugLog.log("Google Maps' notification went away: $why. Maps is still navigating, so the watch keeps the last turn")
            main.removeCallbacks(cardStale)
            main.postDelayed(cardStale, CARD_STALE_MS)
        }
    }

    /** Maps updated its card into one that isn't turn-by-turn, though it's still its ongoing navigation card. */
    fun onNavUnreadable(raw: RawNotification) {
        if (!active || unreadableLogged) return
        unreadableLogged = true
        DebugLog.log("Google Maps changed its notification to one NavBridge can't read, so the watch keeps the last turn: ${describe(raw)}")
    }

    // No card from Maps for [CARD_STALE_MS] after it went away without Maps stopping: Exit was chosen in Maps meanwhile.
    private val cardStale: Runnable = Runnable {
        endWhy = "no word from Google Maps for ${CARD_STALE_MS / 60_000} min after its notification went away"
        endNow.run()
    }

    private val endNow: Runnable = Runnable {
        main.removeCallbacks(cardStale)
        // Maps may still show a turn-by-turn card under another key: then it's still navigating.
        if (NavListenerService.rescan()) {
            DebugLog.log("Google Maps still shows turns, so the navigation carries on")
            return@Runnable
        }
        DebugLog.log("Navigation ended: $endWhy" + (latest?.let { ". Maps last said: ${describe(it)}" } ?: ""))
        cardGone = false
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
        closeTipShown = false
        deliveredArrowHash = -1
        idleDirty = false
        if (destination != null && SystemClock.elapsedRealtime() - destinationAt > DESTINATION_FRESH_MS) clearDestination()
        DebugLog.log("Navigation started" + (destination?.let { " to ${it.name ?: "shared place"}" } ?: ""))
        main.removeCallbacks(helloTimeout)
        main.removeCallbacks(openIfSilent)
        // The watch shows "Open NavMap?" even over a NavMap that's open, and closes it, one opened from the app list
        // too, which the watch would have left open. So no prompt while NavMap is heard from.
        when {
            polling -> onShowing() // it fetches the turns itself
            heard() && appOpen -> watch?.let(::onWatchReady) ?: onShowing()
            heard() -> setStatus("NavMap on the watch isn't paired: tap Pair again, then open NavMap")
            justStarted() -> openLater()
            else -> {
                openOnWatch()
                main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
            }
        }
        if (!polling && heard()) main.postDelayed(openIfSilent, POLL_GONE_MS)
    }

    /** NavMap was heard from in the last [POLL_GONE_MS], by a poll, refused or not, or a Connect IQ message, and hasn't said bye. */
    private fun heard() = heardAt > 0 && SystemClock.elapsedRealtime() - heardAt < POLL_GONE_MS

    /**
     * NavBridge started less than [STARTUP_QUIET_MS] ago, so it knows nothing of NavMap yet. Android may have stopped
     * NavBridge in mid-navigation and started it again, with NavMap still open on the watch: its polls failed
     * meanwhile, and the next one comes within seconds.
     */
    private fun justStarted() = SystemClock.elapsedRealtime() - startedAt < STARTUP_QUIET_MS

    // Asks the watch to open NavMap once NavBridge has been up [STARTUP_QUIET_MS], if NavMap hasn't polled by then. Asked at
    // once, "Open NavMap?" would close a NavMap that is open (a field test: closed 6 s after NavBridge restarted).
    private fun openLater() {
        setStatus("Checking whether NavMap is open on the watch")
        main.removeCallbacks(openIfSilent)
        main.postDelayed(openIfSilent, startedAt + STARTUP_QUIET_MS - SystemClock.elapsedRealtime())
    }

    // NavMap was heard from when the navigation started or the watch came back, so the watch wasn't asked to open it.
    // If it has gone quiet since, it closed after all, and the watch is asked now.
    private val openIfSilent = Runnable {
        if (active && !watchShowing && !heard()) {
            openTries = 0
            openOnWatch()
            main.removeCallbacks(helloTimeout)
            main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
        }
    }

    /** NavServer refused a poll from a NavMap that isn't paired. It's open all the same. */
    fun onRefusedPoll(session: Int?) {
        if (session == null || session != closedSession) heardAt = SystemClock.elapsedRealtime()
    }

    private fun finish() {
        main.removeCallbacks(helloTimeout)
        main.removeCallbacks(giveUpEnd)
        main.removeCallbacks(openAgain)
        main.removeCallbacks(cardStale)
        cardGone = false
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
        // A NavMap that has never paired can pair now: it was NavBridge that asked for it.
        NavServer.pairing.openWindow()
        setStatus("Asking ${device.friendlyName} to open NavMap")
        CiqBridge.openApp(device) { result ->
            if (!active || watchShowing) return@openApp
            DebugLog.log("Open NavMap: $result")
            when (result) {
                IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING -> onWatchReady(device)
                IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> {
                    promptedAt = SystemClock.elapsedRealtime()
                    setStatus("Press the button on the watch to open NavMap")
                }
                IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> {
                    setStatus("NavMap isn't installed on ${device.friendlyName}")
                    Alerts.notInstalled(context)
                }
                // PROMPT_NOT_SHOWN_ON_DEVICE: no prompt, e.g. because the watch still shows an earlier one.
                else -> if (openTries < OPEN_TRIES) main.postDelayed(openAgain, 4_000) else remind("The watch didn't show the prompt")
            }
        }
    }

    private val openAgain = Runnable { if (active && !watchShowing && !heard()) openOnWatch() }

    private val helloTimeout = Runnable { if (active && !watchShowing) remind("The watch hasn't opened NavMap") }

    /** NavMap isn't open: say so, here and in a notification with Try again. */
    private fun remind(text: String) {
        main.removeCallbacks(helloTimeout)
        setStatus(text)
        Alerts.openOnWatch(context)
    }

    /** From the reminder notification or the setup screen. */
    fun retryOpen() {
        Alerts.clear(context)
        if (active && !watchShowing) {
            openTries = 0
            openOnWatch()
            // Remind again if this prompt goes unanswered too.
            main.removeCallbacks(helloTimeout)
            main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
        } else if (!active) {
            CiqBridge.connectedWatch()?.let { device -> CiqBridge.openApp(device) { DebugLog.log("Open NavMap: $it") } }
        }
    }

    fun onWatchConnected(device: IQDevice) {
        if (active && !watchShowing) {
            if (heardAt == 0L && justStarted()) {
                // Garmin Connect reports the watch as soon as NavBridge starts, just after begin() put off asking.
                openLater()
            } else if (heardAt == 0L) {
                openTries = 0
                openOnWatch()
            } else {
                // NavMap may still be open, out of touch while the watch was away: a prompt would close it.
                main.removeCallbacks(openIfSilent)
                main.postDelayed(openIfSilent, POLL_GONE_MS)
            }
        } else if (device == watch) {
            pump()
        }
    }

    override fun onWatchMessage(device: IQDevice, message: WatchProtocol.FromWatch) {
        when (message) {
            is WatchProtocol.FromWatch.Hello -> {
                DebugLog.log(
                    "Hello from NavMap v${message.version} on ${device.friendlyName}" +
                        (if (message.glance) ", opened from the glance list" else "") + if (message.roads) ", wants roads" else "",
                )
                if (NavServer.pairing.offer(message.token)) {
                    DebugLog.log("This NavMap isn't the one paired before. It pairs when it next asks, if NavBridge was open in the last 10 min.")
                }
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
            is WatchProtocol.FromWatch.Bye -> {
                DebugLog.log("NavMap closed on ${device.friendlyName}" + closeReason(message))
                if (device == watch) {
                    watchReady = false
                    onAppClosed()
                    // Its last polls can still be on their way, held up on Bluetooth: they don't mean it's open.
                    closedSession = message.session
                    heardAt = 0L
                    polling = false
                    main.removeCallbacks(pollsStopped)
                    if (active) {
                        shown = false
                        // NavMap 0.12+ says why it closed; with no reason, the watch closed it in mid-navigation.
                        val ran = message.seconds
                        if (ran != null && message.why == null && latest != null && !endPending) {
                            if (!closeTipShown && ran >= 60) {
                                closeTipShown = true
                                DebugLog.log(
                                    "The watch closes NavMap after about 2 minutes without a button press when the phone's " +
                                        "prompt opened it. Opened from the watch's app list, it should stay open.",
                                )
                            }
                            reopen()
                        } else {
                            setStatus("NavMap was closed on the watch")
                        }
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

    /** NavMap went away in mid-navigation, not closed by the user: the watch is asked to open it again. */
    private fun reopen() {
        shown = false
        openTries = 0
        openOnWatch()
        main.removeCallbacks(helloTimeout)
        main.postDelayed(helloTimeout, HELLO_TIMEOUT_MS)
    }

    /** For the log: how long NavMap 0.12+ ran, why it closed, and how soon after the prompt it had opened. */
    private fun closeReason(bye: WatchProtocol.FromWatch.Bye): String {
        val secs = bye.seconds ?: return ""
        val why = when (bye.why) {
            "u" -> "Exit chosen"
            "e" -> "navigation ended"
            else -> "by the watch" + if (bye.suspended) " (suspended)" else ""
        }
        val afterPrompt = SystemClock.elapsedRealtime() - secs * 1000L - promptedAt
        // Not for a NavMap that was open before the prompt.
        val how = if (promptedAt > 0 && afterPrompt in 0L..120_000L) ", opened ${afterPrompt / 1000} s after the prompt" else ""
        return " after ${secs / 60} min ${secs % 60} s: $why$how"
    }

    // ---- NavMap polling /nav over HTTP ----

    /**
     * NavMap asked for the current turn ([NavServer]), so it's open: this counts as a hello, and its position
     * replaces "where". Returns the reply, from what's known right now.
     */
    fun onPoll(poll: WatchProtocol.Poll): Map<String, Any> {
        val now = SystemClock.elapsedRealtime()
        // Sent before NavMap said bye, and held up on the way: answered, but NavMap isn't open.
        val late = poll.session != null && poll.session == closedSession
        if (!late) {
            polledAt = now
            heardAt = now
            polls++
            main.removeCallbacks(pollsStopped)
            main.postDelayed(pollsStopped, POLL_GONE_MS)
            if (!polling) {
                polling = true
                DebugLog.log("NavMap is asking for turns" + if (poll.compact) " (compact)" else "")
            }
            if (active && latest != null && !shown) onShowing()
            poll.position?.let(::onWatchPosition)
            poll.crashedRun?.let(::onUnclosedRun)
        }
        val info = latest
        return when {
            info == null -> WatchProtocol.notNavigatingReply(if (endedAt > 0) now - endedAt else null, endArrived)
            poll.compact -> WatchProtocol.compactNavReply(info, arrow, arrowHash, routeText, routeVersion, poll)
            else -> WatchProtocol.navReply(info, arrow, arrowHash, route, routeVersion, poll)
        }
    }

    /** NavMap 0.14+ says its last run, opened at [openedAt] (Unix seconds), never closed normally. Logged once. */
    private fun onUnclosedRun(openedAt: Long) {
        if (openedAt == crashLogged) return
        crashLogged = openedAt
        val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(openedAt * 1000))
        DebugLog.log("NavMap opened at $time never closed normally: it crashed (IQ! on the watch), or the watch turned off")
    }

    private val pollsStopped = Runnable {
        polling = false
        DebugLog.log("NavMap stopped asking for turns")
        // No prompt: the polls also stop while the watch is out of Bluetooth range, and "Open NavMap?" would close a
        // NavMap that's still open. One the watch closes says bye, and is opened again from there. Turns go over
        // Connect IQ meanwhile, if NavMap takes them that way.
        pump()
    }

    // ---- NavMap open, navigating or not ----

    /** NavMap said hello or answered "where": it's open, so it's asked where it is every 15 s unless it polls. */
    private fun onAppOpen(device: IQDevice) {
        wheresUnanswered = 0
        watch = device
        heardAt = SystemClock.elapsedRealtime()
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
        // A NavMap that polls gets the turn and the line with its next poll, a second or two away. Sent over Connect IQ
        // too, they only add to what Bluetooth carries, and a line that arrives late could replace a newer one.
        val pollsFresh = polling && SystemClock.elapsedRealtime() - polledAt < POLL_FRESH_MS
        when {
            watchReady && endPending -> {
                kind = Kind.END
                message = WatchProtocol.end(endArrived)
            }
            watchReady && navDirty && info != null && !pollsFresh -> {
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
            watchReady && routeDirty && sentRoute != null && !pollsFresh -> {
                kind = Kind.ROUTE
                message = WatchProtocol.route(sentRoute, routeVersion)
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
        routeFails = 0
        routeRetryAt = 0L
        offRouteTries = 0
        DebugLog.log("Destination: ${place.name ?: place}")
        requestRoute()
    }

    fun setPhoneOrigin(origin: LatLng) {
        phoneOrigin = origin
        requestRoute()
    }

    /** For the simulator: a route made up on the phone. */
    fun setRoute(points: List<LatLng>) {
        setRouteLine(points)
        pump()
    }

    /** A new line, or none: it gets a new version, so a polling NavMap fetches it. */
    private fun setRouteLine(points: List<LatLng>?) {
        if (points == null && route == null) return
        routePoints = points
        offRouteSince = 0L
        route = points?.let { Polyline.forWatch(it) }
        routeText = points?.let { NavCodec.routeForWatch(it) }
        routeDirty = points != null
        // Random, so a restarted NavBridge never reuses the version of a line the watch still has.
        val old = routeVersion
        do routeVersion = Random.nextInt(1, 1 shl 30) while (routeVersion == old)
    }

    /**
     * The user withdrew their consent: every position NavBridge has goes, with the route line made from one. NavMap is
     * told with its next poll, and forgets its key and route line too (see NavEndpoint).
     */
    fun forgetLocation() {
        watchPosition = null
        clearDestination()
        roadsWanted = false
        Roads.stop()
        pump()
    }

    private fun clearDestination() {
        destination = null
        setRouteLine(null)
        routeSummary = ""
        phoneOrigin = null
        rerouteWanted = false
        routeFails = 0
        routeRetryAt = 0L
        offRouteTries = 0
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

    /**
     * Google Maps finds a new way when the rider leaves its route, often without the "Rerouting" that [onReroute]
     * waits for. So the watch's position is checked against the line as well: further than [OFF_ROUTE_M] from it for
     * [OFF_ROUTE_MS] gets a new line from where the watch is. A new line the watch doesn't get onto (it can't start
     * in a building, say) makes the next one wait twice as long, from 30 s up to 4 minutes, as each route costs.
     */
    private fun checkOffRoute(position: LatLng) {
        val line = routePoints
        if (!active || line == null || routing || destination == null) return
        val now = SystemClock.elapsedRealtime()
        val off = Polyline.distanceTo(line, position)
        if (off <= OFF_ROUTE_M) {
            offRouteSince = 0L
            offRouteTries = 0
            return
        }
        if (offRouteSince == 0L) offRouteSince = now
        if (now - offRouteSince < OFF_ROUTE_MS || now - lastRouteAt < OFF_ROUTE_GAP_MS shl minOf(offRouteTries, 3)) return
        DebugLog.log("The watch is ${off.roundToInt()} m off the route line")
        offRouteSince = 0L
        offRouteTries++
        rerouteWanted = true
        phoneOrigin = null
    }

    private fun onWatchPosition(position: LatLng) {
        // Only with the user's consent, which NavMap 0.10+ also waits for before it sends a position at all.
        if (!Prefs.consented(context)) return
        watchPosition = position
        main.removeCallbacks(askWhere)
        checkOffRoute(position)
        requestRoute()
        // The simulated ride's route line starts where the watch is, so it can only go once the watch has a fix.
        Simulator.onWatchReady()
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
        // The last try failed: no internet, say. Tried again from where the watch is then.
        if (SystemClock.elapsedRealtime() < routeRetryAt) return
        if (!Prefs.consented(context)) {
            routeSummary = "No route line until you agree to the terms (Privacy and terms)"
            return
        }
        val key = Prefs.mapsKey(context)
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
                routeFails = 0
                routeRetryAt = 0L
                setRouteLine(r.points)
                routeSummary = r.summary()
                DebugLog.log("Route: ${r.summary()}, ${route!!.size / 2} points for the watch")
                pump()
            }.onFailure { e ->
                // Wanted still, so the next position from the watch asks again: after 15 s, then 30, 60 and 120 at most.
                rerouteWanted = true
                routeRetryAt = lastRouteAt + (ROUTE_RETRY_MS shl minOf(routeFails, 3))
                routeFails++
                routeSummary = "Route failed: ${e.message}"
                DebugLog.log("$routeSummary (trying again in ${(routeRetryAt - lastRouteAt) / 1000} s)")
            }
        }
    }

    private fun describe(info: NavInfo) =
        listOfNotNull(info.distance?.text, info.instruction.ifEmpty { null }, info.trip.ifEmpty { null }).joinToString(" · ")

    private fun describe(raw: RawNotification) =
        "title \"${raw.title}\", text \"${raw.text}\", subText \"${raw.subText}\", category ${raw.category}, ongoing ${raw.ongoing}"

    private fun setStatus(text: String) {
        if (status != text) DebugLog.log(text)
        status = text
    }
}
