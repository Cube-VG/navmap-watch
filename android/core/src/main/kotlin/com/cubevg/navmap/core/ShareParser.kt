package com.cubevg.navmap.core

import java.net.URI
import java.net.URLDecoder

/** Where the user wants to go, taken from a place shared out of Google Maps. */
sealed class Destination {
    abstract val name: String?

    data class Point(val lat: Double, val lng: Double, override val name: String? = null) : Destination()

    data class Place(val placeId: String, override val name: String? = null) : Destination()

    /** A search text or street address for the Routes API to geocode. */
    data class Address(val query: String, override val name: String? = null) : Destination()
}

/** What came in with a share: the first Maps link, and the lines of text around it. */
data class SharedPlace(val url: String?, val name: String?, val lines: List<String>)

/**
 * Google Maps shares a place as text, e.g. "Joe's Pizza\n1435 Broadway, New York\nhttps://maps.app.goo.gl/abc".
 * Short links must be followed first (see [isShortLink]); full links carry the coordinates.
 */
object ShareParser {
    private val urlPattern = Regex("https?://\\S+")
    private val latLngPattern = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
    private val placePin = Regex("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)")
    private val routePin = Regex("!1d(-?\\d+(?:\\.\\d+)?)!2d(-?\\d+(?:\\.\\d+)?)")
    private val viewport = Regex("/@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")

    fun fromShareText(text: String?, subject: String? = null): SharedPlace {
        val all = text.orEmpty().lines().map { Text.clean(it) }.filter { it.isNotEmpty() }
        val url = all.firstNotNullOfOrNull { urlPattern.find(it)?.value }?.trimEnd('.', ',', ')')
        val lines = all.map { urlPattern.replace(it, "").trim() }.filter { it.isNotEmpty() }
        val name = Text.clean(subject).ifEmpty { null } ?: lines.firstOrNull()
        return SharedPlace(url, name, lines)
    }

    fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "goo.gl" || host.endsWith(".goo.gl") || host == "g.co" || host == "share.google"
    }

    /**
     * Reads a destination from a full Google Maps link, or from a geo: or google.navigation: URI.
     * Returns null when the link doesn't name a place, e.g. a plain map view.
     */
    fun destination(url: String, name: String? = null): Destination? {
        if (url.startsWith("geo:") || url.startsWith("google.navigation:")) return fromGeoUri(url, name)
        val uri = runCatching { URI(url.replace(" ", "%20").replace("|", "%7C")) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        val query = params(uri.rawQuery)
        val segments = path.split('/').filter { it.isNotEmpty() }.map(::decode)
        val dir = segments.indexOf("dir")
        val stops = if (dir < 0) emptyList() else segments.drop(dir + 1).filter {
            !it.startsWith("@") && !it.startsWith("data=") && !it.startsWith("am=")
        }
        // A directions link's last stop names the destination, unless it's coordinates. That beats the name the share
        // came with, which for directions is only a label like "Shared route".
        val label = stops.lastOrNull()?.takeIf { latLng(it) == null } ?: name

        // A specific place: /maps/place/<name>/@<view>/data=...!3d<lat>!4d<lng>
        placePin.findAll(path).lastOrNull()?.let {
            return Destination.Point(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), label ?: placeName(path))
        }
        query["query_place_id"]?.let { return Destination.Place(it, name ?: query["query"]) }
        query["destination_place_id"]?.let { return Destination.Place(it, name ?: query["destination"]) }
        for (key in listOf("destination", "daddr", "q", "query", "ll")) {
            val value = query[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return pointOrAddress(value, name)
        }

        if (dir >= 0) {
            // /maps/dir/<from>/<to>/@<view>/data=...: the last !1d<lng>!2d<lat> pair is the destination.
            routePin.findAll(path).lastOrNull()?.let {
                return Destination.Point(it.groupValues[2].toDouble(), it.groupValues[1].toDouble(), label)
            }
            stops.lastOrNull()?.let { return pointOrAddress(it, label) }
        }
        for (marker in listOf("search", "place")) {
            val at = segments.indexOf(marker)
            val value = segments.getOrNull(at + 1)?.takeIf { at >= 0 && !it.startsWith("@") } ?: continue
            return pointOrAddress(value, name)
        }
        return null
    }

    /** The map centre of a link like ".../@40.75,-73.98,17z", for when nothing better is known. */
    fun viewportCentre(url: String): Destination.Point? =
        viewport.find(url)?.let { Destination.Point(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }

    private fun fromGeoUri(uri: String, name: String?): Destination? {
        val body = uri.substringAfter(':')
        val query = params(if (uri.startsWith("google.navigation:")) body else body.substringAfter('?', ""))
        val q = query["q"]
        if (q != null) {
            // geo:0,0?q=lat,lng(Label) or geo:0,0?q=1600 Amphitheatre Pkwy
            val label = Regex("\\(([^)]*)\\)\\s*$").find(q)?.groupValues?.get(1)
            val value = q.replace(Regex("\\([^)]*\\)\\s*$"), "").trim()
            if (value.isNotEmpty()) return pointOrAddress(value, name ?: label)
        }
        val coords = body.substringBefore('?').substringBefore(';')
        val point = latLng(coords) ?: return null
        return if (point.lat == 0.0 && point.lng == 0.0) null else Destination.Point(point.lat, point.lng, name)
    }

    private fun pointOrAddress(value: String, name: String?): Destination {
        val point = latLng(value)
        return if (point != null) Destination.Point(point.lat, point.lng, name) else Destination.Address(value, name ?: value)
    }

    private fun latLng(s: String): LatLng? {
        val m = latLngPattern.matchEntire(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lng = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
    }

    private fun placeName(path: String): String? {
        val segments = path.split('/')
        val at = segments.indexOf("place")
        return segments.getOrNull(at + 1)?.takeIf { at >= 0 && !it.startsWith("@") }?.let(::decode)
    }

    private fun params(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
