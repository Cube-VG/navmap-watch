package com.cubevg.navbridge

import android.content.Context

/** How the user travels: picks the Routes API mode for the route line and the Google Maps navigation mode. */
enum class TravelMode(val routes: String, val maps: Char, val urlMode: String) {
    MOTORBIKE("TWO_WHEELER", 'l', "two-wheeler"),
    CAR("DRIVE", 'd', "driving"),
    BICYCLE("BICYCLE", 'b', "bicycling"),
    WALK("WALK", 'w', "walking"),
}

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("navbridge", Context.MODE_PRIVATE)

    fun travelMode(context: Context): TravelMode =
        prefs(context).getString("travel_mode", null)?.let { name -> TravelMode.entries.firstOrNull { it.name == name } }
            ?: TravelMode.MOTORBIKE

    fun setTravelMode(context: Context, mode: TravelMode) {
        prefs(context).edit().putString("travel_mode", mode.name).apply()
    }

    /**
     * The user's own Google Maps key, "" until set: the route line comes from the Routes API with it, and NavMap gets
     * it for the map pictures (see NavEndpoint.withMapsKey). setup.sh sets it over adb (see [SetupProvider]), or it's
     * typed in. Nothing is built in. Kept under its old name, from when only the route line used it.
     */
    fun mapsKey(context: Context): String = prefs(context).getString("routes_key", null)?.trim().orEmpty()

    fun setMapsKey(context: Context, key: String) {
        prefs(context).edit().putString("routes_key", key.trim()).apply()
    }

    /** Google API keys are "AIza" and 35 more characters. */
    fun looksLikeMapsKey(key: String) = MAPS_KEY.matches(key.trim())

    private val MAPS_KEY = Regex("AIza[0-9A-Za-z_-]{35}")

    /**
     * When the user agreed to Google's terms and to NavBridge using their location (System.currentTimeMillis), or 0.
     * Google requires express, prior and revocable consent before an app obtains or keeps anyone's location, so until
     * then NavBridge reads no position and NavMap gets no key (see NavEndpoint). Asked again if [CONSENT_VERSION] goes
     * up, which it should whenever what NavBridge does with the location changes.
     */
    fun consentedAt(context: Context): Long =
        if (prefs(context).getInt("consent_version", 0) >= CONSENT_VERSION) prefs(context).getLong("consent_at", 0) else 0

    fun consented(context: Context) = consentedAt(context) > 0

    fun setConsented(context: Context, agreed: Boolean) {
        prefs(context).edit().apply {
            if (agreed) {
                putInt("consent_version", CONSENT_VERSION)
                putLong("consent_at", System.currentTimeMillis())
            } else {
                remove("consent_version")
                remove("consent_at")
            }
        }.apply()
    }

    private const val CONSENT_VERSION = 1

    /** The paired watch's token (see WatchPairing), or null. */
    fun watchToken(context: Context): String? = prefs(context).getString("watch_token", null)

    fun setWatchToken(context: Context, token: String?) {
        prefs(context).edit().apply { if (token == null) remove("watch_token") else putString("watch_token", token) }.apply()
    }

    /** NavBridge 0.3's binder registration has been removed from Garmin Connect (see [CiqBridge]). */
    fun binderForgotten(context: Context): Boolean = prefs(context).getBoolean("binder_forgotten", false)

    fun setBinderForgotten(context: Context) {
        prefs(context).edit().putBoolean("binder_forgotten", true).apply()
    }
}
