package com.cubevg.navbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cubevg.navmap.core.WatchProtocol
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * The single ConnectIQ connection to Garmin Connect. All callbacks are moved to the main thread.
 *
 * Watch messages, device status and the answers to "open" and "send" arrive as broadcasts, registered per device.
 * Garmin's binder service is never used: registering for it stops those broadcasts, and Garmin Connect never
 * delivers through it (Garmin bug CIQQA-4631). NavMap's turns don't depend on any of this: it fetches them over
 * HTTP ([NavServer]).
 */
object CiqBridge {
    enum class State { STARTING, READY, NO_GARMIN_CONNECT, UPDATE_GARMIN_CONNECT, ERROR }

    fun interface Listener {
        fun onWatchMessage(device: IQDevice, message: WatchProtocol.FromWatch)
    }

    private const val RETRY_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private lateinit var ciq: ConnectIQ
    private val watchApp = IQApp(BuildConfig.WATCH_APP_ID)
    private val appEventsRegistered = mutableSetOf<Long>()
    private val deviceStatus = mutableMapOf<Long, IQDevice.IQDeviceStatus>()

    var listener: Listener? = null
    var state = State.STARTING
        private set
    var devices: List<IQDevice> = emptyList()
        private set

    /** What Garmin Connect reported about NavMap on each watch: installed version, or null if missing. */
    val installed = mutableMapOf<Long, Int?>()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        ciq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        start()
    }

    private fun start() {
        state = State.STARTING
        try {
            ciq.initialize(context, false, sdkListener)
        } catch (e: Exception) {
            fail("ConnectIQ start failed: $e")
        }
    }

    private val sdkListener = object : ConnectIQ.ConnectIQListener {
        override fun onSdkReady() {
            main.post { onReady() }
        }

        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
            main.post {
                state = when (status) {
                    ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> State.NO_GARMIN_CONNECT
                    ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> State.UPDATE_GARMIN_CONNECT
                    else -> State.ERROR
                }
                DebugLog.log("Garmin Connect not available: $status")
                main.postDelayed(::start, RETRY_MS)
            }
        }

        override fun onSdkShutDown() {
            main.post {
                DebugLog.log("Garmin Connect connection closed, reconnecting")
                appEventsRegistered.clear()
                state = State.STARTING
                main.postDelayed(::start, 3_000)
            }
        }
    }

    private fun onReady() {
        if (!Prefs.binderForgotten(context)) {
            // NavBridge 0.3 registered NavMap for the binder service, and Garmin Connect remembers that. Undo it once.
            // The SDK shuts itself down afterwards; onSdkShutDown connects again.
            try {
                ciq.unregisterAppFromUsingBinderService(watchApp.applicationId, context)
                Prefs.setBinderForgotten(context)
                DebugLog.log("Garmin Connect: binder registration removed")
                return
            } catch (e: Exception) {
                DebugLog.log("Couldn't remove the binder registration: $e")
            }
        }
        state = State.READY
        DebugLog.log("Garmin Connect ready")
        refresh()
    }

    private fun fail(message: String) {
        DebugLog.log(message)
        state = State.ERROR
        main.postDelayed(::start, RETRY_MS)
    }

    /** Reloads the paired watches, their connection state and whether NavMap is installed on them. */
    fun refresh() {
        if (state != State.READY) return
        devices = try {
            ciq.knownDevices.orEmpty()
        } catch (e: Exception) {
            DebugLog.log("Couldn't list watches: $e")
            emptyList()
        }
        for (device in devices) {
            val id = device.deviceIdentifier
            try {
                // Reports the current state straight away, then every change.
                ciq.registerForDeviceEvents(device) { d, status -> main.post { onDeviceStatus(d, status) } }
                if (appEventsRegistered.add(id)) ciq.registerForAppEvents(device, watchApp, appListener)
            } catch (e: Exception) {
                DebugLog.log("Couldn't watch ${device.friendlyName}: $e")
            }
            if (isConnected(device)) checkInstalled(device)
        }
    }

    private fun onDeviceStatus(device: IQDevice, status: IQDevice.IQDeviceStatus) {
        val id = device.deviceIdentifier
        val was = deviceStatus.put(id, status)
        if (was == status) return
        DebugLog.log("${device.friendlyName}: $status")
        if (status == IQDevice.IQDeviceStatus.CONNECTED) {
            checkInstalled(device)
            NavSession.onWatchConnected(device)
        }
    }

    private fun checkInstalled(device: IQDevice) {
        try {
            ciq.getApplicationInfo(watchApp.applicationId, device, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    main.post { installed[device.deviceIdentifier] = app.version() }
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    main.post { installed[device.deviceIdentifier] = null }
                }
            })
        } catch (e: Exception) {
            DebugLog.log("Couldn't check NavMap on ${device.friendlyName}: $e")
        }
    }

    fun isConnected(device: IQDevice): Boolean =
        (deviceStatus[device.deviceIdentifier] ?: device.status) == IQDevice.IQDeviceStatus.CONNECTED

    fun status(device: IQDevice): IQDevice.IQDeviceStatus = deviceStatus[device.deviceIdentifier] ?: device.status

    fun connectedWatch(): IQDevice? {
        if (state == State.READY) {
            try {
                ciq.connectedDevices?.firstOrNull()?.let { return it }
            } catch (e: Exception) {
                DebugLog.log("Couldn't list connected watches: $e")
            }
        }
        return devices.firstOrNull { isConnected(it) }
    }

    /** Shows the "Open NavMap?" prompt on the watch, or reports that NavMap is already open. */
    fun openApp(device: IQDevice, done: (ConnectIQ.IQOpenApplicationStatus?) -> Unit) {
        try {
            ciq.openApplication(device, watchApp) { _, _, status -> main.post { done(status) } }
        } catch (e: Exception) {
            DebugLog.log("Open NavMap failed: $e")
            main.post { done(null) }
        }
    }

    /** Sends one message. Garmin's SDK keeps one status listener per app, so callers wait for [done] before the next. */
    fun send(device: IQDevice, message: Any, done: (ConnectIQ.IQMessageStatus?) -> Unit) {
        try {
            ciq.sendMessage(device, watchApp, message) { _, _, status -> main.post { done(status) } }
        } catch (e: Exception) {
            DebugLog.log("Send failed: $e")
            main.post { done(null) }
        }
    }

    private val appListener = ConnectIQ.IQApplicationEventListener { device, _, data, status ->
        main.post {
            if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                DebugLog.log("Bad message from watch: $status")
                return@post
            }
            // Garmin Connect's message broadcasts carry the watch's number but not its name: use the listed watch.
            val watch = devices.firstOrNull { it == device } ?: device
            for (item in data.orEmpty()) listener?.onWatchMessage(watch, WatchProtocol.parse(item))
        }
    }
}
