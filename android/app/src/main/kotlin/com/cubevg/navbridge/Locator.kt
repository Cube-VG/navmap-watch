package com.cubevg.navbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.cubevg.navmap.core.LatLng

/**
 * The phone's last known position, used only to start the route line where the user is when they share a place.
 * Google Maps was open a moment before, so the last fix is fresh; no new fix or background location is needed.
 * None until the user has agreed in NavBridge ([Prefs.consentedAt]), even with the permission granted.
 */
object Locator {
    private const val MAX_AGE_MS = 10 * 60_000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): LatLng? {
        if (!Prefs.consented(context) || !hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val fix = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return if (System.currentTimeMillis() - fix.time < MAX_AGE_MS) LatLng(fix.latitude, fix.longitude) else null
    }
}
