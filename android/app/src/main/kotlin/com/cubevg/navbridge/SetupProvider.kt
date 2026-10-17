package com.cubevg.navbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * setup.sh's way in, over USB debugging:
 *
 *     adb shell content call --uri content://com.cubevg.navbridge.setup --method <method> [--arg <value>]
 *
 * Only adb's shell (and root) may call it, so other apps can neither change the key nor read the setup.
 *  - key <Google Maps key>: keeps the user's key (see [Prefs.mapsKey])
 *  - mode <MOTORBIKE|CAR|BICYCLE|WALK>: how the user travels
 *  - pair: forgets the paired watch and opens pairing for 10 minutes
 *  - simulate, stop: starts or stops the demo ride, which asks the watch to open NavMap
 *  - status: what's set up. "consent" is whether the user has agreed on NavBridge's screen; adb can't set it.
 * Each returns the status, which `content call` prints as `Result: Bundle[{key=true, paired=false, …}]`.
 */
class SetupProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) throw SecurityException("Only adb can use NavBridge's setup")
        val context = requireNotNull(context)
        // On the main thread, which also means after NavBridgeApp.onCreate has started NavServer.
        return onMain {
            when (method) {
                "key" -> {
                    val key = arg.orEmpty()
                    require(Prefs.looksLikeMapsKey(key)) { "That doesn't look like a Google Maps key" }
                    Prefs.setMapsKey(context, key)
                }
                "mode" -> Prefs.setTravelMode(context, requireNotNull(TravelMode.entries.firstOrNull { it.name == arg }) { "Unknown mode $arg" })
                "pair" -> NavServer.pairing.forget()
                "simulate" -> if (!Simulator.running) Simulator.start()
                "stop" -> Simulator.stop()
                "status" -> Unit
                else -> throw IllegalArgumentException("Unknown method $method")
            }
            status(context)
        }
    }

    private fun status(context: Context) = Bundle().apply {
        val power = context.getSystemService(PowerManager::class.java)
        putString("version", BuildConfig.VERSION_NAME)
        putBoolean("key", Prefs.mapsKey(context).isNotEmpty())
        putBoolean("consent", Prefs.consented(context)) // only the user can give it, on NavBridge's screen
        putString("mode", Prefs.travelMode(context).name)
        putBoolean("listener", NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
        putBoolean("battery", power.isIgnoringBatteryOptimizations(context.packageName))
        putBoolean("garminBattery", power.isIgnoringBatteryOptimizations(GARMIN_CONNECT))
        putString("garmin", CiqBridge.state.name)
        putString("watch", CiqBridge.connectedWatch()?.friendlyName.orEmpty())
        putBoolean("paired", NavServer.pairing.paired)
        putBoolean("watchHasKey", NavServer.watchHasKey)
        val last = NavServer.lastPollAt
        putLong("lastPollSecs", if (last == 0L) -1 else (SystemClock.elapsedRealtime() - last) / 1000)
    }

    // Throws the block's own exception: Android can't pass an ExecutionException back to adb, but it passes an
    // IllegalArgumentException with its message, which setup.sh shows.
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        Handler(Looper.getMainLooper()).post(task)
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("NavBridge is busy. Try again in a moment.")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val GARMIN_CONNECT = "com.garmin.android.apps.connectmobile"
    }
}
