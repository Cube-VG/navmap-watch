package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowEncoderTest {
    /** A transparent canvas with an opaque white box, like Google's maneuver icons. */
    private fun canvas(w: Int, h: Int, box: (Int, Int) -> Boolean) =
        IntArray(w * h) { if (box(it % w, it / w)) 0xFFFFFFFF.toInt() else 0x00000000 }

    @Test
    fun filledSquareFillsTheBox() {
        val bits = ArrowEncoder.encode(canvas(96, 96) { x, y -> x in 20..75 && y in 20..75 }, 96, 96)!!
        assertEquals(96, bits.size)
        // A 2-pixel margin on every side, everything inside set.
        assertFalse(ArrowEncoder.isSet(bits, 1, 24))
        assertTrue(ArrowEncoder.isSet(bits, 2, 24))
        assertTrue(ArrowEncoder.isSet(bits, 31, 24))
        assertTrue(ArrowEncoder.isSet(bits, 32, 24))
        assertTrue(ArrowEncoder.isSet(bits, 45, 24))
        assertFalse(ArrowEncoder.isSet(bits, 46, 24))
        assertFalse(ArrowEncoder.isSet(bits, 24, 1))
        assertTrue(ArrowEncoder.isSet(bits, 24, 45))
    }

    @Test
    fun bitLayoutMatchesTheWatch() {
        // A square filling the box sets x 2..45 on every inner row:
        // int 0 holds x 0..31 with x 0 on bit 31, int 1 holds x 32..47 with x 32 on bit 15.
        val bits = ArrowEncoder.encode(canvas(100, 100) { x, y -> x in 10..89 && y in 10..89 }, 100, 100)!!
        assertEquals(0x3FFFFFFF, bits[24 * 2])
        assertEquals(0xFFFC, bits[24 * 2 + 1])
        assertEquals(0, bits[0])
        assertEquals(0, bits[1])
    }

    @Test
    fun tallShapeKeepsItsProportions() {
        val bits = ArrowEncoder.encode(canvas(100, 100) { x, y -> x in 45..54 && y in 0..99 }, 100, 100)!!
        assertTrue(ArrowEncoder.isSet(bits, 24, 2))
        assertTrue(ArrowEncoder.isSet(bits, 24, 45))
        assertFalse(ArrowEncoder.isSet(bits, 10, 24))
        assertFalse(ArrowEncoder.isSet(bits, 38, 24))
    }

    @Test
    fun opaqueIconUsesContrast() {
        // Dark arrow on a solid blue background.
        val bits = ArrowEncoder.encode(
            IntArray(64 * 64) { if (it % 64 in 16..47 && it / 64 in 16..47) 0xFF000000.toInt() else 0xFF1A73E8.toInt() },
            64, 64,
        )!!
        assertTrue(ArrowEncoder.isSet(bits, 24, 24))
    }

    @Test
    fun emptyIcon() {
        assertNull(ArrowEncoder.encode(IntArray(48 * 48), 48, 48))
        assertNull(ArrowEncoder.encode(IntArray(0), 0, 0))
    }

    @Test
    fun hashIsStableAndNonZero() {
        val a = ArrowEncoder.encode(canvas(96, 96) { x, y -> x in 10..40 && y in 10..80 }, 96, 96)!!
        val b = ArrowEncoder.encode(canvas(96, 96) { x, y -> x in 50..80 && y > x - 40 }, 96, 96)!!
        assertEquals(ArrowEncoder.hash(a), ArrowEncoder.hash(a.copyOf()))
        assertNotEquals(ArrowEncoder.hash(a), ArrowEncoder.hash(b))
        assertNotEquals(0, ArrowEncoder.hash(IntArray(96)))
    }
}
