package app.featherlauncher

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings

/**
 * Puts the whole phone in light or dark mode along with Feather's theme, and keeps time for the
 * theme that follows the sun.
 *
 * Android only lets its own apps switch dark mode. An app allowed to change secure settings
 * (granted once from a computer, see [GRANT]) can still store the choice where Android keeps
 * it, but Android only reads it back when car mode ends, so car mode goes on and straight off.
 */
object PhoneTheme {
    const val GRANT = "adb shell pm grant app.featherlauncher android.permission.WRITE_SECURE_SETTINGS"

    private const val HOUR = 60 * 60_000L

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Feather sets the phone's mode: turned on, allowed, and the theme isn't just following the phone. */
    fun active(context: Context, prefs: Prefs) =
        prefs.themeWholePhone && prefs.themeMode != ThemeMode.SYSTEM && allowed(context)

    /**
     * Brings the phone in line with the theme and sets the next sunrise or sunset alarm. Each
     * choice, and each stretch of day or night, is applied once, so switching dark mode by hand
     * afterwards holds until the next sunrise or sunset; [force] applies it again anyway.
     */
    fun sync(context: Context, prefs: Prefs = Prefs(context), force: Boolean = false) {
        val sun = prefs.themeMode == ThemeMode.SUN
        val next = if (sun) DarkMode.nextSunChange(context) else null
        schedule(context, next)
        if (!active(context, prefs)) return
        val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val key = "${prefs.themeMode.name}:${next?.div(HOUR) ?: ""}"
        if (!force && sp.getString("phone_theme_applied", null) == key) return
        sp.edit().putString("phone_theme_applied", key).apply()
        setPhoneDark(context, DarkMode.isDark(context, prefs.themeMode))
    }

    private fun setPhoneDark(context: Context, dark: Boolean) {
        if (DarkMode.phoneIsDark(context) == dark) return
        val mode = if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        if (!runCatching { Settings.Secure.putInt(context.contentResolver, "ui_night_mode", mode) }.getOrDefault(false)) return
        val ui = context.getSystemService(UiModeManager::class.java)
        // Really in a car: the new mode is picked up when car mode ends on its own.
        if (ui.currentModeType == Configuration.UI_MODE_TYPE_CAR) return
        runCatching {
            ui.enableCarMode(0)
            ui.disableCarMode(0)
        }
    }

    /** Wakes [Receiver] at [at] (give or take a few minutes), or never when null. */
    private fun schedule(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, Receiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) alarms.cancel(intent) else alarms.setWindow(AlarmManager.RTC, at, 10 * 60_000L, intent)
    }

    /** Sunrise or sunset: the phone's mode and the wallpaper pair switch, even away from the home screen. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sync(context)
            DarkMode.applyPair(context)
        }
    }
}
