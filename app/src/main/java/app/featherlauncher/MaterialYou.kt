package app.featherlauncher

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Android 12's Material You colors (system_accent1_600 and the rest), worked out the same way
 * for Android 11, which doesn't have them. Each family keeps the wallpaper's main hue at its own
 * colorfulness, and a shade sets the lightness: shade 0 is white, 500 is the middle, 1000 black.
 */
// The color ids are only used as names here; Android 11 never looks them up.
@SuppressLint("InlinedApi")
object MaterialYou {
    private enum class Family(val chroma: (seed: Double) -> Double) {
        ACCENT1({ maxOf(it, 48.0) }), ACCENT2({ 16.0 }), NEUTRAL1({ 4.0 }), NEUTRAL2({ 8.0 })
    }

    /** The colors Feather uses, by family and shade. */
    private val colors = mapOf(
        android.R.color.system_accent1_0 to (Family.ACCENT1 to 0),
        android.R.color.system_accent1_100 to (Family.ACCENT1 to 100),
        android.R.color.system_accent1_200 to (Family.ACCENT1 to 200),
        android.R.color.system_accent1_600 to (Family.ACCENT1 to 600),
        android.R.color.system_accent1_700 to (Family.ACCENT1 to 700),
        android.R.color.system_accent1_800 to (Family.ACCENT1 to 800),
        android.R.color.system_accent1_900 to (Family.ACCENT1 to 900),
        android.R.color.system_accent2_50 to (Family.ACCENT2 to 50),
        android.R.color.system_accent2_800 to (Family.ACCENT2 to 800),
        android.R.color.system_neutral1_50 to (Family.NEUTRAL1 to 50),
        android.R.color.system_neutral1_900 to (Family.NEUTRAL1 to 900),
        android.R.color.system_neutral2_200 to (Family.NEUTRAL2 to 200),
        android.R.color.system_neutral2_700 to (Family.NEUTRAL2 to 700),
    )

    /** Android's own starting color when the wallpaper doesn't give one. */
    private const val DEFAULT_SEED = 0xFF1B6EF3.toInt()

    private var seedFor: Any? = null
    private var seed = DEFAULT_SEED
    private val made = HashMap<Int, Int>()

    /** The color for [id], from the wallpaper; [key] changes when the wallpaper (or theme) does. */
    fun color(context: Context, id: Int, key: Any?): Int? {
        val (family, shade) = colors[id] ?: return null
        if (key !== seedFor) {
            seedFor = key
            seed = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() ?: DEFAULT_SEED
            made.clear()
        }
        return made.getOrPut(id) {
            val (_, c, h) = lch(seed)
            fromLch(100.0 - shade / 10.0, family.chroma(c), h)
        }
    }

    // region Color math (CIE LCh, which tracks lightness the way eyes see it)

    private fun lch(color: Int): Triple<Double, Double, Double> {
        fun linear(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = linear(color shr 16 and 0xFF)
        val g = linear(color shr 8 and 0xFF)
        val b = linear(color and 0xFF)
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
        val fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
        val fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        val a = 500 * (fx - fy)
        val bb = 200 * (fy - fz)
        return Triple(116 * fy - 16, hypot(a, bb), atan2(bb, a))
    }

    /** The color at [lightness] and [hue], as colorful as asked or as the screen can show. */
    private fun fromLch(lightness: Double, chroma: Double, hue: Double): Int {
        var low = 0.0
        var high = chroma
        var best = toRgb(lightness, 0.0, hue)!!
        // Bright and deep shades can't be very colorful: back off until the color fits.
        repeat(16) {
            val mid = (low + high) / 2
            val rgb = toRgb(lightness, mid, hue)
            if (rgb != null) { best = rgb; low = mid } else high = mid
        }
        return toRgb(lightness, chroma, hue) ?: best
    }

    private fun toRgb(lightness: Double, chroma: Double, hue: Double): Int? {
        val fy = (lightness + 16) / 116
        val fx = fy + chroma * cos(hue) / 500
        val fz = fy - chroma * sin(hue) / 200
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
        val x = inv(fx) * 0.95047
        val y = inv(fy)
        val z = inv(fz) * 1.08883
        val linear = doubleArrayOf(
            3.2406 * x - 1.5372 * y - 0.4986 * z,
            -0.9689 * x + 1.8758 * y + 0.0415 * z,
            0.0557 * x - 0.2040 * y + 1.0570 * z,
        )
        if (linear.any { it < -0.0001 || it > 1.0001 }) return null
        val (r, g, b) = linear.map { v ->
            val c = v.coerceIn(0.0, 1.0)
            ((if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055) * 255).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // endregion
}
