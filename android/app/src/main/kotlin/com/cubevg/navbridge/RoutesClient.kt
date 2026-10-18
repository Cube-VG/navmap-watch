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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Google's Routes API for the route line, and short-link resolving for shared places. Network work runs off the main thread. */
object RoutesClient {
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val FIELDS = "routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) NavBridge"

    /** Google front ends that serve every Google host, the short-link one included. */
    private val FRONT_ENDS = listOf("www.google.com", "routes.googleapis.com")

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

    /** The front end that last answered for a short link whose own address didn't. */
    @Volatile
    private var frontEnd: InetAddress? = null

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
            val location = redirectOf(URL(current))
            current = URL(URL(current), location).toString()
        }
        return unwrapConsent(current)
    }

    // Where a short link redirects to. A server that answers without a redirect counts as not answering: on Jio,
    // shared short links came back unchanged in a second (2026-10-18), while Google's servers redirected them.
    private fun redirectOf(link: URL): String {
        frontEnd?.let { address ->
            try {
                return redirectVia(link, address)
            } catch (e: IOException) {
                frontEnd = null
            }
        }
        val failure: IOException
        try {
            return redirectDirect(link)
        } catch (e: IOException) {
            failure = e
        }
        // A mobile network can give the short-link host an address that never answers: Jio's DNS gave maps.app.goo.gl
        // one of Jio's own. Google's other front ends serve it too, so ask those.
        DebugLog.log("${link.host} didn't answer ($failure), asking another Google server")
        val tried = addresses(link.host).toSet()
        for (address in FRONT_ENDS.flatMap(::addresses).distinct().filter { it !in tried }.take(4)) {
            try {
                return redirectVia(link, address).also { frontEnd = address }
            } catch (e: IOException) {
                failure.addSuppressed(e)
            }
        }
        throw failure
    }

    private fun redirectDirect(link: URL): String {
        val conn = link.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            return conn.getHeaderField("Location") ?: throw IOException("HTTP $code without a redirect")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Asks the server at [address] for [link] the way HttpURLConnection asks the link's own host: TLS for that host's
     * name, with a certificate that must carry it.
     */
    private fun redirectVia(link: URL, address: InetAddress): String {
        val host = link.host
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 443), 4_000)
            socket.soTimeout = 8_000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(socket, host, 443, true) as SSLSocket).use { tls ->
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                tls.startHandshake()
                // An SSLSocket checks the certificate's chain but not the name on it.
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                    throw SSLPeerUnverifiedException("$address isn't $host")
                }
                val request = "GET ${link.file.ifEmpty { "/" }} HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\nConnection: close\r\n\r\n"
                tls.outputStream.apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val reply = tls.inputStream.bufferedReader(Charsets.ISO_8859_1)
                val status = reply.readLine()
                if (status?.startsWith("HTTP/") != true) throw IOException("No HTTP answer from $address")
                return generateSequence { reply.readLine() }.takeWhile { it.isNotEmpty() }
                    .firstOrNull { it.startsWith("Location:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    ?: throw IOException("$status without a redirect from $address")
            }
        }
    }

    private fun addresses(host: String): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())

    // In the EU the redirect can go to a cookie-consent page that carries the real link in "continue".
    private fun unwrapConsent(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host?.startsWith("consent.") != true) return url
        val next = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("continue=") }?.substringAfter('=')
        return next?.let { URLDecoder.decode(it, "UTF-8") } ?: url
    }
}
