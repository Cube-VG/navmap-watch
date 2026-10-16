package com.cubevg.navbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.cubevg.navmap.core.NavKind
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Setup checklist, watch status, the simulator and the log. */
class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val garminConnect = "com.garmin.android.apps.connectmobile"

    private lateinit var sessionStatus: TextView
    private lateinit var sessionDetail: TextView
    private lateinit var simulate: MaterialButton
    private lateinit var watchStatus: TextView
    private lateinit var routeStatus: TextView
    private lateinit var keyLayout: TextInputLayout
    private lateinit var keyInput: TextInputEditText
    private lateinit var keySave: View
    private lateinit var lastNotification: TextView
    private lateinit var log: TextView

    private lateinit var access: Step
    private lateinit var restricted: Step
    private lateinit var notifications: Step
    private lateinit var battery: Step
    private lateinit var garminBattery: Step
    private lateinit var location: Step

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        sessionStatus = findViewById(R.id.session_status)
        sessionDetail = findViewById(R.id.session_detail)
        simulate = findViewById(R.id.simulate)
        watchStatus = findViewById(R.id.watch_status)
        routeStatus = findViewById(R.id.route_status)
        keyLayout = findViewById(R.id.key_layout)
        keyInput = findViewById(R.id.key_input)
        keySave = findViewById(R.id.key_save)
        lastNotification = findViewById(R.id.last_notification)
        log = findViewById(R.id.log)

        simulate.setOnClickListener {
            if (Simulator.running) Simulator.stop() else Simulator.start()
            render()
        }
        findViewById<View>(R.id.open_watch).setOnClickListener {
            if (CiqBridge.connectedWatch() == null) toast("No watch connected to Garmin Connect") else NavSession.retryOpen()
        }
        findViewById<View>(R.id.refresh_watch).setOnClickListener {
            CiqBridge.refresh()
            render()
        }
        findViewById<View>(R.id.copy_log).setOnClickListener {
            val clip = ClipData.newPlainText("NavBridge log", DebugLog.lines().joinToString("\n"))
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            toast("Log copied")
        }
        setUpModes()
        keySave.setOnClickListener {
            Prefs.setRoutesKey(this, keyInput.text?.toString().orEmpty())
            toast("Saved")
            render()
        }

        val steps = findViewById<LinearLayout>(R.id.steps)
        access = Step(steps, "Notification access", "Lets NavBridge read Google Maps' turn-by-turn notification.") { openListenerSettings() }
        restricted = Step(
            steps,
            "Switch greyed out?",
            "Android blocks this for apps installed from a file. Open App info, tap ⋮ (top right), choose " +
                "“Allow restricted settings”, then turn on notification access again.",
            "App info",
        ) { openAppDetails(packageName) }
        notifications = Step(steps, "Notifications", "For the reminder when the watch needs a button press.") {
            if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        battery = Step(steps, "Battery: NavBridge", "Unrestricted, so Android doesn't stop it mid-ride.") {
            startSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        garminBattery = Step(
            steps,
            "Battery: Garmin Connect",
            "Set to Unrestricted in App info → Battery, so it keeps talking to the watch.",
            "Open",
        ) { openAppDetails(garminConnect) }
        location = Step(
            steps,
            "Location (optional)",
            "Starts the route line where your phone is when you share a place. Without it, the watch's GPS is used.",
        ) { askPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    override fun onResume() {
        super.onResume()
        CiqBridge.refresh()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private fun setUpModes() {
        val group = findViewById<ChipGroup>(R.id.modes)
        val ids = mapOf(
            TravelMode.MOTORBIKE to R.id.mode_motorbike,
            TravelMode.CAR to R.id.mode_car,
            TravelMode.BICYCLE to R.id.mode_bicycle,
            TravelMode.WALK to R.id.mode_walk,
        )
        group.check(ids.getValue(Prefs.travelMode(this)))
        group.setOnCheckedStateChangeListener { _, checked ->
            val mode = ids.entries.firstOrNull { it.value == checked.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            Prefs.setTravelMode(this, mode)
        }
    }

    private fun render() {
        val accessOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        access.set(accessOn, if (accessOn) "On" else null)
        restricted.view.isVisible = !accessOn
        val notifyOn = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifications.set(notifyOn)
        val power = getSystemService(PowerManager::class.java)
        battery.set(power.isIgnoringBatteryOptimizations(packageName))
        val gcInstalled = isInstalled(garminConnect)
        garminBattery.view.isVisible = gcInstalled
        if (gcInstalled) garminBattery.set(power.isIgnoringBatteryOptimizations(garminConnect))
        location.set(Locator.hasPermission(this))

        renderSession()
        renderWatch(gcInstalled)
        renderRoute()
        renderLastNotification()
        log.text = DebugLog.lines().takeLast(80).reversed().joinToString("\n")
    }

    private fun renderSession() {
        sessionStatus.text = NavSession.status
        val latest = NavSession.latest
        sessionDetail.text = when {
            NavSession.active && latest != null -> buildString {
                append(if (latest.kind == NavKind.NAVIGATING) "Next: " else "")
                latest.distance?.let { append(it.text).append(" · ") }
                append(latest.instruction)
                if (latest.trip.isNotEmpty()) append("\n").append(latest.trip)
                append("\nMessages delivered to the watch: ").append(NavSession.sent)
            }
            NavSession.active -> "Navigation ending"
            else -> "Start navigation in Google Maps and your watch asks to open NavMap. " +
                "Or press Simulate to try it at home."
        }
        simulate.setText(if (Simulator.running) R.string.stop_simulation else R.string.simulate)
    }

    private fun renderWatch(gcInstalled: Boolean) {
        watchStatus.text = buildString {
            append(
                when (CiqBridge.state) {
                    CiqBridge.State.READY -> "Garmin Connect: connected" + if (CiqBridge.binderMode) "" else " (broadcast mode)"
                    CiqBridge.State.STARTING -> "Garmin Connect: connecting…"
                    CiqBridge.State.NO_GARMIN_CONNECT ->
                        if (gcInstalled) "Garmin Connect: not answering. Open it once and sign in." else "Garmin Connect isn't installed"
                    CiqBridge.State.UPDATE_GARMIN_CONNECT -> "Garmin Connect needs an update"
                    CiqBridge.State.ERROR -> "Garmin Connect: error, retrying"
                },
            )
            for (device in CiqBridge.devices) {
                append("\n").append(device.friendlyName).append(": ")
                append(CiqBridge.status(device).name.lowercase().replace('_', ' '))
                if (CiqBridge.installed.containsKey(device.deviceIdentifier)) {
                    val version = CiqBridge.installed[device.deviceIdentifier]
                    append(if (version == null) " · NavMap not installed" else " · NavMap v$version installed")
                }
            }
            if (CiqBridge.state == CiqBridge.State.READY && CiqBridge.devices.isEmpty()) append("\nNo watch paired in Garmin Connect")
        }
    }

    private fun renderRoute() {
        val key = Prefs.routesKey(this)
        val builtIn = BuildConfig.MAPS_API_KEY.isNotEmpty()
        keyLayout.isVisible = !builtIn
        keySave.isVisible = !builtIn
        routeStatus.text = when {
            key.isEmpty() -> "This build has no Routes API key, so there's no route line. Turns still work."
            NavSession.routeSummary.isNotEmpty() -> NavSession.routeSummary
            NavSession.destination != null -> "Destination: ${NavSession.destination?.name ?: "shared place"}"
            else -> "Ready"
        }
    }

    private fun renderLastNotification() {
        val raw = NavListenerService.lastRaw
        lastNotification.text = if (raw == null) {
            "None yet"
        } else {
            val parsed = NavListenerService.lastParsed
            "title:   ${raw.title}\ntext:    ${raw.text}\nsubText: ${raw.subText}\ncategory: ${raw.category}, ongoing: ${raw.ongoing}\n" +
                if (parsed == null) {
                    "→ not a turn-by-turn notification"
                } else {
                    "→ ${parsed.kind}: ${parsed.distance?.let { "${it.meters} m, " } ?: ""}${parsed.instruction} | ${parsed.trip}"
                }
        }
    }

    private fun openListenerSettings() {
        val component = ComponentName(this, NavListenerService::class.java).flattenToString()
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else {
            null
        }
        if (detail == null || !startSafely(detail)) startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAppDetails(pkg: String) {
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        toast("Couldn't open settings")
        false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** One line of the setup checklist. */
    private inner class Step(parent: LinearLayout, title: String, private val detail: String, action: String = "Allow", onClick: () -> Unit) {
        val view: View = layoutInflater.inflate(R.layout.item_step, parent, false).also { parent.addView(it) }
        private val icon: TextView = view.findViewById(R.id.step_icon)
        private val detailView: TextView = view.findViewById(R.id.step_detail)
        private val button: MaterialButton = view.findViewById(R.id.step_button)

        init {
            view.findViewById<TextView>(R.id.step_title).text = title
            detailView.text = detail
            button.text = action
            button.setOnClickListener { onClick() }
        }

        fun set(done: Boolean, doneDetail: String? = null) {
            icon.text = if (done) "✓" else "!"
            icon.setTextColor(getColor(if (done) R.color.step_done else R.color.step_todo))
            detailView.text = if (done && doneDetail != null) doneDetail else detail
            button.isVisible = !done
        }
    }
}
