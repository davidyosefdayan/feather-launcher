package app.featherlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** What the clock shows under the time: the dates, the next zman and the next event. */
class ClockInfo(private val context: Context) {
    /** Where zmanim and the calendar are worked out for, in its own time zone. [area] is "New York, United States". */
    class Place(val name: String, val lat: Double, val lon: Double, val zone: TimeZone = TimeZone.getDefault(), val area: String? = null) {
        /** In Eretz Yisrael: one day of Yom Tov, and the parsha read there. */
        val inIsrael get() = zone.id in ISRAEL_ZONES

        fun toJson(): String = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("zone", zone.id).put("area", area).toString()

        companion object {
            private val ISRAEL_ZONES = setOf("Asia/Jerusalem", "Asia/Tel_Aviv", "Asia/Hebron")

            fun fromJson(json: String): Place? = runCatching {
                val o = JSONObject(json)
                Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), TimeZone.getTimeZone(o.getString("zone")),
                    o.optString("area").ifEmpty { null })
            }.getOrNull()
        }
    }
    /** [color] is its calendar's, as the calendar app shows it. */
    class Event(val id: Long, val title: String, val begin: Long, val allDay: Boolean = false, val end: Long = begin, val color: Int = 0,
                val location: String? = null)

    private val prefs = Prefs(context)

    /**
     * The chosen city, else where the phone is (or last was); null if neither is known. Where the
     * phone is goes by its town's name once that's been looked up; [named] runs on the main thread
     * when a new name arrives.
     */
    fun place(named: (() -> Unit)? = null): Place? {
        prefs.chosenPlace?.let { return it }
        // Asked once a minute at most: every widget wants it on every refresh.
        val now = System.currentTimeMillis()
        if (now - lookedAt !in 0 until LOOK_AGAIN &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lookedAt = now
            val lm = context.getSystemService(LocationManager::class.java)
            val fix = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            val at = fix?.let { it.latitude.toFloat().toDouble() to it.longitude.toFloat().toDouble() }
            if (at != null && at != prefs.lastPlace) prefs.lastPlace = at
        }
        val (lat, lon) = prefs.lastPlace ?: return null
        // A name looked up a little way off still stands while the new one is found.
        val known = prefs.lastPlaceName?.takeIf { (_, at) -> metres(at, lat, lon) < NAME_KEPT }
        if (known == null || metres(known.second, lat, lon) > NAME_FRESH) lookUp(lat, lon, named)
        return Place(known?.first ?: "Current location", lat, lon)
    }

    /**
     * Finds the name of the town at [lat], [lon] in the background: the phone's geocoder, else
     * OpenStreetMap's (for phones without one).
     */
    private fun lookUp(lat: Double, lon: Double, named: (() -> Unit)?) {
        if (looking || System.currentTimeMillis() - failedAt < RETRY) return
        looking = true
        Thread {
            val name = geocoder(lat, lon) ?: osm(lat, lon)
            looking = false
            if (name == null) {
                failedAt = System.currentTimeMillis()
                return@Thread
            }
            prefs.lastPlaceName = name to (lat to lon)
            named?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }.start()
    }

    @Suppress("DEPRECATION") // The listener version only adds a thread; this already runs on one.
    private fun geocoder(lat: Double, lon: Double): String? = if (!Geocoder.isPresent()) null else runCatching {
        Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            // New York gives no town, only the borough ("Brooklyn") and its county ("Kings County").
            ?.let { it.locality ?: it.subLocality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()

    private fun osm(lat: Double, lon: Double): String? = runCatching {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=$lat&lon=$lon&accept-language=" +
            Locale.getDefault().toLanguageTag()
        val text = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            // OpenStreetMap asks every app to name itself.
            setRequestProperty("User-Agent", "FeatherLauncher (https://github.com/davidyosefdayan/feather-launcher)")
            inputStream.bufferedReader().use { it.readText() }
        }
        val o = JSONObject(text)
        val address = o.optJSONObject("address")
        listOf("city", "town", "village", "municipality").firstNotNullOfOrNull { address?.optString(it)?.ifEmpty { null } }
            ?: o.optString("name").ifEmpty { null }
    }.getOrNull()

    private fun metres(at: Pair<Double, Double>, lat: Double, lon: Double) =
        FloatArray(1).also { Location.distanceBetween(at.first, at.second, lat, lon, it) }[0]

    /** [zman]'s name to show: in Hebrew with Show in Hebrew on, kept whole and to the left in an English line. */
    fun zmanName(zman: Zman) =
        if (prefs.secondCalendar == SecondCalendar.HEBREW && prefs.hebrewInHebrew) HebrewText.isolate(HebrewText.zman(zman.name)) else zman.name

    fun zmanim(day: Long, place: Place) = Zmanim.forDay(day, place, prefs.zmanMagenAvraham, prefs.candleMinutes, sunOnly = !prefs.dailyZmanim)

    fun nextZman(now: Long, place: Place) = Zmanim.next(now, place, prefs.zmanMagenAvraham, prefs.candleMinutes, sunOnly = !prefs.dailyZmanim)

    /** "Mon, October 26 · 25 Tishrei", with the second calendar's date if one is chosen. */
    fun dateLine(now: Long, place: Place?): String {
        val date = android.text.format.DateFormat.format("EEE, MMMM d", now).toString()
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        return listOfNotNull(date, prefs.secondCalendar.format(now, sunset)).joinToString(" · ")
    }

    /** Events from [from] to [to], soonest first, all-day ones included; empty without calendar access. */
    fun events(from: Long, to: Long, limit: Int): List<Event> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        // As the calendar would answer: the ones that overlap the span, all-day ones first.
        return instances(from, to).filter { it.end > from && it.begin <= to }
            .sortedWith(compareByDescending<Event> { it.allDay }.thenBy { it.begin })
            .take(limit)
            .map { Event(it.id, it.title, it.begin, it.allDay, it.end, it.color) }
    }

    /** The next timed event in the coming day, if calendar access was given. */
    fun nextEvent(now: Long): Event? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        return instances(now, now + DAY).filter { !it.allDay && it.begin >= now && it.begin <= now + DAY }.minByOrNull { it.begin }
            ?.let { Event(it.id, it.title, it.begin, end = it.end, location = it.location) }
    }

    /**
     * Every event from today to a little over two weeks ahead, read from the calendar once and
     * kept until it changes (or [from] to [to] goes past what was read), instead of asking the
     * calendar again for every widget every minute.
     */
    private fun instances(from: Long, to: Long): List<Event> {
        watchCalendar(context.applicationContext)
        kept?.let { (start, end, list) -> if (from >= start && to <= end) return list }
        val start = from - DAY
        val end = from + KEPT_DAYS * DAY
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val list = runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.EVENT_LOCATION),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val list = ArrayList<Event>()
                while (c.moveToNext()) {
                    list += Event(c.getLong(0), c.getString(1).orEmpty().ifEmpty { "Event" }, c.getLong(2), c.getInt(4) == 1, c.getLong(3), c.getInt(5),
                        c.getString(6)?.trim()?.ifEmpty { null })
                }
                list
            }
        }.getOrNull() ?: return emptyList()
        kept = Triple(start, end, list)
        return list
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        /** Moving further than this looks the name up again; further than [NAME_KEPT], the old one is dropped. */
        private const val NAME_FRESH = 1_000f
        private const val NAME_KEPT = 5_000f
        private const val RETRY = 10 * 60_000L

        @Volatile private var looking = false
        @Volatile private var failedAt = 0L

        /** When the phone's position was last asked for; [place] asks again after [LOOK_AGAIN]. */
        @Volatile private var lookedAt = 0L
        private const val LOOK_AGAIN = 60_000L

        /** The events read last (see [instances]): from, to, and what's in between. */
        @Volatile private var kept: Triple<Long, Long, List<Event>>? = null
        private const val KEPT_DAYS = 16
        private var watching = false

        /** Anything changed in the calendar (an event added, a sync) is read again next time. */
        private fun watchCalendar(context: Context) {
            if (watching) return
            watching = true
            runCatching {
                context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true,
                    object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) { kept = null }
                    })
            }
            // A new time zone moves every event's time.
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) { kept = null }
            }, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
            })
        }

        /**
         * Places matching [query]: Jewish communities first (from [COMMUNITIES], so they're found
         * however they're spelled, even offline), then Open-Meteo's free place search (no account),
         * with their time zones. Runs on the calling thread; null if nothing was found and the
         * search couldn't be reached.
         */
        fun search(query: String): List<Place>? {
            val key = simplify(query)
            val known = COMMUNITIES.filter { (place, names) -> (names + place.name).any { simplify(it).startsWith(key) || key.length > 3 && simplify(it).contains(key) } }
                .map { it.first }
            val found = searchOnline(query)
            if (found == null && known.isEmpty()) return null
            // The same town from both lists shows once.
            return known + found.orEmpty().filter { f -> known.none { k -> k.name == f.name && abs(k.lat - f.lat) < 0.2 && abs(k.lon - f.lon) < 0.2 } }
        }

        private fun simplify(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

        private fun searchOnline(query: String): List<Place>? = runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" +
                java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val text = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 15_000
                inputStream.bufferedReader().use { it.readText() }
            }
            val results = JSONObject(text).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).map { i ->
                val o = results.getJSONObject(i)
                val area = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotEmpty() && it != o.getString("name") }
                Place(o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"),
                    TimeZone.getTimeZone(o.optString("timezone").ifEmpty { "UTC" }), area.joinToString(", ").ifEmpty { null })
            }
        }.getOrNull()

        private fun il(name: String, lat: Double, lon: Double, vararg also: String) =
            Place(name, lat, lon, TimeZone.getTimeZone("Asia/Jerusalem"), "Israel") to also.toList()

        private fun at(name: String, lat: Double, lon: Double, zone: String, area: String, vararg also: String) =
            Place(name, lat, lon, TimeZone.getTimeZone(zone), area) to also.toList()

        /** Jewish communities, with the other ways their names are written. */
        val COMMUNITIES: List<Pair<Place, List<String>>> = listOf(
            il("Jerusalem", 31.7683, 35.2137, "Yerushalayim", "Yerushalaim"),
            il("Bnei Brak", 32.0807, 34.8338, "Bene Beraq", "Bnai Brak"),
            il("Beitar Illit", 31.6969, 35.1153, "Betar Illit", "Beitar Ilit", "Betar"),
            il("Modiin Illit", 31.9330, 35.0410, "Kiryat Sefer", "Kiryas Sefer", "Modi'in Illit"),
            il("Beit Shemesh", 31.7470, 34.9881, "Bet Shemesh", "Ramat Beit Shemesh", "RBS"),
            il("Elad", 32.0522, 34.9511, "El'ad"),
            il("Tzfat", 32.9646, 35.4960, "Safed", "Tsfat", "Zefat"),
            il("Haifa", 32.7940, 34.9896),
            il("Netanya", 32.3215, 34.8532),
            il("Petah Tikva", 32.0840, 34.8878, "Petach Tikva"),
            il("Ashdod", 31.8014, 34.6435),
            at("Brooklyn", 40.6782, -73.9442, "America/New_York", "New York, United States"),
            at("Boro Park", 40.6340, -73.9910, "America/New_York", "Brooklyn, New York", "Borough Park"),
            at("Williamsburg", 40.7081, -73.9571, "America/New_York", "Brooklyn, New York"),
            at("Flatbush", 40.6409, -73.9624, "America/New_York", "Brooklyn, New York", "Midwood"),
            at("Crown Heights", 40.6694, -73.9422, "America/New_York", "Brooklyn, New York"),
            at("Kew Gardens Hills", 40.7300, -73.8230, "America/New_York", "Queens, New York", "Queens"),
            at("Monsey", 41.1112, -74.0685, "America/New_York", "New York, United States"),
            at("Kiryas Joel", 41.3420, -74.1677, "America/New_York", "New York, United States", "Kiryas Yoel", "Monroe"),
            at("New Square", 41.1395, -74.0293, "America/New_York", "New York, United States", "Skver"),
            at("Lakewood", 40.0821, -74.2097, "America/New_York", "New Jersey, United States"),
            at("Jackson", 40.0979, -74.3577, "America/New_York", "New Jersey, United States"),
            at("Toms River", 39.9537, -74.1979, "America/New_York", "New Jersey, United States"),
            at("Passaic", 40.8568, -74.1285, "America/New_York", "New Jersey, United States"),
            at("Teaneck", 40.8932, -74.0116, "America/New_York", "New Jersey, United States"),
            at("Five Towns", 40.6290, -73.7271, "America/New_York", "New York, United States", "Lawrence", "Cedarhurst", "Woodmere"),
            at("Far Rockaway", 40.6054, -73.7551, "America/New_York", "New York, United States"),
            at("Baltimore", 39.2904, -76.6122, "America/New_York", "Maryland, United States"),
            at("Philadelphia", 39.9526, -75.1652, "America/New_York", "Pennsylvania, United States"),
            at("Boston", 42.3601, -71.0589, "America/New_York", "Massachusetts, United States"),
            at("Cleveland", 41.4993, -81.6944, "America/New_York", "Ohio, United States"),
            at("Miami Beach", 25.7907, -80.1300, "America/New_York", "Florida, United States"),
            at("Chicago", 41.8781, -87.6298, "America/Chicago", "Illinois, United States"),
            at("Los Angeles", 34.0522, -118.2437, "America/Los_Angeles", "California, United States"),
            at("Toronto", 43.6532, -79.3832, "America/Toronto", "Ontario, Canada"),
            at("Montreal", 45.5019, -73.5674, "America/Toronto", "Quebec, Canada"),
            at("Stamford Hill", 51.5700, -0.0730, "Europe/London", "London, United Kingdom"),
            at("Golders Green", 51.5724, -0.1941, "Europe/London", "London, United Kingdom"),
            at("Manchester", 53.4808, -2.2426, "Europe/London", "England, United Kingdom"),
            at("Gateshead", 54.9527, -1.6034, "Europe/London", "England, United Kingdom"),
            at("Antwerp", 51.2194, 4.4025, "Europe/Brussels", "Belgium", "Antwerpen"),
            at("Paris", 48.8566, 2.3522, "Europe/Paris", "France"),
            at("Zurich", 47.3769, 8.5417, "Europe/Zurich", "Switzerland"),
            at("Johannesburg", -26.2041, 28.0473, "Africa/Johannesburg", "South Africa"),
            at("Melbourne", -37.8136, 144.9631, "Australia/Melbourne", "Australia"),
            at("Sydney", -33.8688, 151.2093, "Australia/Sydney", "Australia"),
            at("Buenos Aires", -34.6037, -58.3816, "America/Argentina/Buenos_Aires", "Argentina"),
            at("Mexico City", 19.4326, -99.1332, "America/Mexico_City", "Mexico"),
        )
    }
}
