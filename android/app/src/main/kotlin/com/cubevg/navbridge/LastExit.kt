package com.cubevg.navbridge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why NavBridge last stopped, written to the log as it starts again: the log lives only as long as the process, and
 * Android starts NavBridge again by itself, for the notification listener, so a field test only shows a log that
 * begins anew. Android 11+ keeps a record of how each process ended; a crash also leaves its stack trace in a file,
 * written as it happens.
 */
object LastExit {
    private const val CRASH_FILE = "last-crash.txt"
    private const val FRAMES = 4 // stack frames kept after each exception line, besides NavBridge's own

    fun init(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastExit(context) else null
        DebugLog.log("NavBridge started" + (exit?.let { ". It last stopped $it" } ?: ""))
        logCrash(file)
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                file.writeText("${System.currentTimeMillis()} ${thread.name}\n${e.stackTraceToString()}")
            } catch (_: Throwable) {
                // Nothing more to be done: Android's handler still reports the crash.
            }
            next?.uncaughtException(thread, e)
        }
    }

    private fun logCrash(file: File) {
        if (!file.exists()) return
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            emptyList()
        }
        file.delete()
        val head = lines.firstOrNull().orEmpty()
        val at = head.substringBefore(' ').toLongOrNull()?.let(::time) ?: "?"
        // Each exception line ("Caused by" too), its first few frames, and every frame in NavBridge's own code.
        var frames = 0
        val trace = lines.drop(1).filter { line ->
            val frame = line.startsWith("\tat ")
            frames = if (frame) frames + 1 else 0
            !line.startsWith("\t...") && (!frame || frames <= FRAMES || "com.cubevg." in line)
        }.take(40)
        DebugLog.log("NavBridge crashed at $at on thread ${head.substringAfter(' ', "?")}:\n" + trace.joinToString("\n") { it.trim() })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lastExit(context: Context): String? {
        val exit = try {
            context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 1)?.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return null
        val why = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "because it crashed"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "because it crashed in native code"
            ApplicationExitInfo.REASON_ANR -> "because it stopped responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "because Android needed the memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "because Android found it used too much battery or CPU"
            ApplicationExitInfo.REASON_SIGNALED -> "because it was killed (signal ${exit.status})"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "because it was force-stopped, by the user or the phone"
            ApplicationExitInfo.REASON_USER_STOPPED -> "because its Android user profile was stopped"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "because a permission changed"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "because an app it depends on stopped"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "because it failed to start"
            ApplicationExitInfo.REASON_EXIT_SELF -> "by itself"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "to be updated"
            ApplicationExitInfo.REASON_OTHER -> "because Android stopped it"
            else -> "(reason ${exit.reason})"
        }
        return "at ${time(exit.timestamp)} $why" + (exit.description?.let { ": $it" } ?: "")
    }

    private fun time(millis: Long) = SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(millis))
}
