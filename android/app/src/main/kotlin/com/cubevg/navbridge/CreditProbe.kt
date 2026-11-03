package com.cubevg.navbridge

import android.graphics.BitmapFactory
import android.os.SystemClock
import com.cubevg.navmap.core.CreditStrip
import com.cubevg.navmap.core.LatLng
import com.cubevg.navmap.core.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Probes Google Static Maps for the attribution credit strip size (`cw`, `ch`, `cs`)
 * for the current area and zoom level. Runs off the main thread and caches results for
 * up to 12 hours or until moving more than 25 km.
 */
object CreditProbe {
    private const val MAX_DISTANCE_M = 25_000.0
    private const val TTL_MS = 12 * 3600 * 1000L
    private val pool = Executors.newSingleThreadExecutor()

    private var cachedArea: LatLng? = null
    private var cachedZoom: Int = -1
    private var cachedAt: Long = 0L
    @Volatile
    var current: CreditStrip.Measurement? = null
        private set

    @Volatile
    private var inFlight = false

    fun get(position: LatLng?, zoom: Int?, mapsKey: String): CreditStrip.Measurement? {
        if (position == null || zoom == null || mapsKey.isEmpty()) return current

        val now = SystemClock.elapsedRealtime()
        val area = cachedArea
        val stale = area == null ||
            cachedZoom != zoom ||
            now - cachedAt > TTL_MS ||
            distanceMeters(area, position) > MAX_DISTANCE_M

        if (stale && !inFlight) {
            inFlight = true
            pool.execute {
                try {
                    val m = fetch(position, zoom, mapsKey)
                    if (m != null) {
                        current = m
                        cachedArea = position
                        cachedZoom = zoom
                        cachedAt = SystemClock.elapsedRealtime()
                        DebugLog.log("Credit probe measured: ${m.width}x${m.height} (split at ${m.split})")
                    }
                } catch (e: Exception) {
                    DebugLog.log("Credit probe failed: ${e.message}")
                } finally {
                    inFlight = false
                }
            }
        }

        return current
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val latMid = (a.lat + b.lat) * Math.PI / 360.0
        val dy = (b.lat - a.lat) * 111_139.0
        val dx = (b.lng - a.lng) * 111_139.0 * kotlin.math.cos(latMid)
        return kotlin.math.hypot(dx, dy)
    }

    private fun fetch(p: LatLng, zoom: Int, key: String): CreditStrip.Measurement? {
        val url = URL(
            "https://maps.googleapis.com/maps/api/staticmap?center=${p.lat},${p.lng}" +
                "&zoom=$zoom&size=280x60&scale=2&format=png32&style=visibility:off&key=$key",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        return conn.inputStream.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return null
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            CreditStrip.measure(pixels, bmp.width, bmp.height)
        }
    }
}
