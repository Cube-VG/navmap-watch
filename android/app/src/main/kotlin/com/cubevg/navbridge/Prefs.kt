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

    /** The Routes API key: one typed into the app wins over the one built in. */
    fun routesKey(context: Context): String =
        prefs(context).getString("routes_key", null)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.MAPS_API_KEY

    fun setRoutesKey(context: Context, key: String) {
        prefs(context).edit().putString("routes_key", key.trim()).apply()
    }

    /** NavBridge 0.3's binder registration has been removed from Garmin Connect (see [CiqBridge]). */
    fun binderForgotten(context: Context): Boolean = prefs(context).getBoolean("binder_forgotten", false)

    fun setBinderForgotten(context: Context) {
        prefs(context).edit().putBoolean("binder_forgotten", true).apply()
    }
}
