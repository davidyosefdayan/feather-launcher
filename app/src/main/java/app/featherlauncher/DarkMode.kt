package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/** The icon look chosen in settings; Automatic follows the phone's dark mode. */
enum class IconLookSetting(val label: String) { AUTO("Automatic"), LIGHT("Light"), DARK("Dark"), TINTED("Tinted") }

/**
 * What changes when the phone goes dark (or the sun sets, when the theme follows it): icons (if
 * set to Automatic), a dimmer wallpaper, and the wallpaper itself when a light and a dark one are paired.
 */
object DarkMode {
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Whether it's night for the icons and wallpaper: after sunset when the theme follows the
     * sun, otherwise when the phone is in dark mode.
     */
    fun isNight(context: Context) =
        if (followsSun(context)) sunDown(context) else phoneIsDark(context)

    fun phoneIsDark(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Whether Feather's own screens are dark in [mode]. */
    fun isDark(context: Context, mode: ThemeMode) = when (mode) {
        ThemeMode.SYSTEM -> phoneIsDark(context)
        ThemeMode.SUN -> sunDown(context)
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Read straight from storage: this is asked often, from places without a Prefs at hand.
    private fun followsSun(context: Context) =
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("theme_mode", null) == ThemeMode.SUN.name

    // region Sunrise and sunset

    private const val DAY = 24 * 60 * 60_000L

    /** Whether the sun is down where the phone is (or the chosen city) at [now]. */
    fun sunDown(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (rise, set) = sunTimes(context, now)
        return now < rise || now >= set
    }

    /** The next sunrise or sunset after [now]. */
    fun nextSunChange(context: Context, now: Long = System.currentTimeMillis()): Long {
        val (rise, set) = sunTimes(context, now)
        val next = when {
            now < rise -> rise
            now < set -> set
            else -> sunTimes(context, now + DAY).first
        }
        return next.coerceAtLeast(now + 60_000)
    }

    /**
     * Sunrise and sunset on the day containing [day]. Without a known place, or where the sun
     * doesn't rise or set that day, 7 am and 7 pm stand in.
     */
    private fun sunTimes(context: Context, day: Long): Pair<Long, Long> {
        val place = ClockInfo(context).place()
        val sunrise = place?.let { Zmanim.sun(day, it, Zmanim.SUNRISE, true) }
        val sunset = place?.let { Zmanim.sun(day, it, Zmanim.SUNRISE, false) }
        if (sunrise != null && sunset != null) return sunrise to sunset
        fun at(hour: Int) = Calendar.getInstance().apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return at(7) to at(19)
    }

    // endregion

    /** The icon look to draw right now. */
    fun iconLook(context: Context, prefs: Prefs): IconLook = when (prefs.iconLook) {
        IconLookSetting.AUTO -> if (isNight(context)) IconLook.DARK else IconLook.NORMAL
        IconLookSetting.LIGHT -> IconLook.NORMAL
        IconLookSetting.DARK -> IconLook.DARK
        IconLookSetting.TINTED -> IconLook.TINTED
    }

    /** Tinted icons are drawn in a light version of the accent color. */
    fun tint(context: Context, prefs: Prefs): Int = Palette.from(context, prefs.colorStyle, ThemeMode.DARK, prefs.accentColor).accent

    /** How much to darken the home screen's wallpaper (0 to 1). */
    fun wallpaperDim(context: Context, prefs: Prefs): Float = if (prefs.dimWallpaper && isNight(context)) 0.3f else 0f

    // region Light and dark wallpaper pair

    fun pairFile(context: Context, dark: Boolean) = File(context.filesDir, if (dark) "wallpaper_dark.png" else "wallpaper_light.png")

    fun hasPair(context: Context) = pairFile(context, false).exists() && pairFile(context, true).exists()

    /** Keeps [bitmap] as the light or dark wallpaper; sets it now if the phone is in that mode. */
    fun saveToPair(context: Context, bitmap: Bitmap, dark: Boolean, done: () -> Unit) = io.execute {
        pairFile(context, dark).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove("pair_applied").apply()
        applyPair(context)
        android.os.Handler(android.os.Looper.getMainLooper()).post(done)
    }

    fun clearPair(context: Context) {
        pairFile(context, false).delete()
        pairFile(context, true).delete()
    }

    /**
     * Sets the wallpaper that goes with the current mode, if both are chosen and it isn't set yet.
     * Called whenever the home screen comes back, when the phone switches modes, and at sunrise and sunset.
     */
    fun applyPair(context: Context) {
        if (!hasPair(context)) return
        val night = isNight(context)
        val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val want = if (night) "dark" else "light"
        if (sp.getString("pair_applied", null) == want) return
        sp.edit().putString("pair_applied", want).apply()
        io.execute {
            val bitmap = BitmapFactory.decodeFile(pairFile(context, night).path) ?: return@execute
            runCatching {
                WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            }
        }
    }

    // endregion
}
