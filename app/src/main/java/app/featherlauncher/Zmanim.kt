package app.featherlauncher

import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** One zman: its name and when it is (epoch millis). */
data class Zman(val name: String, val time: Long)

/**
 * The day's zmanim for a place, worked out from the sun's position with NOAA's solar
 * calculations (the ones KosherJava and Hebcal use), in the place's own time zone. Shaos
 * zmaniyos follow the Gra (sunrise to sunset) or, for Shema and Tefila, optionally the Magen
 * Avraham (72 minutes before and after). With [sunOnly], just sunrise and sunset.
 */
object Zmanim {
    /** Sun this many degrees below the horizon, counted as a zenith angle. Sunrise allows for refraction and the sun's radius. */
    const val SUNRISE = 90.833
    private const val ALOS = 106.1
    private const val MISHEYAKIR = 101.5
    private const val TZEIS = 98.5

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Everything for the day containing [day] where [place] is, in order. Friday adds candle lighting. */
    fun forDay(day: Long, place: ClockInfo.Place, magenAvraham: Boolean, candleMinutes: Int, sunOnly: Boolean = false): List<Zman> {
        val sunrise = sun(day, place, SUNRISE, true) ?: return emptyList()
        val sunset = sun(day, place, SUNRISE, false) ?: return emptyList()
        if (sunOnly) return listOf(Zman("Sunrise", sunrise), Zman("Sunset", sunset))
        val hour = (sunset - sunrise) / 12
        // Magen Avraham's day runs from 72 minutes before sunrise to 72 after sunset.
        val maStart = sunrise - 72 * MINUTE
        val maHour = (sunset + 72 * MINUTE - maStart) / 12
        val shemaStart = if (magenAvraham) maStart else sunrise
        val shemaHour = if (magenAvraham) maHour else hour
        val weekday = Calendar.getInstance(place.zone).apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK)

        val list = ArrayList<Zman>()
        sun(day, place, ALOS, true)?.let { list += Zman("Alos hashachar", it) }
        sun(day, place, MISHEYAKIR, true)?.let { list += Zman("Misheyakir", it) }
        list += Zman("Netz", sunrise)
        list += Zman("Sof zman Shema", shemaStart + 3 * shemaHour)
        list += Zman("Sof zman Tefila", shemaStart + 4 * shemaHour)
        list += Zman("Chatzos", sunrise + 6 * hour)
        list += Zman("Mincha gedola", sunrise + 6 * hour + hour / 2)
        list += Zman("Mincha ketana", sunrise + 9 * hour + hour / 2)
        list += Zman("Plag hamincha", sunrise + 10 * hour + 3 * hour / 4)
        if (weekday == Calendar.FRIDAY) list += Zman("Candle lighting", sunset - candleMinutes * MINUTE)
        list += Zman("Shkiah", sunset)
        sun(day, place, TZEIS, false)?.let { list += Zman("Tzeis", it) }
        return list.sortedBy { it.time }
    }

    /** The next zman after [now], looking into tomorrow once today's are over. */
    fun next(now: Long, place: ClockInfo.Place, magenAvraham: Boolean, candleMinutes: Int, sunOnly: Boolean = false): Zman? =
        (forDay(now, place, magenAvraham, candleMinutes, sunOnly) + forDay(now + 24 * HOUR, place, magenAvraham, candleMinutes, sunOnly))
            // Misheyakir and Mincha gedola are left out of "next" to keep the clock calm.
            .filter { it.time > now && it.name != "Misheyakir" && it.name != "Mincha gedola" }
            .minByOrNull { it.time }

    /**
     * When the sun reaches [zenith] on the day containing [day] where [place] is, rising or
     * setting; null when it doesn't (far north in summer). NOAA's method, two passes.
     */
    fun sun(day: Long, place: ClockInfo.Place, zenith: Double, rising: Boolean): Long? =
        sunMemo.get(SunKey(JewishCalendar.day(day, place.zone), place.lat, place.lon, place.zone.id, zenith, rising)) { sunOf(day, place, zenith, rising) }

    /** The same day, place and angle always give the same time; the clock and widgets ask for it every minute. */
    private data class SunKey(val day: Long, val lat: Double, val lon: Double, val zone: String, val zenith: Double, val rising: Boolean)
    private val sunMemo = Memo<SunKey, Long?>(64)

    private fun sunOf(day: Long, place: ClockInfo.Place, zenith: Double, rising: Boolean): Long? {
        val local = Calendar.getInstance(place.zone).apply { timeInMillis = day }
        val jd = julianDay(local.get(Calendar.YEAR), local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH))
        // NOAA counts longitude positive to the west.
        val lon = -place.lon
        fun minutesUtc(t: Double): Double? {
            val eq = equationOfTime(t)
            val angle = hourAngle(place.lat, declination(t), zenith) ?: return null
            return 720 + 4 * (lon - Math.toDegrees(if (rising) angle else -angle)) - eq
        }
        val noon = solarNoonUtc(jd, lon)
        val first = minutesUtc(centuries(jd + noon / 1440.0)) ?: return null
        val utcMinutes = minutesUtc(centuries(jd + first / 1440.0)) ?: return null
        // Minutes after midnight UTC on the local date, which can fall on the day before or after.
        val midnightUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        var time = midnightUtc + (utcMinutes * MINUTE).toLong()
        val dayOfResult = Calendar.getInstance(place.zone).apply { timeInMillis = time }.get(Calendar.DAY_OF_YEAR)
        val diff = dayOfResult - local.get(Calendar.DAY_OF_YEAR)
        if (diff == 1 || diff < -300) time -= 24 * HOUR
        if (diff == -1 || diff > 300) time += 24 * HOUR
        return time
    }

    // region NOAA solar calculations

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = y / 100
        val b = 2 - a + a / 4
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun centuries(jd: Double) = (jd - 2451545.0) / 36525.0

    private fun meanLongitude(t: Double) = norm(280.46646 + t * (36000.76983 + 0.0003032 * t), 360.0)
    private fun meanAnomaly(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    private fun eccentricity(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun equationOfCenter(t: Double): Double {
        val m = meanAnomaly(t)
        return sinD(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sinD(2 * m) * (0.019993 - 0.000101 * t) + sinD(3 * m) * 0.000289
    }

    private fun apparentLongitude(t: Double) =
        meanLongitude(t) + equationOfCenter(t) - 0.00569 - 0.00478 * sinD(125.04 - 1934.136 * t)

    private fun obliquity(t: Double): Double {
        val mean = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        return mean + 0.00256 * cosD(125.04 - 1934.136 * t)
    }

    private fun declination(t: Double) = Math.toDegrees(asin(sinD(obliquity(t)) * sinD(apparentLongitude(t))))

    /** Minutes. */
    private fun equationOfTime(t: Double): Double {
        val e = obliquity(t)
        val l0 = meanLongitude(t)
        val ecc = eccentricity(t)
        val m = meanAnomaly(t)
        val y = tanD(e / 2) * tanD(e / 2)
        val eq = y * sinD(2 * l0) - 2 * ecc * sinD(m) + 4 * ecc * y * sinD(m) * cosD(2 * l0) -
            0.5 * y * y * sinD(4 * l0) - 1.25 * ecc * ecc * sinD(2 * m)
        return Math.toDegrees(eq) * 4
    }

    /** Radians from noon at which the sun is at [zenith]; null if it never gets there that day. */
    private fun hourAngle(lat: Double, decl: Double, zenith: Double): Double? {
        val cosH = cosD(zenith) / (cosD(lat) * cosD(decl)) - tanD(lat) * tanD(decl)
        if (cosH > 1 || cosH < -1) return null
        return acos(cosH)
    }

    /** Minutes after midnight UTC. */
    private fun solarNoonUtc(jd: Double, lon: Double): Double {
        val first = 720 + lon * 4 - equationOfTime(centuries(jd + lon / 360.0))
        return 720 + lon * 4 - equationOfTime(centuries(jd - 0.5 + first / 1440.0))
    }

    // endregion

    /**
     * "25 Tishrei", the Hebrew date for [now]. After [sunset] the Jewish day has already
     * turned over, so it's tomorrow's date.
     */
    fun hebrewDate(now: Long, sunset: Long?): String {
        val cal = HebrewCalendar().apply {
            timeInMillis = now
            if (sunset != null && now >= sunset) add(Calendar.DAY_OF_MONTH, 1)
        }
        val leap = cal.getActualMaximum(Calendar.DAY_OF_YEAR) > 380
        val month = when (cal.get(Calendar.MONTH)) {
            HebrewCalendar.TISHRI -> "Tishrei"
            HebrewCalendar.HESHVAN -> "Cheshvan"
            HebrewCalendar.KISLEV -> "Kislev"
            HebrewCalendar.TEVET -> "Teves"
            HebrewCalendar.SHEVAT -> "Shevat"
            HebrewCalendar.ADAR_1 -> "Adar I"
            HebrewCalendar.ADAR -> if (leap) "Adar II" else "Adar"
            HebrewCalendar.NISAN -> "Nissan"
            HebrewCalendar.IYAR -> "Iyar"
            HebrewCalendar.SIVAN -> "Sivan"
            HebrewCalendar.TAMUZ -> "Tammuz"
            HebrewCalendar.AV -> "Av"
            else -> "Elul"
        }
        return "${cal.get(Calendar.DAY_OF_MONTH)} $month"
    }

    private fun norm(v: Double, range: Double) = ((v % range) + range) % range
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
    private fun tanD(d: Double) = tan(Math.toRadians(d))
}
