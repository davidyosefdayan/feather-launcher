package app.featherlauncher

import android.content.Context

class WidgetSpec(val id: Int, val heightDp: Int)

/** Everything the user sets up, kept in SharedPreferences with in-memory copies for fast reads. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val launches = context.getSharedPreferences("launches", Context.MODE_PRIVATE)

    val hasDock: Boolean get() = sp.contains("dock")

    var colorStyle: ColorStyle
        get() = runCatching { ColorStyle.valueOf(sp.getString("color_style", null)!!) }.getOrDefault(ColorStyle.WALLPAPER)
        set(value) = sp.edit().putString("color_style", value.name).apply()

    /** A chosen accent color; 0 means follow the wallpaper. */
    var accentColor: Int
        get() = sp.getInt("accent_color", 0)
        set(value) = sp.edit().putInt("accent_color", value).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString("theme_mode", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = sp.edit().putString("theme_mode", value.name).apply()

    var iconShape: IconShape
        get() = runCatching { IconShape.valueOf(sp.getString("icon_shape", null)!!) }.getOrDefault(IconShape.SQUIRCLE)
        set(value) = sp.edit().putString("icon_shape", value.name).apply()

    var dock: List<String?> = readDock()
        private set

    var hidden: Set<String> = emptySet()
        private set

    var widgets: List<WidgetSpec> = readWidgets()
        private set

    init {
        reload()
    }

    /** Re-reads what another screen (settings) may have changed. */
    fun reload() {
        hidden = sp.getStringSet("hidden", emptySet())!!.toSet()
    }


    /** App list as a grid of icons (true) or a list of names. */
    var drawerGrid: Boolean
        get() = sp.getBoolean("drawer_grid", true)
        set(value) = sp.edit().putBoolean("drawer_grid", value).apply()

    /** Swiping down on the home screen opens Smart Search (true) or the notifications. */
    var swipeDownSearch: Boolean
        get() = sp.getBoolean("swipe_down_search", true)
        set(value) = sp.edit().putBoolean("swipe_down_search", value).apply()

    var drawerCategories: Boolean
        get() = sp.getBoolean("drawer_categories", true)
        set(value) = sp.edit().putBoolean("drawer_categories", value).apply()

    /** Hash of the PIN that guards hidden apps; null means no PIN. */
    var hiddenPin: String?
        get() = sp.getString("hidden_pin", null)
        set(value) = sp.edit().putString("hidden_pin", value).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("show_labels", true)
        set(value) = sp.edit().putBoolean("show_labels", value).apply()

    /** A red badge on apps with notifications (needs notification access). */
    var notificationBadges: Boolean
        get() = sp.getBoolean("notification_badges", true)
        set(value) = sp.edit().putBoolean("notification_badges", value).apply()

    /** How many notifications to show on [pkg]'s icon: 0 for none, or when badges are off. */
    fun badgeCount(pkg: String) = if (notificationBadges) NotificationBadges.count(pkg) else 0

    private fun readDock(): List<String?> {
        val saved = sp.getString("dock", null)?.split('|')?.map { it.ifEmpty { null } } ?: emptyList()
        return List(DOCK_SIZE) { saved.getOrNull(it) }
    }

    fun setDock(slot: Int, key: String?) {
        dock = dock.toMutableList().also { it[slot] = key }
        sp.edit().putString("dock", dock.joinToString("|") { it.orEmpty() }).apply()
    }

    fun setHidden(key: String, hide: Boolean) {
        hidden = if (hide) hidden + key else hidden - key
        sp.edit().putStringSet("hidden", hidden).apply()
    }

    private fun readWidgets(): List<WidgetSpec> =
        sp.getString("widgets", "")!!.split(',').mapNotNull { part ->
            val (id, height) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            WidgetSpec(id.toIntOrNull() ?: return@mapNotNull null, height.toIntOrNull() ?: return@mapNotNull null)
        }

    fun setWidgets(specs: List<WidgetSpec>) {
        widgets = specs
        sp.edit().putString("widgets", specs.joinToString(",") { "${it.id}:${it.heightDp}" }).apply()
    }

    fun clockOptions(id: Long) = ClockOptions(
        runCatching { ClockOptions.Size.valueOf(sp.getString("clock_size_$id", null)!!) }.getOrDefault(ClockOptions.Size.LARGE),
        sp.getBoolean("clock_start_$id", false),
        sp.getBoolean("clock_date_$id", true),
        sp.getBoolean("clock_time_$id", true),
    )

    fun setClockOptions(id: Long, options: ClockOptions) {
        sp.edit()
            .putString("clock_size_$id", options.size.name)
            .putBoolean("clock_start_$id", options.alignStart)
            .putBoolean("clock_date_$id", options.showDate)
            .putBoolean("clock_time_$id", options.showTime)
            .apply()
    }

    /** The welcome has been shown (or the older first-run sheet was, for people updating). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", sp.getBoolean("setup_offered", false))
        set(value) = sp.edit().putBoolean("onboarded", value).apply()

    /** Icons: Automatic (dark in dark mode), always light, always dark, or tinted. */
    var iconLook: IconLookSetting
        get() = runCatching { IconLookSetting.valueOf(sp.getString("icon_look", null)!!) }.getOrDefault(IconLookSetting.AUTO)
        set(value) = sp.edit().putString("icon_look", value.name).apply()

    /** Darken the wallpaper on the home screen in dark mode. */
    var dimWallpaper: Boolean
        get() = sp.getBoolean("dim_wallpaper", true)
        set(value) = sp.edit().putBoolean("dim_wallpaper", value).apply()

    // The clock's extra lines, and how zmanim are worked out.

    /** The calendar whose date shows next to the usual one. */
    var secondCalendar: SecondCalendar
        get() = runCatching { SecondCalendar.valueOf(sp.getString("second_calendar", null)!!) }.getOrDefault(SecondCalendar.HEBREW)
        set(value) = sp.edit().putString("second_calendar", value.name).apply()

    /** Jewish zmanim (true), or just sunrise and sunset. */
    var dailyZmanim: Boolean
        get() = sp.getBoolean("daily_zmanim", true)
        set(value) = sp.edit().putBoolean("daily_zmanim", value).apply()

    // At a glance: each part can be turned off; each only shows when it matters.

    var glanceHolidays: Boolean
        get() = sp.getBoolean("glance_holidays", true)
        set(value) = sp.edit().putBoolean("glance_holidays", value).apply()

    var glanceParsha: Boolean
        get() = sp.getBoolean("glance_parsha", true)
        set(value) = sp.edit().putBoolean("glance_parsha", value).apply()

    var glanceOmer: Boolean
        get() = sp.getBoolean("glance_omer", true)
        set(value) = sp.edit().putBoolean("glance_omer", value).apply()

    var glanceDaf: Boolean
        get() = sp.getBoolean("glance_daf", false)
        set(value) = sp.edit().putBoolean("glance_daf", value).apply()

    var glanceCandles: Boolean
        get() = sp.getBoolean("glance_candles", true)
        set(value) = sp.edit().putBoolean("glance_candles", value).apply()

    var glanceUpcoming: Boolean
        get() = sp.getBoolean("glance_upcoming", true)
        set(value) = sp.edit().putBoolean("glance_upcoming", value).apply()

    /** The wallpaper is the Weather one ([WeatherWallpaper]), kept up to date. */
    var weatherWallpaper: Boolean
        get() = sp.getBoolean("weather_wallpaper", false)
        set(value) = sp.edit().putBoolean("weather_wallpaper", value).apply()

    /** The weather and time of day the Weather wallpaper last showed, so it's only set again when they change. */
    var weatherWallpaperScene: String?
        get() = sp.getString("weather_wallpaper_scene", null)
        set(value) = sp.edit().putString("weather_wallpaper_scene", value).apply()

    /** Photo Shuffle is on ([Shuffle]): the collection it goes through, or null for your own photos; where it's got to, and when it last moved on. */
    var shuffleOn: Boolean
        get() = sp.getBoolean("shuffle_on", false)
        set(value) = sp.edit().putBoolean("shuffle_on", value).apply()
    var shuffleCollection: String?
        get() = sp.getString("shuffle_collection", null)
        set(value) = sp.edit().putString("shuffle_collection", value).apply()
    var shuffleIndex: Int
        get() = sp.getInt("shuffle_index", -1)
        set(value) = sp.edit().putInt("shuffle_index", value).apply()
    var shuffleLast: Long
        get() = sp.getLong("shuffle_last", 0)
        set(value) = sp.edit().putLong("shuffle_last", value).apply()
    var shuffleEvery: Shuffle.Every
        get() = Shuffle.Every.entries.firstOrNull { it.name == sp.getString("shuffle_every", null) } ?: Shuffle.Every.WAKE
        set(value) = sp.edit().putString("shuffle_every", value.name).apply()

    var glanceWeather: Boolean
        get() = sp.getBoolean("glance_weather", true)
        set(value) = sp.edit().putBoolean("glance_weather", value).apply()

    var glanceAlarm: Boolean
        get() = sp.getBoolean("glance_alarm", true)
        set(value) = sp.edit().putBoolean("glance_alarm", value).apply()

    var glanceMissed: Boolean
        get() = sp.getBoolean("glance_missed", true)
        set(value) = sp.edit().putBoolean("glance_missed", value).apply()

    var clockZman: Boolean
        get() = sp.getBoolean("clock_zman", true)
        set(value) = sp.edit().putBoolean("clock_zman", value).apply()

    var clockEvent: Boolean
        get() = sp.getBoolean("clock_event", true)
        set(value) = sp.edit().putBoolean("clock_event", value).apply()

    /** Sof zman Shema and Tefila by the Magen Avraham (true) or the Gra. */
    var zmanMagenAvraham: Boolean
        get() = sp.getBoolean("zman_mga", false)
        set(value) = sp.edit().putBoolean("zman_mga", value).apply()

    var candleMinutes: Int
        get() = sp.getInt("candle_minutes", 18)
        set(value) = sp.edit().putInt("candle_minutes", value).apply()

    /** A chosen city, or null to use where the phone is. Everything that needs a place uses this one. */
    var chosenPlace: ClockInfo.Place?
        get() = sp.getString("place", null)?.let { ClockInfo.Place.fromJson(it) }
        set(value) = sp.edit().putString("place", value?.toJson()).apply()

    /** The phone's last known position, kept for when it can't be found. */
    var lastPlace: Pair<Double, Double>?
        get() = if (sp.contains("last_lat")) sp.getFloat("last_lat", 0f).toDouble() to sp.getFloat("last_lon", 0f).toDouble() else null
        set(value) {
            if (value == null) sp.edit().remove("last_lat").remove("last_lon").apply()
            else sp.edit().putFloat("last_lat", value.first.toFloat()).putFloat("last_lon", value.second.toFloat()).apply()
        }

    /** The name of where the phone last was, and the position it was looked up for. */
    var lastPlaceName: Pair<String, Pair<Double, Double>>?
        get() = sp.getString("place_name", null)?.let { it to (sp.getFloat("place_name_lat", 0f).toDouble() to sp.getFloat("place_name_lon", 0f).toDouble()) }
        set(value) {
            if (value == null) sp.edit().remove("place_name").remove("place_name_lat").remove("place_name_lon").apply()
            else sp.edit().putString("place_name", value.first)
                .putFloat("place_name_lat", value.second.first.toFloat()).putFloat("place_name_lon", value.second.second.toFloat()).apply()
        }

    fun launchCount(key: String): Int = launches.getInt(key, 0)

    fun recordLaunch(key: String) {
        launches.edit().putInt(key, launchCount(key) + 1).apply()
    }

    companion object {
        const val DOCK_SIZE = 4
    }
}
