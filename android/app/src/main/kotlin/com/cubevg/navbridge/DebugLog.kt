package com.cubevg.navbridge

import android.util.Log
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
    }

    @Synchronized
    fun lines(): List<String> = lines.toList()
}
