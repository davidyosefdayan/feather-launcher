package app.featherlauncher

import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone

/**
 * The Jewish calendar as frum communities keep it: Yom Tov (two days outside Eretz Yisrael),
 * fasts, Rosh Chodesh, Chanukah and Purim; the weekly parsha; the Omer; and Daf Yomi (Bavli).
 * Days are civil days, counted as days since 1970 ([day]); the Jewish date of a civil day is
 * the one its daytime falls in, so after shkiah callers look at tomorrow.
 */
object JewishCalendar {
    private const val DAY = 24 * 60 * 60_000L
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val ICU_UTC: android.icu.util.TimeZone = android.icu.util.TimeZone.getTimeZone("UTC")

    /** [month] is [HebrewCalendar]'s (TISHRI to ELUL, with ADAR_1 only in leap years). */
    data class HDate(val year: Int, val month: Int, val day: Int)

    /** The civil day containing [time] where the clock is ([zone]), as days since 1970. */
    fun day(time: Long, zone: TimeZone): Long = Math.floorDiv(time + zone.getOffset(time), DAY)

    fun hebrew(day: Long): HDate {
        val c = HebrewCalendar(ICU_UTC).apply { timeInMillis = day * DAY + DAY / 2 }
        return HDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    fun civil(year: Int, month: Int, dayOfMonth: Int): Long =
        HebrewCalendar(ICU_UTC).apply { clear(); set(year, month, dayOfMonth, 12, 0) }.timeInMillis.let { Math.floorDiv(it, DAY) }

    /** [Calendar.SUNDAY] to [Calendar.SATURDAY]. */
    fun weekday(day: Long): Int = ((day + 4) % 7 + 7).toInt() % 7 + 1

    fun isLeap(year: Int) = (7 * year + 1) % 19 < 7

    private fun yearLength(year: Int) = (civil(year + 1, HebrewCalendar.TISHRI, 1) - civil(year, HebrewCalendar.TISHRI, 1)).toInt()


    // region Holidays

    /** What [day] is, if anything: "Rosh Chodesh Kislev", "Chanukah · day 3", "Shavuos". */
    fun holidays(day: Long, israel: Boolean): List<String> {
        val h = hebrew(day)
        val w = weekday(day)
        val (y, m, d) = h
        val list = ArrayList<String>()
        fun add(name: String) = list.add(name)
        when (m) {
            HebrewCalendar.TISHRI -> when (d) {
                1, 2 -> add("Rosh Hashanah")
                3 -> if (w != Calendar.SATURDAY) add("Tzom Gedaliah")
                4 -> if (w == Calendar.SUNDAY) add("Tzom Gedaliah")
                9 -> add("Erev Yom Kippur")
                10 -> add("Yom Kippur")
                14 -> add("Erev Sukkos")
                15 -> add("Sukkos")
                16 -> add(if (israel) "Chol Hamoed Sukkos" else "Sukkos")
                in 17..20 -> add("Chol Hamoed Sukkos")
                21 -> add("Hoshana Rabbah")
                22 -> add(if (israel) "Shemini Atzeres and Simchas Torah" else "Shemini Atzeres")
                23 -> if (!israel) add("Simchas Torah")
            }
            HebrewCalendar.KISLEV -> if (d >= 25) add("Chanukah · day ${d - 24}")
            HebrewCalendar.TEVET -> {
                // Chanukah's last days: Kislev has 29 or 30 days.
                val chanukahDay = (day - civil(y, HebrewCalendar.KISLEV, 25) + 1).toInt()
                if (chanukahDay in 1..8) add("Chanukah · day $chanukahDay")
                if (d == 10) add("Asarah B'Teves")
            }
            HebrewCalendar.SHEVAT -> if (d == 15) add("Tu B'Shvat")
            HebrewCalendar.ADAR_1 -> when (d) {
                14 -> add("Purim Katan")
                15 -> add("Shushan Purim Katan")
            }
            HebrewCalendar.ADAR -> when (d) {
                // Moved to Thursday when the 13th is Shabbos.
                11 -> if (w == Calendar.THURSDAY) add("Taanis Esther")
                13 -> if (w != Calendar.SATURDAY) add("Taanis Esther")
                14 -> add("Purim")
                15 -> add("Shushan Purim")
            }
            HebrewCalendar.NISAN -> when (d) {
                14 -> add("Erev Pesach")
                15 -> add("Pesach")
                16 -> add(if (israel) "Chol Hamoed Pesach" else "Pesach")
                in 17..20 -> add("Chol Hamoed Pesach")
                21 -> add("Shvi'i shel Pesach")
                22 -> if (!israel) add("Acharon shel Pesach")
            }
            HebrewCalendar.IYAR -> when (d) {
                14 -> add("Pesach Sheni")
                18 -> add("Lag Ba'omer")
            }
            HebrewCalendar.SIVAN -> when (d) {
                5 -> add("Erev Shavuos")
                6 -> add("Shavuos")
                7 -> if (!israel) add("Shavuos")
            }
            HebrewCalendar.TAMUZ -> when (d) {
                // Pushed off to Sunday when it falls on Shabbos.
                17 -> if (w != Calendar.SATURDAY) add("Shiva Asar B'Tammuz")
                18 -> if (w == Calendar.SUNDAY) add("Shiva Asar B'Tammuz")
            }
            HebrewCalendar.AV -> when (d) {
                9 -> if (w != Calendar.SATURDAY) add("Tisha B'Av")
                10 -> if (w == Calendar.SUNDAY) add("Tisha B'Av")
                15 -> add("Tu B'Av")
            }
            HebrewCalendar.ELUL -> if (d == 29) add("Erev Rosh Hashanah")
        }
        roshChodesh(day)?.let { list.add(0, it) }
        return list
    }

    /** "Rosh Chodesh Kislev" on the 30th of the month before and the 1st (not Tishrei). */
    private fun roshChodesh(day: Long): String? {
        val h = hebrew(day)
        val next = hebrew(day + 1)
        val month = when {
            h.day == 1 && h.month != HebrewCalendar.TISHRI -> h.month
            h.day == 30 && next.month != HebrewCalendar.TISHRI -> next.month
            else -> return null
        }
        return "Rosh Chodesh ${monthName(month, isLeap(if (h.day == 30) next.year else h.year))}"
    }

    /** Whether [day] is Yom Tov, when Shabbos-like days start with candle lighting the evening before. */
    fun isYomTov(day: Long, israel: Boolean): Boolean {
        val (_, m, d) = hebrew(day)
        return when (m) {
            HebrewCalendar.TISHRI -> d == 1 || d == 2 || d == 10 || d == 15 || d == 22 || !israel && (d == 16 || d == 23)
            HebrewCalendar.NISAN -> d == 15 || d == 21 || !israel && (d == 16 || d == 22)
            HebrewCalendar.SIVAN -> d == 6 || !israel && d == 7
            else -> false
        }
    }

    fun monthName(month: Int, leap: Boolean) = when (month) {
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

    // endregion

    // region Omer

    /** The day of the Omer [day]'s daytime belongs to (counted the night before), 1 to 49, or null. */
    fun omer(day: Long): Int? {
        val h = hebrew(day)
        val n = (day - civil(h.year, HebrewCalendar.NISAN, 16) + 1).toInt()
        return n.takeIf { it in 1..49 }
    }

    /** "Day 23 of the Omer". */
    fun omerText(n: Int) = "Day $n of the Omer"

    // endregion

    // region Daf Yomi

    private val MASECHTOS = listOf(
        "Berachos" to 64, "Shabbos" to 157, "Eruvin" to 105, "Pesachim" to 121, "Shekalim" to 22, "Yoma" to 88,
        "Sukkah" to 56, "Beitzah" to 40, "Rosh Hashanah" to 35, "Taanis" to 31, "Megillah" to 32, "Moed Katan" to 29,
        "Chagigah" to 27, "Yevamos" to 122, "Kesubos" to 112, "Nedarim" to 91, "Nazir" to 66, "Sotah" to 49,
        "Gittin" to 90, "Kiddushin" to 82, "Bava Kamma" to 119, "Bava Metzia" to 119, "Bava Basra" to 176,
        "Sanhedrin" to 113, "Makkos" to 24, "Shevuos" to 49, "Avodah Zarah" to 76, "Horayos" to 14, "Zevachim" to 120,
        "Menachos" to 110, "Chullin" to 142, "Bechoros" to 61, "Arachin" to 34, "Temurah" to 34, "Kereisos" to 28,
        "Meilah" to 22, "Kinnim" to 4, "Tamid" to 9, "Middos" to 5, "Niddah" to 73,
    )

    /** Cycles since the eighth (24 June 1975) are all this long, with 22 dafim of Shekalim. */
    private const val CYCLE = 2711
    private val CYCLE_8_START = Math.floorDiv(Calendar.getInstance(UTC).apply { clear(); set(1975, Calendar.JUNE, 24) }.timeInMillis, DAY)

    /** "Bava Basra 45", the Bavli daf for [day]; null before 1975. */
    fun dafYomi(day: Long): String? {
        if (day < CYCLE_8_START) return null
        var n = ((day - CYCLE_8_START) % CYCLE).toInt()
        for ((index, entry) in MASECHTOS.withIndex()) {
            val (name, last) = entry
            val count = last - 1
            if (n < count) {
                // Kinnim, Tamid and Middos continue Meilah's numbering, as printed.
                val offset = when (index) { 36 -> 21; 37 -> 24; 38 -> 32; else -> 0 }
                return "$name ${n + 2 + offset}"
            }
            n -= count
        }
        return null
    }

    // endregion

    // region Parsha

    val PARSHIYOS = listOf(
        "Bereishis", "Noach", "Lech Lecha", "Vayeira", "Chayei Sarah", "Toldos", "Vayeitzei", "Vayishlach", "Vayeishev",
        "Mikeitz", "Vayigash", "Vayechi", "Shemos", "Va'eira", "Bo", "Beshalach", "Yisro", "Mishpatim", "Terumah",
        "Tetzaveh", "Ki Sisa", "Vayakhel", "Pekudei", "Vayikra", "Tzav", "Shemini", "Tazria", "Metzora", "Acharei Mos",
        "Kedoshim", "Emor", "Behar", "Bechukosai", "Bamidbar", "Nasso", "Beha'aloscha", "Shelach", "Korach", "Chukas",
        "Balak", "Pinchas", "Mattos", "Masei", "Devarim", "Va'eschanan", "Eikev", "Re'eh", "Shoftim", "Ki Seitzei",
        "Ki Savo", "Nitzavim", "Vayeilech", "Haazinu",
    )

    /** The parshiyos that can be read together, by the index of the first. */
    private val PAIRS = listOf(21, 26, 28, 31, 38, 41, 50)

    /**
     * Which [PAIRS] are read together in a year, by its type: how long it is, the day Rosh
     * Hashanah falls on, and whether it's in Eretz Yisrael. Bit i is [PAIRS] i. Worked out from
     * Hebcal's calendar for 2000 to 2060 and checked against it.
     */
    private fun doubled(length: Int, roshHashanah: Int, israel: Boolean): Int =
        DOUBLED["$length/$roshHashanah/${if (israel) "il" else "da"}"] ?: error("no year type $length/$roshHashanah")

    private val DOUBLED: Map<String, Int> = mapOf(
        "353/2/da" to 0b1101111,
        "353/7/da" to 0b0101111,
        "354/3/da" to 0b1111111,
        "354/5/da" to 0b0101111,
        "355/2/da" to 0b1111111,
        "355/5/da" to 0b0101110,
        "355/7/da" to 0b1101111,
        "383/2/da" to 0b1110000,
        "383/5/da" to 0b0000000,
        "383/7/da" to 0b1100000,
        "384/3/da" to 0b0100000,
        "385/2/da" to 0b0100000,
        "385/5/da" to 0b1000000,
        "385/7/da" to 0b1110000,
        "353/2/il" to 0b1101111,
        "353/7/il" to 0b0101111,
        "354/3/il" to 0b1101111,
        "354/5/il" to 0b0100111,
        "355/2/il" to 0b1101111,
        "355/5/il" to 0b0101110,
        "355/7/il" to 0b1101111,
        "383/2/il" to 0b1100000,
        "383/5/il" to 0b0000000,
        "383/7/il" to 0b1100000,
        "384/3/il" to 0b0000000,
        "385/2/il" to 0b0000000,
        "385/5/il" to 0b1000000,
        "385/7/il" to 0b1100000,
    )

    /** Whether Shabbos [day] has its own reading for Yom Tov or Chol Hamoed instead of the parsha. */
    private fun festival(day: Long, israel: Boolean): Boolean {
        val (_, m, d) = hebrew(day)
        return when (m) {
            HebrewCalendar.TISHRI -> d in 1..2 || d == 10 || d in 15..(if (israel) 22 else 23)
            HebrewCalendar.NISAN -> d in 15..(if (israel) 21 else 22)
            HebrewCalendar.SIVAN -> d == 6 || !israel && d == 7
            else -> false
        }
    }

    /** The parsha read on Shabbos [shabbos] ("Tazria-Metzora"), or null when it's Yom Tov or Chol Hamoed. */
    fun parsha(shabbos: Long, israel: Boolean): String? {
        if (festival(shabbos, israel)) return null
        val h = hebrew(shabbos)
        // The cycle starts the Shabbos after Simchas Torah; before it in Tishrei, it's last year's.
        val year = if (h.month == HebrewCalendar.TISHRI && h.day <= 23) h.year - 1 else h.year
        val start = civil(year, HebrewCalendar.TISHRI, if (israel) 22 else 23)
        val mask = doubled(yearLength(year), weekday(civil(year, HebrewCalendar.TISHRI, 1)), israel)
        val readings = ArrayList<String>()
        var i = 0
        while (i < PARSHIYOS.size) {
            val pair = PAIRS.indexOf(i)
            if (pair >= 0 && mask and (1 shl pair) != 0) {
                readings += "${PARSHIYOS[i]}-${PARSHIYOS[i + 1]}"
                i += 2
            } else {
                readings += PARSHIYOS[i]
                i++
            }
        }
        var next = start + ((Calendar.SATURDAY - weekday(start) + 7) % 7).let { if (it == 0) 7 else it }
        var k = 0
        while (next <= shabbos) {
            if (!festival(next, israel)) {
                if (next == shabbos) return readings.getOrNull(k)
                k++
            }
            next += 7
        }
        return null
    }

    /** This week's parsha: the one read on the coming Shabbos (today, if it's Shabbos). */
    fun parshaOfWeek(day: Long, israel: Boolean): String? {
        val shabbos = day + (Calendar.SATURDAY - weekday(day))
        return parsha(shabbos, israel)
    }

    // endregion
}
