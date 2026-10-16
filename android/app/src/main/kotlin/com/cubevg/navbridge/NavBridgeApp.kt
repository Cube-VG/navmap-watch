package com.cubevg.navbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class NavBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Alerts.createChannel(this)
        // Garmin Connect hands watch messages to IQGarminBindingService, which looks up ConnectIQ.getInstance().
        // It has to exist from the moment the process starts, even when Garmin Connect is what started it.
        CiqBridge.init(this)
        NavSession.init(this)
    }
}
