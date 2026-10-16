package com.cubevg.navmap.core

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Reads the lines of one layer of a Mapbox Vector Tile (a protobuf, spec 2.1). Points, polygons and the other
 * layers are skipped. Coordinates stay in tile units, 0 to [Layer.extent] (lines may run a little past the edges).
 */
object Mvt {
    class Layer(val name: String, val extent: Int, val lines: List<Line>)

    /** One line feature: its tags, and its parts as x0, y0, x1, y1, … */
    class Line(val tags: Map<String, Any>, val parts: List<IntArray>)

    /** Decodes [tile] (gzipped or not) and returns [layerName], or null if the tile has no such layer. */
    fun lines(tile: ByteArray, layerName: String): Layer? {
        val data = gunzipIfNeeded(tile)
        val r = Reader(data, 0, data.size)
        while (r.more()) {
            val (field, wire) = r.key()
            if (field == 3 && wire == 2) {
                val layer = r.message()
                if (layerName(layer) == layerName) return layer(layer)
            } else {
                r.skip(wire)
            }
        }
        return null
    }

    fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }

    private fun layerName(r: Reader): String? {
        val start = r.pos
        var name: String? = null
        while (r.more()) {
            val (field, wire) = r.key()
            if (field == 1 && wire == 2) name = r.string() else r.skip(wire)
        }
        r.pos = start
        return name
    }

    private fun layer(r: Reader): Layer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val features = ArrayList<Reader>()
        while (r.more()) {
            val (field, wire) = r.key()
            when {
                field == 1 && wire == 2 -> name = r.string()
                field == 2 && wire == 2 -> features.add(r.message())
                field == 3 && wire == 2 -> keys.add(r.string())
                field == 4 && wire == 2 -> values.add(value(r.message()))
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        val lines = features.mapNotNull { feature(it, keys, values) }
        return Layer(name, extent, lines)
    }

    private const val LINESTRING = 2

    private fun feature(r: Reader, keys: List<String>, values: List<Any>): Line? {
        var type = 0
        val tags = ArrayList<Int>()
        val geometry = ArrayList<Int>()
        while (r.more()) {
            val (field, wire) = r.key()
            when {
                field == 2 -> r.uints(wire, tags)
                field == 3 && wire == 0 -> type = r.varint().toInt()
                field == 4 -> r.uints(wire, geometry)
                else -> r.skip(wire)
            }
        }
        if (type != LINESTRING) return null
        val tagMap = HashMap<String, Any>()
        for (i in 0 until tags.size - 1 step 2) {
            val k = keys.getOrNull(tags[i]) ?: continue
            val v = values.getOrNull(tags[i + 1]) ?: continue
            tagMap[k] = v
        }
        val parts = lineParts(geometry)
        return if (parts.isEmpty()) null else Line(tagMap, parts)
    }

    /**
     * Each MoveTo point starts a part and LineTo extends it; the cursor carries over between parts, as the spec
     * says. ClosePath (polygons only) is ignored, as in tools/roads.py. Parts of one point are dropped.
     */
    private fun lineParts(g: List<Int>): List<IntArray> {
        val parts = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        fun close() {
            if (current.size >= 4) parts.add(current.toIntArray())
            current = ArrayList()
        }
        while (i < g.size) {
            val cmd = g[i++]
            val id = cmd and 0x7
            val count = cmd ushr 3
            if (id == 7) continue
            if (id != 1 && id != 2) break // malformed
            repeat(count) {
                if (i + 1 >= g.size) return@repeat
                x += zigzag(g[i++])
                y += zigzag(g[i++])
                if (id == 1) close()
                current.add(x)
                current.add(y)
            }
        }
        close()
        return parts
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun value(r: Reader): Any {
        var v: Any = ""
        while (r.more()) {
            val (field, wire) = r.key()
            v = when {
                field == 1 && wire == 2 -> r.string()
                field == 2 && wire == 5 -> java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                field == 3 && wire == 1 -> java.lang.Double.longBitsToDouble(r.fixed64())
                field == 4 && wire == 0 -> r.varint()
                field == 5 && wire == 0 -> r.varint()
                field == 6 && wire == 0 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                field == 7 && wire == 0 -> r.varint() != 0L
                else -> {
                    r.skip(wire)
                    v
                }
            }
        }
        return v
    }

    /** Protobuf wire format over a slice of [buf]. */
    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end

        fun key(): Pair<Int, Int> {
            val k = varint()
            return (k ushr 3).toInt() to (k and 0x7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) throw IllegalArgumentException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }

        fun fixed32(): Int {
            need(4)
            var v = 0
            for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xff) shl (8 * i))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
            pos += 8
            return v
        }

        fun length(): Int {
            val n = varint()
            if (n < 0 || n > end - pos) throw IllegalArgumentException("bad length $n")
            return n.toInt()
        }

        fun message(): Reader {
            val n = length()
            val r = Reader(buf, pos, pos + n)
            pos += n
            return r
        }

        fun string(): String {
            val n = length()
            val s = String(buf, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        /** A repeated uint32 field, packed (wire type 2) or not (wire type 0). */
        fun uints(wire: Int, out: MutableList<Int>) {
            when (wire) {
                2 -> {
                    val r = message()
                    while (r.more()) out.add(r.varint().toInt())
                }
                0 -> out.add(varint().toInt())
                else -> skip(wire)
            }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { need(8); pos += 8 }
                2 -> {
                    val n = length() // before pos is read: `pos += length()` would add to the old pos
                    pos += n
                }
                5 -> { need(4); pos += 4 }
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }

        private fun need(n: Int) {
            if (end - pos < n) throw IllegalArgumentException("truncated field")
        }
    }
}
