package com.cubevg.navmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CreditStripTest {
    @Test
    fun transparentImageReturnsNull() {
        val pixels = IntArray(100 * 50) { 0 }
        assertNull(CreditStrip.measure(pixels, 100, 50))
    }

    @Test
    fun measuresBoxDimensionsAndWordGap() {
        val w = 280
        val h = 60
        val pixels = IntArray(w * h) { 0 } // all transparent

        val boxX = 100
        val boxY = 30
        val boxW = 168
        val boxH = 30

        val whiteBg = 0xCCFFFFFF.toInt()
        val darkText = 0xFF202020.toInt()

        // Fill background box
        for (y in boxY until (boxY + boxH)) {
            for (x in boxX until (boxX + boxW)) {
                pixels[y * w + x] = whiteBg
            }
        }

        // Draw word 1: columns 10..70
        for (y in (boxY + 5) until (boxY + 25)) {
            for (x in (boxX + 10)..(boxX + 70)) {
                pixels[y * w + x] = darkText
            }
        }
        // Word gap: columns 71..89 (no dark pixels)
        // Draw word 2: columns 90..150
        for (y in (boxY + 5) until (boxY + 25)) {
            for (x in (boxX + 90)..(boxX + 150)) {
                pixels[y * w + x] = darkText
            }
        }

        val m = CreditStrip.measure(pixels, w, h)
        assertNotNull(m)
        assertEquals(boxW, m!!.width)
        assertEquals(boxH, m.height)
        // Word gap is between 71 and 89 (middle of gap is ~80, box half is 84)
        assertEquals(true, m.split in 71..89)
    }
}
