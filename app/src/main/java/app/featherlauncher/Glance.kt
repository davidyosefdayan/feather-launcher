package app.featherlauncher

import android.app.AlarmManager
import android.content.Context
import java.util.Calendar
import java.util.Date

/**
 * At a glance: what the clock and Up next ([UpNextWidget]) show. The date; a quieter line with the second
 * calendar's date, today's holiday, the parsha, the Omer and Daf Yomi; then the weather and up
 * to two things coming up, the most pressing first. Each part can be turned
 * off in Calendar and times, and each only shows when it matters. The Jewish parts are there
 * with the Hebrew calendar.
 */
class Glance(private val context: Context) {
    /** What tapping something does. */
    enum class Tap { ZMANIM, EVENT, ALARMS }

    /**
     * One thing coming up; lower [rank] is more pressing. [name] and [at] ("in 25 min", "5:42 PM")
     * are its two halves for a row; [text] is the two together, for a pill. [time] is when it is,
     * and [clock] that time as the clock shows it ("4:00 PM"), for things that happen at a time.
     */
    class Item(val icon: Int, val name: String, val at: String?, val tap: Tap, val rank: Int, val event: ClockInfo.Event? = null,
               val text: String = listOfNotNull(name, at).joinToString(" "), val time: Long? = null, val clock: String? = null)

    /** [coming] is everything coming up, the most pressing first; the clock shows the first two. */
    class Lines(val date: String, val detail: String?, val weather: Weather.Now?, val coming: List<Item>, val place: ClockInfo.Place?)

    private val info = ClockInfo(context)

    /** Everything for [now]. [weatherLoaded] runs when the weather arrives, to show it. */
    fun lines(now: Long, weatherLoaded: () -> Unit): Lines {
        val prefs = Prefs(context)
        val place = info.place()
        val jewish = prefs.secondCalendar == SecondCalendar.HEBREW
        val israel = place?.inIsrael == true
        val zone = place?.zone ?: java.util.TimeZone.getDefault()
        val today = JewishCalendar.day(now, zone)
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        // The Jewish day turns over at shkiah.
        val jewishDay = if (sunset != null && now >= sunset) today + 1 else today

        val date = android.text.format.DateFormat.format("EEE, MMM d", now).toString()
        val weather = if (prefs.glanceWeather && place != null) Weather.at(place, weatherLoaded) else null

        // Just what each is ("Lech Lecha", "Bava Basra 45"), in Hebrew if that's chosen.
        val hebrew = jewish && prefs.hebrewInHebrew
        fun he(text: String, translate: (String) -> String) = if (hebrew) HebrewText.isolate(translate(text)) else text
        val week = mutableListOf<String>()
        prefs.secondCalendar.format(now, sunset, hebrew)?.let { week += if (hebrew) HebrewText.isolate(it) else it }
        if (jewish && prefs.glanceHolidays) week += JewishCalendar.holidays(jewishDay, israel).map { he(it, HebrewText::holiday) }
        if (jewish) {
            if (prefs.glanceParsha) JewishCalendar.parshaOfWeek(jewishDay, israel)?.let { week += he(it, HebrewText::parsha) }
            if (prefs.glanceOmer) JewishCalendar.omer(jewishDay)?.let { week += if (hebrew) HebrewText.isolate(HebrewText.omer(it)) else JewishCalendar.omerText(it) }
            if (prefs.glanceDaf) JewishCalendar.dafYomi(today)?.let { week += he(it, HebrewText::daf) }
        }

        val coming = ArrayList<Item>()
        val time = android.text.format.DateFormat.getTimeFormat(context)
        val placeTime = android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = zone }

        if (prefs.clockEvent) info.nextEvent(now)?.let { e ->
            val minutes = (e.begin - now) / 60_000
            val at = if (minutes < 60) "in ${minutes.coerceAtLeast(1)} min" else time.format(Date(e.begin))
            coming += Item(R.drawable.ic_calendar, e.title, at, Tap.EVENT, if (minutes < 60) 0 else 6, e,
                time = e.begin, clock = if (e.end > e.begin) "${time.format(Date(e.begin))} – ${time.format(Date(e.end))}" else time.format(Date(e.begin)))
        }

        // Candle lighting on Friday and Erev Yom Tov, until it's time.
        if (jewish && prefs.glanceCandles && place != null) {
            val weekday = JewishCalendar.weekday(today)
            val erevYomTov = JewishCalendar.isYomTov(today + 1, israel) && !JewishCalendar.isYomTov(today, israel)
            val lighting = Zmanim.sun(now, place, Zmanim.SUNRISE, false)?.minus(prefs.candleMinutes * 60_000L)
            if (lighting != null && now < lighting && weekday != Calendar.SATURDAY && (weekday == Calendar.FRIDAY || erevYomTov)) {
                coming += Item(R.drawable.ic_sunset, "Candle lighting", placeTime.format(Date(lighting)), Tap.ZMANIM,
                    if (lighting - now < 4 * HOUR) 1 else 5, time = lighting, clock = placeTime.format(Date(lighting)))
            }
        }

        // In the last hour before shkiah (the end of Mincha), it comes before the alarm and the like.
        val shkiahSoon = prefs.clockZman && sunset != null && now < sunset && sunset - now <= HOUR
        if (shkiahSoon) coming += Item(R.drawable.ic_sunset, "Shkiah", placeTime.format(Date(sunset!!)), Tap.ZMANIM, 2,
            time = sunset, clock = placeTime.format(Date(sunset)))

        // Tonight's alarm, once it's evening.
        if (prefs.glanceAlarm) {
            val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
            val evening = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) >= 18
            if (alarm != null && evening && alarm - now < 16 * HOUR) {
                coming += Item(R.drawable.ic_time, "Alarm", time.format(Date(alarm)), Tap.ALARMS, 4,
                    time = alarm, clock = time.format(Date(alarm)))
            }
        }

        if (jewish && prefs.glanceUpcoming) upcoming(jewishDay, israel)?.let { (english, ahead) ->
            val name = he(english, HebrewText::holiday)
            coming += Item(R.drawable.ic_calendar, name, if (ahead == 1) "Tomorrow" else "in $ahead days", Tap.ZMANIM, 7,
                text = if (ahead == 1) "Tomorrow: $name" else "$name in $ahead days")
        }

        if (prefs.clockZman && place != null) info.nextZman(now, place)?.takeIf { !(shkiahSoon && it.name == "Shkiah") }?.let { z ->
            coming += Item(R.drawable.ic_time, z.name, placeTime.format(Date(z.time)), Tap.ZMANIM, 8,
                time = z.time, clock = placeTime.format(Date(z.time)))
        }

        return Lines(date, week.joinToString(" · ").ifEmpty { null }, weather, coming.sortedBy { it.rank }, place)
    }

    /** The next holiday in the coming week that isn't today's, and how many days ahead it is. */
    private fun upcoming(today: Long, israel: Boolean): Pair<String, Int>? {
        val current = JewishCalendar.holidays(today, israel).map(::family).toSet()
        for (ahead in 1..7) {
            val name = JewishCalendar.holidays(today + ahead, israel).map(::family)
                .firstOrNull { it !in current && notable(it, ahead) } ?: continue
            return name to ahead
        }
        return null
    }

    /** The holiday a day belongs to: "Chanukah · day 3" is Chanukah, Chol Hamoed is part of its Yom Tov. */
    private fun family(name: String) = when {
        name.startsWith("Chanukah") -> "Chanukah"
        name == "Chol Hamoed Pesach" || name == "Shvi'i shel Pesach" || name == "Acharon shel Pesach" -> "Pesach"
        name == "Chol Hamoed Sukkos" || name == "Hoshana Rabbah" -> "Sukkos"
        else -> name
    }

    /** Worth mentioning ahead: not Erev days, and Rosh Chodesh only the day before. */
    private fun notable(name: String, ahead: Int) = !name.startsWith("Erev ") && (!name.startsWith("Rosh Chodesh") || ahead == 1)

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
