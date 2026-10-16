package com.cubevg.navbridge

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.cubevg.navmap.core.ArrowEncoder
import com.cubevg.navmap.core.RawNotification

/** Pulls the text and the maneuver arrow out of a posted notification. */
object NotificationReader {
    private const val ICON_PX = 96

    fun raw(sbn: StatusBarNotification): RawNotification {
        val n = sbn.notification
        val extras = n.extras
        return RawNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            category = n.category,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    /** Google Maps draws the next maneuver as the large icon. Returns NavMap's 48×48 bits, or null. */
    fun arrow(context: Context, sbn: StatusBarNotification): IntArray? = try {
        sbn.notification.getLargeIcon()?.loadDrawable(context)?.let(::encode)
    } catch (e: Exception) {
        DebugLog.log("Couldn't read the turn arrow: $e")
        null
    }

    fun encode(drawable: Drawable): IntArray? {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(ICON_PX * ICON_PX)
        bitmap.getPixels(pixels, 0, ICON_PX, 0, 0, ICON_PX, ICON_PX)
        bitmap.recycle()
        return ArrowEncoder.encode(pixels, ICON_PX, ICON_PX)
    }
}
