package com.cubevg.navmap.core

/**
 * Turns Google Maps' maneuver icon into the 48x48 one-bit image NavMap draws in its turn banner.
 *
 * Output is 96 ints, two per row: int 0 holds x = 0..31 (bit 31 is x 0), int 1 holds x = 32..47 (bit 15 is x 32).
 * The shape is cropped to its visible pixels and scaled to fill the square, keeping its proportions.
 */
object ArrowEncoder {
    const val SIZE = 48
    private const val MARGIN = 2
    private const val SAMPLES = 4

    /** [argb] is row-major ARGB pixels. Returns null when the icon has nothing visible. */
    fun encode(argb: IntArray, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null
        val ink = ink(argb, width, height)

        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (ink[y * width + x] >= 0.2f) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val box = (SIZE - 2 * MARGIN).toFloat()
        val scale = minOf(box / bw, box / bh)
        val ox = (SIZE - bw * scale) / 2f
        val oy = (SIZE - bh * scale) / 2f

        val out = IntArray(SIZE * 2)
        for (ty in 0 until SIZE) {
            var hi = 0
            var lo = 0
            for (tx in 0 until SIZE) {
                // Average several samples per output pixel so thin strokes survive the downscale.
                var sum = 0f
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    val px = (tx + (sx + 0.5f) / SAMPLES - ox) / scale
                    val py = (ty + (sy + 0.5f) / SAMPLES - oy) / scale
                    if (px < 0f || py < 0f || px >= bw || py >= bh) continue
                    sum += ink[(y0 + py.toInt()) * width + x0 + px.toInt()]
                }
                if (sum / (SAMPLES * SAMPLES) >= 0.5f) {
                    if (tx < 32) hi = hi or (1 shl (31 - tx)) else lo = lo or (1 shl (47 - tx))
                }
            }
            out[ty * 2] = hi
            out[ty * 2 + 1] = lo
        }
        return out
    }

    /** A stable, non-zero id for an encoded arrow. NavMap uses 0 for "no arrow". */
    fun hash(bits: IntArray): Int = bits.contentHashCode().let { if (it == 0) 1 else it }

    fun isSet(bits: IntArray, x: Int, y: Int): Boolean =
        if (x < 32) (bits[y * 2] ushr (31 - x)) and 1 == 1 else (bits[y * 2 + 1] ushr (47 - x)) and 1 == 1

    // Ink per pixel in 0..1: alpha for icons drawn on transparency, contrast with the corner colour otherwise.
    private fun ink(argb: IntArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val opaque = (0 until n).count { (argb[it] ushr 24) >= 128 }
        return if (opaque < n * 0.9) {
            FloatArray(n) { (argb[it] ushr 24) / 255f }
        } else {
            val bg = argb[0]
            FloatArray(n) { if (contrast(argb[it], bg) > 96) 1f else 0f }
        }
    }

    private fun contrast(a: Int, b: Int): Int =
        maxOf(
            Math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
            Math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
            Math.abs((a and 0xff) - (b and 0xff)),
        )
}
