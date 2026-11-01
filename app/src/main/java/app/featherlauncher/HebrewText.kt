package app.featherlauncher

import android.icu.util.HebrewCalendar

/**
 * The Jewish calendar's words in Hebrew, for those who'd rather read "י׳ חשון" and "לך לך" than
 * "10 Cheshvan" and "Lech Lecha". Each takes the English name [JewishCalendar] gives; a name it
 * doesn't know stays in English.
 */
object HebrewText {
    private const val GERESH = '׳'
    private const val GERSHAYIM = '״'

    /** [n] in Hebrew letters: 3 is ג׳, 15 is ט״ו, 145 is קמ״ה. */
    fun number(n: Int): String {
        var rest = n
        val letters = StringBuilder()
        while (rest >= 400) { letters.append('ת'); rest -= 400 }
        if (rest >= 100) { letters.append("קרש"[rest / 100 - 1]); rest %= 100 }
        // 15 and 16 are written 9+6 and 9+7, so they don't spell the Name.
        when (rest) {
            15 -> { letters.append("טו"); rest = 0 }
            16 -> { letters.append("טז"); rest = 0 }
        }
        if (rest >= 10) { letters.append("יכלמנסעפצ"[rest / 10 - 1]); rest %= 10 }
        if (rest > 0) letters.append("אבגדהוזחט"[rest - 1])
        return if (letters.length == 1) "$letters$GERESH" else letters.insert(letters.length - 1, GERSHAYIM).toString()
    }

    fun month(month: Int, leap: Boolean) = when (month) {
        HebrewCalendar.TISHRI -> "תשרי"
        HebrewCalendar.HESHVAN -> "חשון"
        HebrewCalendar.KISLEV -> "כסלו"
        HebrewCalendar.TEVET -> "טבת"
        HebrewCalendar.SHEVAT -> "שבט"
        HebrewCalendar.ADAR_1 -> "אדר א$GERESH"
        HebrewCalendar.ADAR -> if (leap) "אדר ב$GERESH" else "אדר"
        HebrewCalendar.NISAN -> "ניסן"
        HebrewCalendar.IYAR -> "אייר"
        HebrewCalendar.SIVAN -> "סיון"
        HebrewCalendar.TAMUZ -> "תמוז"
        HebrewCalendar.AV -> "אב"
        else -> "אלול"
    }

    /** "י׳ חשון", or "10 Cheshvan" in English; [day] is the epoch day ([JewishCalendar.day]). */
    fun date(day: Long): String {
        val h = JewishCalendar.hebrew(day)
        return "${number(h.day)} ${month(h.month, JewishCalendar.isLeap(h.year))}"
    }

    /** "לך לך"; a double parsha is "תזריע-מצורע". */
    fun parsha(name: String) = name.split("-").joinToString("-") { PARSHIYOS[it] ?: it }

    /** "ראש חודש כסלו", "חנוכה · יום ג׳". */
    fun holiday(name: String): String {
        name.removePrefix("Rosh Chodesh ").takeIf { it != name }?.let { m -> return "ראש חודש ${MONTHS[m] ?: m}" }
        name.removePrefix("Chanukah · day ").toIntOrNull()?.let { return "חנוכה · יום ${number(it)}" }
        return HOLIDAYS[name] ?: name
    }

    /** "כ״ג בעומר". */
    fun omer(n: Int) = "${number(n)} בעומר"

    /** "בבא בתרא מ״ה" for "Bava Basra 45". */
    fun daf(english: String): String {
        val page = english.substringAfterLast(' ').toIntOrNull() ?: return english
        val name = english.substringBeforeLast(' ')
        return "${MASECHTOS[name] ?: name} ${number(page)}"
    }

    /**
     * [text] kept whole inside an English line, so the dots between stay put: marks on either
     * side that read left to right keep two Hebrew parts in a row from swapping places.
     */
    fun isolate(text: String) = "\u200E$text\u200E"

    private val MONTHS = mapOf(
        "Tishrei" to "תשרי", "Cheshvan" to "חשון", "Kislev" to "כסלו", "Teves" to "טבת", "Shevat" to "שבט",
        "Adar I" to "אדר א$GERESH", "Adar II" to "אדר ב$GERESH", "Adar" to "אדר", "Nissan" to "ניסן", "Iyar" to "אייר",
        "Sivan" to "סיון", "Tammuz" to "תמוז", "Av" to "אב", "Elul" to "אלול",
    )

    private val HOLIDAYS = mapOf(
        "Rosh Hashanah" to "ראש השנה",
        "Erev Rosh Hashanah" to "ערב ראש השנה",
        "Tzom Gedaliah" to "צום גדליה",
        "Erev Yom Kippur" to "ערב יום כיפור",
        "Yom Kippur" to "יום כיפור",
        "Erev Sukkos" to "ערב סוכות",
        "Sukkos" to "סוכות",
        "Chol Hamoed Sukkos" to "חול המועד סוכות",
        "Hoshana Rabbah" to "הושענא רבה",
        "Shemini Atzeres" to "שמיני עצרת",
        "Shemini Atzeres and Simchas Torah" to "שמיני עצרת ושמחת תורה",
        "Simchas Torah" to "שמחת תורה",
        "Chanukah" to "חנוכה",
        "Asarah B'Teves" to "עשרה בטבת",
        "Tu B'Shvat" to "ט${GERSHAYIM}ו בשבט",
        "Purim Katan" to "פורים קטן",
        "Shushan Purim Katan" to "שושן פורים קטן",
        "Taanis Esther" to "תענית אסתר",
        "Purim" to "פורים",
        "Shushan Purim" to "שושן פורים",
        "Erev Pesach" to "ערב פסח",
        "Pesach" to "פסח",
        "Chol Hamoed Pesach" to "חול המועד פסח",
        "Shvi'i shel Pesach" to "שביעי של פסח",
        "Acharon shel Pesach" to "אחרון של פסח",
        "Pesach Sheni" to "פסח שני",
        "Lag Ba'omer" to "ל${GERSHAYIM}ג בעומר",
        "Erev Shavuos" to "ערב שבועות",
        "Shavuos" to "שבועות",
        "Shiva Asar B'Tammuz" to "שבעה עשר בתמוז",
        "Tisha B'Av" to "תשעה באב",
        "Tu B'Av" to "ט${GERSHAYIM}ו באב",
    )

    private val PARSHIYOS = JewishCalendar.PARSHIYOS.zip(listOf(
        "בראשית", "נח", "לך לך", "וירא", "חיי שרה", "תולדות", "ויצא", "וישלח", "וישב",
        "מקץ", "ויגש", "ויחי", "שמות", "וארא", "בא", "בשלח", "יתרו", "משפטים", "תרומה",
        "תצוה", "כי תשא", "ויקהל", "פקודי", "ויקרא", "צו", "שמיני", "תזריע", "מצורע", "אחרי מות",
        "קדושים", "אמור", "בהר", "בחקתי", "במדבר", "נשא", "בהעלתך", "שלח", "קרח", "חקת",
        "בלק", "פינחס", "מטות", "מסעי", "דברים", "ואתחנן", "עקב", "ראה", "שופטים", "כי תצא",
        "כי תבוא", "נצבים", "וילך", "האזינו",
    )).toMap()

    private val MASECHTOS = mapOf(
        "Berachos" to "ברכות", "Shabbos" to "שבת", "Eruvin" to "עירובין", "Pesachim" to "פסחים", "Shekalim" to "שקלים",
        "Yoma" to "יומא", "Sukkah" to "סוכה", "Beitzah" to "ביצה", "Rosh Hashanah" to "ראש השנה", "Taanis" to "תענית",
        "Megillah" to "מגילה", "Moed Katan" to "מועד קטן", "Chagigah" to "חגיגה", "Yevamos" to "יבמות", "Kesubos" to "כתובות",
        "Nedarim" to "נדרים", "Nazir" to "נזיר", "Sotah" to "סוטה", "Gittin" to "גיטין", "Kiddushin" to "קידושין",
        "Bava Kamma" to "בבא קמא", "Bava Metzia" to "בבא מציעא", "Bava Basra" to "בבא בתרא", "Sanhedrin" to "סנהדרין",
        "Makkos" to "מכות", "Shevuos" to "שבועות", "Avodah Zarah" to "עבודה זרה", "Horayos" to "הוריות", "Zevachim" to "זבחים",
        "Menachos" to "מנחות", "Chullin" to "חולין", "Bechoros" to "בכורות", "Arachin" to "ערכין", "Temurah" to "תמורה",
        "Kereisos" to "כריתות", "Meilah" to "מעילה", "Kinnim" to "קינים", "Tamid" to "תמיד", "Middos" to "מדות", "Niddah" to "נדה",
    )
}
