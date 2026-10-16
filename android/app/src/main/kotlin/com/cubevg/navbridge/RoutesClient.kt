package com.cubevg.navbridge

import android.os.Handler
import android.os.Looper
import com.cubevg.navmap.core.Destination
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.Polyline
import com.cubevg.navmap.core.ShareParser
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

/** Google's Routes API for the route line, and short-link resolving for shared places. Network work runs off the main thread. */
object RoutesClient {
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELDS = "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration"

    data class Route(val points: List<LatLng>, val meters: Int, val seconds: Int, val mode: String) {
        fun summary(): String {
            val km = if (meters >= 10_000) "${meters / 1000} km" else String.format(Locale.US, "%.1f km", meters / 1000.0)
            val min = (seconds + 59) / 60
            val time = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
            return "$km, $time by ${mode.lowercase().replace('_', '-')}"
        }
    }

    class NoRoute(message: String) : IOException(message)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun computeRoute(key: String, origin: LatLng, destination: Destination, mode: TravelMode, done: (Result<Route>) -> Unit) {
        worker.execute {
            val result = runCatching {
                try {
                    request(key, origin, destination, mode.routes)
                } catch (e: IOException) {
                    // Two-wheeler routing only exists in some countries; driving is the closest match elsewhere.
                    if (mode == TravelMode.MOTORBIKE) request(key, origin, destination, TravelMode.CAR.routes) else throw e
                }
            }
            main.post { done(result) }
        }
    }

    private fun request(key: String, origin: LatLng, destination: Destination, travelMode: String): Route {
        val body = JSONObject()
            .put("origin", waypoint(origin))
            .put("destination", waypoint(destination))
            .put("travelMode", travelMode)
            .put("polylineQuality", "HIGH_QUALITY")
            .put("languageCode", Locale.getDefault().toLanguageTag())
        if (travelMode == "DRIVE" || travelMode == "TWO_WHEELER") body.put("routingPreference", "TRAFFIC_AWARE")

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", key)
            conn.setRequestProperty("X-Goog-FieldMask", FIELDS)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("Routes API $code: ${reason ?: text.take(200)}")
            }
            val routes = JSONObject(text).optJSONArray("routes")
            if (routes == null || routes.length() == 0) throw NoRoute("No $travelMode route found")
            val r = routes.getJSONObject(0)
            val points = Polyline.decode(r.getJSONObject("polyline").getString("encodedPolyline"))
            if (points.size < 2) throw NoRoute("Empty route")
            val seconds = r.optString("duration").removeSuffix("s").toDoubleOrNull()?.toInt() ?: 0
            return Route(points, r.optInt("distanceMeters"), seconds, travelMode)
        } finally {
            conn.disconnect()
        }
    }

    private fun waypoint(p: LatLng) =
        JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lng)))

    private fun waypoint(d: Destination): JSONObject = when (d) {
        is Destination.Point -> waypoint(LatLng(d.lat, d.lng))
        is Destination.Place -> JSONObject().put("placeId", d.placeId)
        is Destination.Address -> JSONObject().put("address", d.query)
    }

    /**
     * Follows a maps.app.goo.gl link to the full Google Maps URL without loading the page.
     * Blocking: call from a worker thread.
     */
    fun resolveShortLink(url: String): String {
        var current = url
        repeat(5) {
            if (!ShareParser.isShortLink(current)) return unwrapConsent(current)
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NavBridge")
                conn.responseCode
                val location = conn.getHeaderField("Location") ?: return current
                current = URL(URL(current), location).toString()
            } finally {
                conn.disconnect()
            }
        }
        return unwrapConsent(current)
    }

    // In the EU the redirect can go to a cookie-consent page that carries the real link in "continue".
    private fun unwrapConsent(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host?.startsWith("consent.") != true) return url
        val next = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("continue=") }?.substringAfter('=')
        return next?.let { URLDecoder.decode(it, "UTF-8") } ?: url
    }
}
