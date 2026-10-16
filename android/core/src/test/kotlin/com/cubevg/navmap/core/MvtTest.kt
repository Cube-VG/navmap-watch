package com.cubevg.navmap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class MvtTest {
    /** Just enough of a protobuf writer to build test tiles. */
    private class Pb {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long): Pb {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
            return this
        }

        fun uint(field: Int, v: Long) = key(field, 0).varint(v)

        fun bytes(field: Int, b: ByteArray): Pb {
            key(field, 2).varint(b.size.toLong())
            out.write(b)
            return this
        }

        fun string(field: Int, s: String) = bytes(field, s.toByteArray())

        fun message(field: Int, m: Pb) = bytes(field, m.toByteArray())

        fun packed(field: Int, values: List<Int>) = bytes(field, Pb().apply { values.forEach { varint(it.toLong() and 0xffffffffL) } }.toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    }

    private fun cmd(id: Int, count: Int) = id or (count shl 3)

    private fun zz(n: Int) = (n shl 1) xor (n shr 31)

    private fun feature(type: Int, tags: List<Int>, geometry: List<Int>) =
        Pb().uint(1, 9).packed(2, tags).uint(3, type.toLong()).packed(4, geometry)

    private fun tile(): ByteArray {
        val water = Pb().uint(15, 2).string(1, "water").message(2, feature(3, emptyList(), listOf(cmd(1, 1), 0, 0)))
        val roads = Pb().uint(15, 2).string(1, "transportation")
            // Two parts: (10,20) (15,20) (15,25), then (115,125) (114,126); the cursor carries over.
            .message(2, feature(2, listOf(0, 0, 1, 2), listOf(cmd(1, 1), zz(10), zz(20), cmd(2, 2), zz(5), 0, 0, zz(5), cmd(1, 1), zz(100), zz(100), cmd(2, 1), zz(-1), zz(1))))
            .message(2, feature(1, listOf(0, 1), listOf(cmd(1, 1), 2, 2))) // a point: skipped
            .message(2, feature(2, listOf(0, 1), listOf(cmd(1, 1), zz(4096), zz(4100), cmd(2, 1), zz(-8), 0)))
            .message(2, feature(2, listOf(0, 0), listOf(cmd(1, 1), 0, 0))) // a single point: no line
            .string(3, "class").string(3, "brunnel")
            .message(4, Pb().string(1, "primary"))
            .message(4, Pb().string(1, "service"))
            .message(4, Pb().string(1, "bridge"))
            .uint(5, 4096)
        return Pb().message(3, water).message(3, roads).toByteArray()
    }

    @Test
    fun readsLinesOfOneLayer() {
        val layer = Mvt.lines(tile(), "transportation")!!
        assertEquals(4096, layer.extent)
        assertEquals(2, layer.lines.size)
        val first = layer.lines[0]
        assertEquals(mapOf("class" to "primary", "brunnel" to "bridge"), first.tags)
        assertEquals(2, first.parts.size)
        assertArrayEquals(intArrayOf(10, 20, 15, 20, 15, 25), first.parts[0])
        assertArrayEquals(intArrayOf(115, 125, 114, 126), first.parts[1])
        assertEquals("service", layer.lines[1].tags["class"])
        assertArrayEquals(intArrayOf(4096, 4100, 4088, 4100), layer.lines[1].parts[0])
        assertNull(Mvt.lines(tile(), "poi"))
    }

    @Test
    fun readsGzippedTiles() {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tile()) } }.toByteArray()
        assertArrayEquals(Mvt.lines(tile(), "transportation")!!.lines[0].parts[1], Mvt.lines(zipped, "transportation")!!.lines[0].parts[1])
    }

    @Test
    fun tileLinesKeepNavMapClassesInWorldPixels() {
        val lines = RoadArea.tileLines(3, 5, Mvt.lines(tile(), "transportation")!!)
        assertEquals(2, lines.size) // both parts of the primary road; the service road isn't drawn
        assertEquals(4, lines[0].cls)
        assertEquals(3, lines[0].tx)
        assertEquals(5, lines[0].ty)
        assertArrayEquals(doubleArrayOf(3 * 4096.0 + 10, 5 * 4096.0 + 20, 3 * 4096.0 + 15, 5 * 4096.0 + 20, 3 * 4096.0 + 15, 5 * 4096.0 + 25), lines[0].points, 0.0)
    }
}
