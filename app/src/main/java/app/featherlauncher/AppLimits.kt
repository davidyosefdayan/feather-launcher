package app.featherlauncher

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** How a limit keeps its apps closed while it's on. */
enum class Strictness(val label: String, val icon: Int) {
    /** The app doesn't open until the limit ends. */
    BLOCK("Block", R.drawable.ic_lock),
    /** Opening takes a wait, then the app is open for a few minutes. */
    WAIT("Wait", R.drawable.ic_hourglass),
}

/**
 * One limit: while it's on (every day or some days, all day or between two times), its [apps]
 * are locked. Opening one shows [LimitActivity] instead, with the [message]; with [Strictness.WAIT]
 * a wait there opens the app for a few minutes. Locked apps' sound stops, and with
 * [holdNotifications] their notifications wait until the limit ends.
 */
data class AppLimit(
    val id: Long,
    val name: String,
    val enabled: Boolean = true,
    /** Package names. */
    val apps: Set<String> = emptySet(),
    /** Minutes after midnight. It can run past midnight (start later than end); the same start and end is all day. */
    val start: Int = 0,
    val end: Int = 0,
    /** Days it runs on, as [Calendar] days of the week, counted by the day it starts. */
    val days: Set<Int> = AppLimits.ALL_DAYS,
    val strictness: Strictness = Strictness.WAIT,
    val waitSeconds: Int = 30,
    val openMinutes: Int = 5,
    /** Minutes after an app locks again before it can be opened again; 0 for none. */
    val cooldownMinutes: Int = 1,
    val holdNotifications: Boolean = false,
    val message: String = "",
) {
    val allDay get() = start == end

    /** Start of the stretch [now] falls in, or null when it isn't on. */
    fun runningSince(now: Long): Long? {
        if (!enabled) return null
        // The stretch that's running started today or, if it runs past midnight, yesterday.
        for (back in 0..1) {
            val begin = AppLimits.dayStart(now, -back) + start * MINUTE
            if (AppLimits.dayOf(begin) in days && now >= begin && now < begin + length() * MINUTE) return begin
        }
        return null
    }

    fun isOn(now: Long) = runningSince(now) != null

    /** When the stretch that's running ends, or null when it isn't on. */
    fun endsAt(now: Long): Long? = runningSince(now)?.let { it + length() * MINUTE }

    fun nextStart(now: Long): Long? = if (!enabled) null else
        (0..7).map { AppLimits.dayStart(now, it) + start * MINUTE }.firstOrNull { it > now && AppLimits.dayOf(it) in days }

    private fun length(): Int = (((end - start) + DAY) % DAY).takeIf { it > 0 } ?: DAY

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("apps", JSONArray(apps.toList()))
        .put("start", start).put("end", end).put("days", JSONArray(days.toList()))
        .put("strictness", strictness.name).put("wait", waitSeconds).put("open", openMinutes)
        .put("cooldown", cooldownMinutes).put("holdNotifications", holdNotifications).put("message", message)

    companion object {
        private const val MINUTE = 60_000L
        private const val DAY = 24 * 60

        fun fromJson(o: JSONObject): AppLimit? = runCatching {
            fun ints(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            AppLimit(o.getLong("id"), o.getString("name"), o.optBoolean("enabled", true), strings("apps").orEmpty(),
                o.optInt("start"), o.optInt("end"), ints("days") ?: AppLimits.ALL_DAYS,
                runCatching { Strictness.valueOf(o.getString("strictness")) }.getOrDefault(Strictness.WAIT),
                o.optInt("wait", 30), o.optInt("open", 5), o.optInt("cooldown", 1),
                o.optBoolean("holdNotifications"), o.optString("message"))
        }.getOrNull()
    }
}

/**
 * Every limit, and how changing them is guarded. One shared instance ([get]) so the launcher, the
 * settings page, the accessibility service and the notification listener all see the same limits
 * and unlocks as soon as they happen.
 */
class AppLimits private constructor(context: Context) {
    private val sp = context.getSharedPreferences("limits", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("limits_state", Context.MODE_PRIVATE)

    var limits: List<AppLimit> = read()
        private set

    private fun read(): List<AppLimit> {
        val array = runCatching { JSONArray(sp.getString("limits", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { AppLimit.fromJson(array.getJSONObject(it)) }
    }

    private fun save(next: List<AppLimit>) {
        limits = next
        sp.edit().putString("limits", JSONArray(next.map { it.toJson() }).toString()).apply()
        listeners.forEach { it() }
    }

    fun find(id: Long) = limits.firstOrNull { it.id == id }

    /** Adds [limit], or replaces the one with its id. */
    fun put(limit: AppLimit) =
        save(if (limits.any { it.id == limit.id }) limits.map { if (it.id == limit.id) limit else it } else limits + limit)

    fun remove(id: Long) = save(limits.filter { it.id != id })

    /** Every app any limit covers. */
    val allApps: Set<String> get() = limits.filter { it.enabled }.flatMap { it.apps }.toSet()

    /**
     * The limit keeping [pkg] closed at [now], or null when it can be used. Blocking beats waiting;
     * between waits, the longest wins.
     */
    fun lockFor(pkg: String, now: Long = System.currentTimeMillis()): AppLimit? =
        limits.filter { pkg in it.apps && it.isOn(now) }
            .filter { it.strictness == Strictness.BLOCK || openUntil(pkg) <= now }
            .maxWithOrNull(compareBy<AppLimit>({ it.strictness == Strictness.BLOCK }, { it.waitSeconds }))

    fun isLocked(pkg: String, now: Long = System.currentTimeMillis()) = lockFor(pkg, now) != null

    /** Until when [pkg]'s notifications wait, while a limit that holds them has it locked; null otherwise. */
    fun holdNotificationsUntil(pkg: String, now: Long = System.currentTimeMillis()): Long? {
        if (!isLocked(pkg, now)) return null
        return limits.filter { pkg in it.apps && it.holdNotifications }.mapNotNull { it.endsAt(now) }.maxOrNull()
    }

    /** Until when [pkg] was opened from the lock screen (0 if never). */
    fun openUntil(pkg: String): Long = state.getLong("open_$pkg", 0)

    /** When [pkg] can be opened again from the lock screen of [limit]. */
    fun readyAt(pkg: String, limit: AppLimit): Long = openUntil(pkg) + limit.cooldownMinutes * MINUTE

    fun open(pkg: String, limit: AppLimit, now: Long = System.currentTimeMillis()) {
        state.edit().putLong("open_$pkg", now + limit.openMinutes * MINUTE).apply()
        listeners.forEach { it() }
    }

    /** Called when limits change or an app is opened from the lock screen, so timers can be set again. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * The next moment anything about locking can change for [pkg] (null: any app): a limit
     * starting or ending, or an app's open time running out.
     */
    fun nextChange(now: Long = System.currentTimeMillis(), pkg: String? = null): Long? {
        val relevant = limits.filter { it.enabled && (pkg == null || pkg in it.apps) }
        if (relevant.isEmpty()) return null
        val times = ArrayList<Long>()
        relevant.forEach { l ->
            l.endsAt(now)?.let(times::add)
            l.nextStart(now)?.let(times::add)
        }
        (if (pkg != null) listOf(pkg) else allApps).forEach { p -> openUntil(p).takeIf { it > now }?.let(times::add) }
        return times.minOrNull()
    }

    // region Guarding changes

    /** What it takes to change limits, so they're harder to undo on a whim. */
    enum class Guard(val label: String) {
        NONE("Anytime"),
        WAIT("After a wait"),
        PIN("With a PIN"),
        RESTART("After restarting the phone"),
    }

    var guard: Guard
        get() = runCatching { Guard.valueOf(sp.getString("guard", null)!!) }.getOrDefault(Guard.NONE)
        set(value) = sp.edit().putString("guard", value.name).apply()

    /** How long the wait is, for [Guard.WAIT]. */
    var guardWaitSeconds: Int
        get() = sp.getInt("guard_wait", 5 * 60)
        set(value) = sp.edit().putInt("guard_wait", value).apply()

    /** The PIN's hash, for [Guard.PIN]. */
    var guardPin: String?
        get() = sp.getString("guard_pin", null)
        set(value) = sp.edit().putString("guard_pin", value).apply()

    /** Unlocked with the wait or the PIN until then (elapsed realtime), or until Settings closes. */
    private var unlockedUntil = 0L

    fun canChange(): Boolean = when (guard) {
        Guard.NONE -> true
        // Counted from when the phone started, which changing the clock can't move.
        Guard.RESTART -> SystemClock.elapsedRealtime() < RESTART_WINDOW
        else -> SystemClock.elapsedRealtime() < unlockedUntil
    }

    fun unlock() {
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_FOR
    }

    fun lockAgain() {
        unlockedUntil = 0
        waitStartedAt = 0
    }

    /** When the wait to unlock changes was started (elapsed realtime), or 0. Leaving Settings stops it. */
    var waitStartedAt = 0L

    // endregion

    companion object {
        const val MINUTE = 60_000L
        val ALL_DAYS = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        val SUN_THU = setOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY)
        /** After a restart, changes are open for this long. */
        const val RESTART_WINDOW = 10 * MINUTE
        /** After the wait or the PIN, changes are open for this long (or until Settings closes). */
        private const val UNLOCKED_FOR = 10 * MINUTE

        @Volatile private var instance: AppLimits? = null

        fun get(context: Context): AppLimits =
            instance ?: synchronized(this) { instance ?: AppLimits(context.applicationContext).also { instance = it } }

        /** Ready-made limits to start from: name, what it's for, and the limit. */
        fun templates(): List<Triple<String, String, AppLimit>> {
            val id = System.currentTimeMillis()
            return listOf(
                Triple("Bedtime", "Apps are blocked at night, and their notifications wait until morning",
                    AppLimit(id, "Bedtime", start = 23 * 60, end = 6 * 60, strictness = Strictness.BLOCK,
                        holdNotifications = true, message = "It's late. Is this worth staying up for?")),
                Triple("Focus", "Blocks apps Sunday to Thursday, 9 AM to 5 PM",
                    AppLimit(id, "Focus", start = 9 * 60, end = 17 * 60, days = SUN_THU, strictness = Strictness.BLOCK,
                        message = "Back to what you were doing.")),
                Triple("Slow down", "Wait 30 seconds to open an app for 10 minutes. Notifications are still delivered.",
                    AppLimit(id, "Slow down", strictness = Strictness.WAIT, waitSeconds = 30, openMinutes = 10,
                        message = "Do you want to open this right now?")),
                Triple("Custom", "Start from scratch",
                    AppLimit(id, "New limit", start = 22 * 60, end = 7 * 60)),
            )
        }

        internal fun dayStart(now: Long, plusDays: Int): Long = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, plusDays)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        internal fun dayOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.DAY_OF_WEEK)

        /** "1:00 AM", in the phone's 12 or 24 hour style. */
        fun formatTime(context: Context, minutes: Int): String {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
            }
            return TimeFormat.format(context, c.timeInMillis)
        }

        fun formatTime(context: Context, time: Long): String =
            TimeFormat.format(context, time)

        /** "Every day", "Sun–Thu", or the short day names. */
        fun formatDays(days: Set<Int>): String {
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            return when (days) {
                ALL_DAYS -> "Every day"
                emptySet<Int>() -> "Never"
                SUN_THU -> "Sun–Thu"
                else -> (Calendar.SUNDAY..Calendar.SATURDAY).filter { it in days }.joinToString(", ") { names[it] }
            }
        }

        /** "All day" or "11:00 PM–6:00 AM", then the days unless it's every day. */
        fun formatWhen(context: Context, limit: AppLimit): String {
            val hours = if (limit.allDay) "All day" else "${formatTime(context, limit.start)}–${formatTime(context, limit.end)}"
            return if (limit.days == ALL_DAYS) hours else "$hours · ${formatDays(limit.days)}"
        }

        fun formatDuration(seconds: Int): String = when {
            seconds == 0 -> "Off"
            seconds < 60 -> "$seconds sec"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "${seconds / 60} min ${seconds % 60} sec"
        }
    }
}
