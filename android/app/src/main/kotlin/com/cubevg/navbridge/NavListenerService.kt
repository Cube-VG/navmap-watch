package com.cubevg.navbridge

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cubevg.navmap.core.MapsNotificationParser
import com.cubevg.navmap.core.NavInfo
import com.cubevg.navmap.core.RawNotification

/** Watches Google Maps' notifications: its turn-by-turn card starts, updates and ends the navigation. */
class NavListenerService : NotificationListenerService() {
    private val navKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        connected = true
        DebugLog.log("Notification access on")
        // Navigation may already be running.
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            DebugLog.log("Couldn't read current notifications: $e")
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        DebugLog.log("Notification access disconnected, asking to reconnect")
        requestRebind(ComponentName(this, NavListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MapsNotificationParser.MAPS_PACKAGE) return
        val raw = NotificationReader.raw(sbn)
        val info = MapsNotificationParser.parse(raw)
        lastRaw = raw
        lastParsed = info
        if (info == null) {
            // A card that stopped being turn-by-turn counts as gone.
            if (navKeys.remove(sbn.key) && navKeys.isEmpty()) NavSession.onNavEnded()
            return
        }
        if (Simulator.running) Simulator.stop()
        navKeys += sbn.key
        NavSession.onNav(info, NotificationReader.arrow(this, sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (navKeys.remove(sbn.key) && navKeys.isEmpty()) NavSession.onNavEnded()
    }

    companion object {
        var connected = false
            private set

        /** The last Google Maps notification and what it was read as, for the setup screen. */
        var lastRaw: RawNotification? = null
            private set
        var lastParsed: NavInfo? = null
            private set
    }
}
