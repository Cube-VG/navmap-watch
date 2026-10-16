package com.cubevg.navbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Phone notifications for when the watch needs the user: NavMap not opened, or not installed. */
object Alerts {
    private const val CHANNEL = "watch"
    private const val ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Watch reminders", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "When NavMap on your watch needs a button press"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun openOnWatch(context: Context) = post(
        context,
        "Open NavMap on your watch",
        "The watch didn't answer. Tap Try again for a new prompt, or open NavMap from the watch's app list.",
        retry = true,
    )

    fun notInstalled(context: Context) = post(
        context,
        "NavMap isn't on your watch",
        "Install NavMap on the watch, then start navigation again.",
        retry = false,
    )

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, title: String, text: String, retry: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (retry) {
            val again = PendingIntent.getBroadcast(
                context, 1, Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.RETRY), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Try again", again)
        }
        NotificationManagerCompat.from(context).notify(ID, builder.build())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RETRY) NavSession.retryOpen()
    }

    companion object {
        const val RETRY = "com.cubevg.navbridge.RETRY"
    }
}
