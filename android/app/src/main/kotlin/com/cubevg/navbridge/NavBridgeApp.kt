package com.cubevg.navbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class NavBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Alerts.createChannel(this)
        // Garmin Connect's broadcasts only reach a receiver the SDK registers while it runs, and NavMap polls
        // NavServer whenever it's open: both start with the process, which the notification listener keeps alive.
        CiqBridge.init(this)
        NavSession.init(this)
        NavServer.start(this)
    }
}
