package com.cubevg.navmap.core

/**
 * Measures the Google Maps attribution credit box from a probe Static Maps picture.
 * The probe picture has all map features hidden (`style=visibility:off`), so the pixels
 * are transparent except for Google's logo (bottom-left) and the attribution box (bottom-right).
 */
object CreditStrip {
    data class Measurement(val width: Int, val height: Int, val split: Int)

    /**
     * Finds the width, height, and middle-most word gap split position of the credit box
     * in the bottom-right quadrant of [pixels] (width [w], height [h]).
     */
    fun measure(pixels: IntArray, w: Int, h: Int): Measurement? {
        if (pixels.size < w * h || w <= 0 || h <= 0) return null

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1

        // Bottom-right quadrant scan
        val startX = w / 3
        val startY = h / 3

        for (y in startY until h) {
            val row = y * w
            for (x in startX until w) {
                val p = pixels[row + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha > 64) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) return null

        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        val halfW = boxWidth / 2

        // Count dark (text) pixels per column to locate word gaps
        val textPerCol = IntArray(boxWidth)
        for (y in minY..maxY) {
            val row = y * w
            for (x in minX..maxX) {
                val p = pixels[row + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha > 64) {
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    // Dark text on translucent white background
                    if (r < 220 || g < 220 || b < 220) {
                        textPerCol[x - minX]++
                    }
                }
            }
        }

        // Find the column with 0 text pixels closest to halfW (ignoring padding near edges)
        var bestGap = -1
        var bestDist = Int.MAX_VALUE
        val margin = minOf(10, boxWidth / 6)
        for (col in margin until (boxWidth - margin)) {
            if (textPerCol[col] == 0) {
                val dist = kotlin.math.abs(col - halfW)
                if (dist < bestDist) {
                    bestDist = dist
                    bestGap = col
                }
            }
        }

        val split = if (bestGap != -1) bestGap else halfW
        return Measurement(width = boxWidth, height = boxHeight, split = split)
    }
}
