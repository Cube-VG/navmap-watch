package com.cubevg.navbridge

import com.cubevg.navmap.core.RoadArea
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenFreeMap's z14 vector tiles (map data © OpenStreetMap contributors), no key needed, kept in a disk cache.
 * The TileJSON names the current data version; tiles are cached per version and older versions deleted.
 * Blocking: worker thread only.
 */
class RoadTiles(private val dir: File, private val userAgent: String) {
    private companion object {
        const val TILEJSON = "https://tiles.openfreemap.org/planet"
        const val TILEJSON_MAX_AGE_MS = 24 * 3600_000L
        const val TILEJSON_RETRY_MS = 10 * 60_000L
        const val MAX_CACHE_BYTES = 64L shl 20
    }

    private var template: String? = null
    private var templateAt = 0L

    /** Tiles fetched over the network, and their bytes, since the last [resetCounts]. */
    var downloaded = 0
        private set
    var downloadedBytes = 0L
        private set

    fun resetCounts() {
        downloaded = 0
        downloadedBytes = 0
    }

    /** The tile as served, gzipped or not; empty when there's nothing in it (open sea). */
    fun tile(tx: Int, ty: Int): ByteArray {
        val file = file(tx, ty)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file.readBytes()
        }
        val url = template().replace("{z}", "${RoadArea.TILE_Z}").replace("{x}", "$tx").replace("{y}", "$ty")
        val bytes = get(url, keepGzip = true)
        downloaded++
        downloadedBytes += bytes.size
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        return bytes
    }

    /** A cached tile that turned out to be broken, so the next try downloads it again. */
    fun forget(tx: Int, ty: Int) {
        file(tx, ty).delete()
    }

    /** Deletes the least recently used tiles once the cache is over 64 MB. */
    fun trim() {
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".pbf") }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun file(tx: Int, ty: Int) = File(dir, "${version(template())}/${RoadArea.TILE_Z}_${tx}_$ty.pbf")

    private fun version(url: String) =
        url.substringAfter("/planet/", "").substringBefore('/')
            .filter { it.isLetterOrDigit() || it in "_-." }.ifEmpty { "current" }

    /** The tile URL template, from the TileJSON at most a day old. */
    private fun template(): String {
        val now = System.currentTimeMillis()
        template?.let { if (now - templateAt < TILEJSON_MAX_AGE_MS) return it }
        val saved = File(dir, "planet.json")
        if (template == null && saved.exists() && now - saved.lastModified() < TILEJSON_MAX_AGE_MS) {
            parse(saved.readBytes())?.let {
                template = it
                templateAt = saved.lastModified()
                return it
            }
        }
        return try {
            val body = get(TILEJSON, keepGzip = false)
            val fresh = parse(body) ?: throw IOException("No tile URL in OpenFreeMap's TileJSON")
            dir.mkdirs()
            saved.writeBytes(body)
            template = fresh
            templateAt = now
            // A new data version: the old tiles are only taking space.
            dir.listFiles { f -> f.isDirectory && f.name != version(fresh) }?.forEach { it.deleteRecursively() }
            fresh
        } catch (e: IOException) {
            // Offline: an older TileJSON still names the cached tiles. Try again in a while.
            val old = (template ?: if (saved.exists()) parse(saved.readBytes()) else null) ?: throw e
            template = old
            templateAt = now - TILEJSON_MAX_AGE_MS + TILEJSON_RETRY_MS
            old
        }
    }

    private fun parse(body: ByteArray): String? =
        runCatching { JSONObject(String(body)).getJSONArray("tiles").getString(0) }.getOrNull()
            ?.takeIf { it.startsWith("https://") && "{z}" in it && "{x}" in it && "{y}" in it }

    private fun get(url: String, keepGzip: Boolean): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            // Asked for explicitly, gzip isn't unpacked on the way in: tiles stay small on disk, and Mvt unpacks them.
            if (keepGzip) conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NO_CONTENT) return ByteArray(0)
            if (code != HttpURLConnection.HTTP_OK) throw IOException("OpenFreeMap: HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
