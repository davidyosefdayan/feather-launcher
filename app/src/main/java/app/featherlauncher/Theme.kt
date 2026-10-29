package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import kotlin.math.abs

/** The color choices offered in settings. [WALLPAPER] follows the current wallpaper. */
enum class ColorStyle(val label: String, val hue: Float, val saturation: Float) {
    WALLPAPER("Wallpaper", 0f, 0f),
    MIDNIGHT("Midnight", 220f, 0.06f),
    OCEAN("Ocean", 212f, 0.55f),
    FOREST("Forest", 150f, 0.40f),
    PLUM("Plum", 280f, 0.40f),
    EMBER("Ember", 18f, 0.60f),
    ROSE("Rose", 340f, 0.45f),
}

/** [SUN] is light from sunrise to sunset where the phone is, and dark the rest of the time. */
enum class ThemeMode(val label: String) { SYSTEM("Match phone"), SUN("Sunrise to sunset"), LIGHT("Light"), DARK("Dark") }

/**
 * Every color the UI uses, from one hue (the wallpaper's, or a preset). Surfaces
 * are solid and lightly tinted with that hue, in light or dark mode.
 * Text drawn straight on the wallpaper (clock, labels) stays white either way.
 */
class Palette(
    hue: Float,
    saturation: Float,
    val dark: Boolean,
    val wallpaperIsLight: Boolean = false,
    /** The wallpaper's own color, for things drawn on the wallpaper; the accent preset doesn't change it. */
    wallHue: Float = hue,
    wallSaturation: Float = saturation,
    /** How light the wallpaper is overall. */
    wallLightness: Float = 0.3f,
) {
    /**
     * Surfaces on the wallpaper (app list, search, dock, tab bar, folders) follow Smart Launcher,
     * measured over many wallpapers: the wallpaper's hue at a fixed, gentle saturation and a
     * fixed lightness, nearly solid. Dark for most wallpapers; light when the wallpaper is light.
     */
    private val lightWall = wallpaperIsLight
    /**
     * A bright, colorful wallpaper that isn't light enough for dark text. The usual near-black
     * surfaces look like a hole in it, so they take a richer, lighter tone of its color instead.
     */
    private val vividWall = !lightWall && wallLightness > 0.42f && wallSaturation > 0.3f
    private val wallSat = if (vividWall) (wallSaturation * 0.95f).coerceAtMost(0.62f) else (wallSaturation * 1.5f).coerceAtMost(0.28f)
    /** How light the deep surfaces (app list, search, folders) are when the wallpaper isn't light. */
    private val deep = if (vividWall) 0.29f else 0.13f
    private val glassSat = if (vividWall) wallSat * 0.85f else wallSat * 0.48f

    val accent = if (dark) hsl(hue, saturation.coerceIn(0.35f, 0.75f), 0.74f) else hsl(hue, saturation.coerceIn(0.40f, 0.80f), 0.42f)
    val onAccent = if (dark) hsl(hue, 0.45f, 0.14f) else Color.WHITE
    /** A soft fill in the accent hue, for selected items. */
    val accentSurface = if (dark) hsl(hue, minOf(saturation, 0.35f), 0.26f) else hsl(hue, minOf(saturation, 0.60f), 0.90f)

    /** Popups, sheets and the app drawer. */
    // Pop-ups sit over apps of every color, so they stay nearly neutral: just a hint of the hue.
    private val tint = saturation.coerceIn(0.03f, 0.08f)
    val surface = if (dark) hsl(hue, tint, 0.15f) else hsl(hue, tint + 0.05f, 0.96f)
    /** A page header with content scrolled under it: a step deeper than the page, as Android's own top bars do. */
    val surfaceScrolled = if (dark) hsl(hue, tint, 0.185f) else hsl(hue, tint + 0.03f, 0.93f)
    /** Buttons, rows and fields inside a surface. */
    val surfaceHigh = if (dark) hsl(hue, tint * 0.9f, 0.22f) else hsl(hue, tint, 0.905f)
    /** The dock, tab bar and search bar: a lighter, softer tone than the app list, nearly solid. */
    val glass = if (lightWall) hsl(wallHue, wallSat * 0.6f, 0.78f, 0xF5) else hsl(wallHue, glassSat, deep + 0.08f, 0xF5)
    /** The faint line around the dock and tab bar. */
    val hairline = if (lightWall) 0x14000000 else 0x14FFFFFF
    /** The folder when it's open. */
    val folderPanel = glass
    /** The app list's background: the wallpaper's hue, deep and nearly solid, so little shows through. */
    val drawer = if (lightWall) hsl(wallHue, wallSat * 1.25f, 0.86f, 0xFA) else hsl(wallHue, wallSat, deep, if (vividWall) 0xEB else 0xFA)
    /**
     * The dock on the home screen. On darker wallpapers, the app list's deep tone; on bright
     * ones (light or vivid) that's a heavy slab, so it becomes a soft frosted white instead.
     */
    val dock = when {
        lightWall -> hsl(wallHue, wallSat * 0.6f, 0.95f, 0x8C)
        vividWall -> 0x4DFFFFFF
        else -> drawer
    }
    /** Folder tiles on the home screen match the dock, so the two read as one family. */
    val folderFill = dock
    /** Behind Smart Search: the same as the app list. */
    val searchScrim = drawer
    /** Text on wallpaper-colored surfaces: near white (or near black) with a touch of the hue. */
    val onWall = if (lightWall) hsl(wallHue, 0.20f, 0.12f) else hsl(wallHue, 0.15f, 0.88f)
    val onWallSecondary = (onWall and 0xFFFFFF) or 0xA6000000.toInt()
    /** Background of the app drawer. */
    val scrim = surface
    val stroke = 0

    val text = if (dark) Color.WHITE else 0xFF1B1C1F.toInt()
    val textSecondary = if (dark) 0xB8FFFFFF.toInt() else 0xB31B1C1F.toInt()
    val textTertiary = if (dark) 0x80FFFFFF.toInt() else 0x801B1C1F.toInt()
    val danger = if (dark) 0xFFFF8A80.toInt() else 0xFFC62828.toInt()
    val dangerSurface = if (dark) hsl(4f, 0.40f, 0.22f) else hsl(4f, 0.80f, 0.94f)

    companion object {
        /** The wallpaper's main colors, for offering as accent choices. */
        fun wallpaperColors(context: Context): List<Int> {
            val c = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return emptyList()
            return listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).map { it.toArgb() }
                .filter { rgbToHsl(it)[1] > 0.08f }
        }

        fun from(context: Context, style: ColorStyle, mode: ThemeMode, accentColor: Int = 0): Palette {
            val dark = DarkMode.isDark(context, mode)
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            val seed = colors?.let(::averageColor)?.let(::rgbToHsl)
                ?: floatArrayOf(ColorStyle.OCEAN.hue, ColorStyle.OCEAN.saturation, 0.3f)
            // Android's hint isn't always set (a pale pastel can come without it), so the
            // wallpaper's own average brightness counts too.
            val wallpaperIsLight = colors != null &&
                (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 || seed[2] > 0.7f)
            android.util.Log.d("Palette", "wallpaper average -> hue ${seed[0]} sat ${seed[1]} light ${seed[2]}")
            val custom = if (accentColor != 0) rgbToHsl(accentColor) else null
            val hue = custom?.get(0) ?: if (style == ColorStyle.WALLPAPER) seed[0] else style.hue
            val saturation = custom?.get(1)?.coerceAtLeast(0.35f) ?: if (style == ColorStyle.WALLPAPER) seed[1] else style.saturation
            return Palette(hue, saturation, dark, wallpaperIsLight, seed[0], seed[1], seed[2])
        }

        /**
         * The wallpaper's average color. Android works out every color in the wallpaper with how
         * much of it there is, but only shares that list privately; if it can't be read, the
         * three main colors are averaged instead.
         */
        private fun averageColor(colors: android.app.WallpaperColors): Int {
            val all = runCatching {
                @Suppress("UNCHECKED_CAST")
                colors.javaClass.getMethod("getAllColors").invoke(colors) as Map<Int, Int>
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            val weighted = all?.entries?.map { it.key to it.value.toFloat() }
                ?: listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
                    .mapIndexed { i, c -> c.toArgb() to (3 - i).toFloat() }
            var r = 0f; var g = 0f; var b = 0f; var total = 0f
            weighted.forEach { (c, w) ->
                r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; total += w
            }
            android.util.Log.d("Palette", "average from ${if (all != null) "${all.size} colors" else "main colors"}")
            return Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
        }

        fun hsl(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
            val c = (1 - abs(2 * l - 1)) * s
            val x = c * (1 - abs((h / 60f) % 2 - 1))
            val m = l - c / 2
            val (r, g, b) = when {
                h < 60 -> Triple(c, x, 0f)
                h < 120 -> Triple(x, c, 0f)
                h < 180 -> Triple(0f, c, x)
                h < 240 -> Triple(0f, x, c)
                h < 300 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return Color.argb(alpha, ((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
        }

        fun rgbToHsl(color: Int): FloatArray {
            val r = Color.red(color) / 255f
            val g = Color.green(color) / 255f
            val b = Color.blue(color) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            if (max == min) return floatArrayOf(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            val h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
                g -> ((b - r) / d + 2) * 60
                else -> ((r - g) / d + 4) * 60
            }
            return floatArrayOf(h, s, l)
        }
    }
}
