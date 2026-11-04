package com.cubevg.navbridge

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last few hundred events, shown on the setup screen so field tests can be read back. */
object DebugLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(message: String) {
        Log.i("NavBridge", message)
        lines.addLast("${time.format(Date())}  $message")
        while (lines.size > MAX) lines.removeFirst()

        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log(message)
            val lower = message.lowercase(Locale.US)
            if (lower.startsWith("couldn't") || lower.contains("failed") || lower.contains("crashed")) {
                crashlytics.recordException(NavBridgeIssue(message))
            }
        } catch (_: Throwable) {
            // Firebase Crashlytics not initialized or unavailable
        }
    }

    fun record(throwable: Throwable, message: String? = null) {
        if (message != null) log(message)
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (_: Throwable) {
            // Firebase Crashlytics not initialized or unavailable
        }
    }

    @Synchronized
    fun lines(): List<String> = lines.toList()

    class NavBridgeIssue(message: String) : Exception(message)
}
