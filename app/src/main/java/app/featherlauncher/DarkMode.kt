package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Executors

/** The icon look chosen in settings; Automatic follows the phone's dark mode. */
enum class IconLookSetting(val label: String) { AUTO("Automatic"), LIGHT("Light"), DARK("Dark"), TINTED("Tinted") }

/**
 * What changes when the phone goes dark: icons (if set to Automatic), a dimmer wallpaper, and
 * the wallpaper itself when a light and a dark one are paired.
 */
object DarkMode {
    private val io = Executors.newSingleThreadExecutor()

    fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

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
     * Called whenever the home screen comes back and when the phone switches modes.
     */
    fun applyPair(context: Context) {
        if (!hasPair(context)) return
        val night = isNight(context)
        val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val want = if (night) "dark" else "light"
        if (sp.getString("pair_applied", null) == want) return
        sp.edit().putString("pair_applied", want).apply()
        io.execute {
            // The saved picture is already the wallpaper as it should be: handed over as it is,
            // not read into memory and made into a picture file again.
            runCatching {
                pairFile(context, night).inputStream().use {
                    WallpaperManager.getInstance(context).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                }
            }
        }
    }

    // endregion
}
