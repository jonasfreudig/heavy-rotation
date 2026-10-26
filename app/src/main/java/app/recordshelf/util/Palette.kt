package app.recordshelf.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The colors of a picture (a cover, an artist photo), to tint its page: the most common color,
 * and the most vivid one that still covers a fair part of it. Pixels are ARGB ints, like
 * Bitmap.getPixels gives them; a small, scaled-down picture is enough.
 */
object Palette {
    class Colors(val dominant: Int, val vibrant: Int)

    private class Bucket {
        var n = 0
        var r = 0L
        var g = 0L
        var b = 0L
        fun rgb() = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    fun of(pixels: IntArray): Colors? {
        val buckets = HashMap<Int, Bucket>()
        var total = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue // transparent
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            // 4 bits per channel: similar colors fall together.
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val k = buckets.getOrPut(key) { Bucket() }
            k.n++
            k.r += r
            k.g += g
            k.b += b
            total++
        }
        if (total == 0) return null
        val dominant = buckets.values.maxByOrNull { it.n }!!.rgb()
        // Vivid: saturated, neither near black nor near white, and not a speck.
        val vibrant = buckets.values.filter { it.n * 50 >= total }.maxByOrNull { k ->
            val (s, l) = satLight(k.rgb())
            s * (1 - abs(l - 0.55) * 1.6).coerceAtLeast(0.0) * sqrt(k.n.toDouble() / total)
        }?.rgb()?.takeIf { satLight(it).first >= 0.25 } ?: dominant
        return Colors(dominant, vibrant)
    }

    /** HSL saturation and lightness (0..1) of an RGB int. */
    fun satLight(rgb: Int): Pair<Double, Double> {
        val r = ((rgb shr 16) and 255) / 255.0
        val g = ((rgb shr 8) and 255) / 255.0
        val b = (rgb and 255) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2
        val s = if (hi == lo) 0.0 else (hi - lo) / (1 - abs(2 * l - 1))
        return s to l
    }

    fun hex(rgb: Int) = "#%06x".format(rgb and 0xffffff)
}
