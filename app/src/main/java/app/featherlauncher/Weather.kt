package app.featherlauncher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The weather where the clock is, from Open-Meteo (free, no account), kept for half an hour.
 * In Fahrenheit where that's what people use, Celsius everywhere else.
 */
object Weather {
    class Now(
        val temperature: Int, val high: Int, val low: Int, val description: String, val sky: Sky, val night: Boolean,
        /** The coming hours and days, for the Weather widget. */
        val hours: List<Hour> = emptyList(), val days: List<Day> = emptyList(),
        /** Today's highest chance of rain or snow, in percent. */
        val rainChance: Int = 0,
    )
    /** [rain] is the chance of rain or snow that hour, in percent. */
    class Hour(val time: Long, val temperature: Int, val sky: Sky, val night: Boolean, val rain: Int = 0)
    class Day(val time: Long, val high: Int, val low: Int, val sky: Sky, val rain: Int = 0)

    private const val FRESH = 30 * 60_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: Triple<String, Long, Now>? = null
    @Volatile private var loading = false
    /** Where and when loading last failed: offline, it tries again after [RETRY], not at every look. */
    @Volatile private var failed: Pair<String, Long>? = null
    private const val RETRY = 10 * 60_000L

    val fahrenheit get() = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    /**
     * The weather at [place], or null until it's first loaded. When it's missing or more than
     * half an hour old, it's loaded in the background and [loaded] runs on the main thread.
     */
    fun at(place: ClockInfo.Place, loaded: () -> Unit): Now? {
        val key = "%.2f,%.2f,%b".format(Locale.US, place.lat, place.lon, fahrenheit)
        val known = cached?.takeIf { it.first == key }
        val time = System.currentTimeMillis()
        if ((known == null || time - known.second > FRESH) && !loading && failed?.let { (at, t) -> at == key && time - t in 0 until RETRY } != true) {
            loading = true
            Thread {
                val now = runCatching { fetch(place) }.getOrNull()
                loading = false
                if (now == null) failed = key to System.currentTimeMillis()
                if (now != null) {
                    cached = Triple(key, System.currentTimeMillis(), now)
                    main.post(loaded)
                }
            }.start()
        }
        return known?.third
    }

    private fun fetch(place: ClockInfo.Place): Now {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max&forecast_days=10&timezone=auto&timeformat=unixtime$unit"
        val text = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            inputStream.bufferedReader().use { it.readText() }
        }
        val o = JSONObject(text)
        val current = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val code = current.getInt("weather_code")
        val hourly = o.getJSONObject("hourly")
        val now = System.currentTimeMillis() / 1000
        val hours = (0 until hourly.getJSONArray("time").length())
            .filter { hourly.getJSONArray("time").getLong(it) > now }
            .take(24)
            .map { i ->
                Hour(hourly.getJSONArray("time").getLong(i) * 1000, Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)).toInt(),
                    sky(hourly.getJSONArray("weather_code").getInt(i)), hourly.getJSONArray("is_day").getInt(i) == 0,
                    hourly.optJSONArray("precipitation_probability")?.optInt(i) ?: 0)
            }
        val days = (0 until daily.getJSONArray("time").length()).map { i ->
            Day(daily.getJSONArray("time").getLong(i) * 1000, Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(), sky(daily.getJSONArray("weather_code").getInt(i)),
                daily.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0)
        }
        return Now(
            Math.round(current.getDouble("temperature_2m")).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            describe(code), sky(code), current.optInt("is_day", 1) == 0, hours, days,
            daily.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
        )
    }

    private fun sky(code: Int) = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        in 51..67, in 80..82 -> Sky.RAIN
        else -> Sky.CLOUDY
    }

    /** The WMO weather codes Open-Meteo uses, in a word or two. */
    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> ""
    }
}
