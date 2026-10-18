package com.cubevg.navbridge

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.cubevg.navmap.core.MapsNotificationParser
import com.cubevg.navmap.core.MapsRemoval
import com.cubevg.navmap.core.NavInfo
import com.cubevg.navmap.core.RawNotification

/** Watches Google Maps' notifications: its turn-by-turn card starts, updates and ends the navigation. */
class NavListenerService : NotificationListenerService() {
    private val navKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        connected = true
        instance = this
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
        instance = null
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
            if (sbn.key !in navKeys) return
            if (raw.ongoing && raw.category == MapsNotificationParser.CATEGORY_NAVIGATION) {
                // Still Maps' navigation card, so Maps is still navigating, with something NavBridge can't read.
                NavSession.onNavUnreadable(raw)
                return
            }
            // A card that stopped being turn-by-turn counts as gone.
            navKeys.remove(sbn.key)
            if (navKeys.isEmpty()) NavSession.onNavEnded("it stopped being turn-by-turn", mapsStopped = true)
            return
        }
        if (Simulator.running) Simulator.stop()
        navKeys += sbn.key
        NavSession.onNav(info, NotificationReader.arrow(this, sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!navKeys.remove(sbn.key) || navKeys.isNotEmpty()) return
        val why = MapsRemoval.of(reason)
        NavSession.onNavEnded(why.text, why.mapsStopped)
    }

    companion object {
        var connected = false
            private set
        private var instance: NavListenerService? = null

        /** Reads Maps' cards again; true if one is turn-by-turn, which then goes to [NavSession.onNav]. */
        fun rescan(): Boolean {
            val service = instance ?: return false
            val cards = try {
                service.activeNotifications.orEmpty().filter { it.packageName == MapsNotificationParser.MAPS_PACKAGE }
            } catch (e: Exception) {
                return false
            }
            val nav = cards.firstOrNull { MapsNotificationParser.parse(NotificationReader.raw(it)) != null } ?: return false
            service.onNotificationPosted(nav)
            return true
        }

        /** The last Google Maps notification and what it was read as, for the setup screen. */
        var lastRaw: RawNotification? = null
            private set
        var lastParsed: NavInfo? = null
            private set
    }
}
