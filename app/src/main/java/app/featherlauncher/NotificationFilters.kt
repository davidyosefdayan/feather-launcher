package app.featherlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One notification filter. For [app] (a package name, or null for every app), notifications
 * that mention any of [words] are hidden; with [allowOnly], everything except those is hidden.
 * No words means every notification from the app. With [during], only while that app limit is on.
 */
data class NotificationFilter(
    val id: Long,
    val app: String?,
    val words: List<String>,
    val allowOnly: Boolean = false,
    /** An app limit's id. */
    val during: Long? = null,
    val enabled: Boolean = true,
) {
    /** Whether a notification from [pkg] saying [text] should be hidden, with the limits [on] right now. */
    fun hides(pkg: String, text: String, on: Set<Long>): Boolean {
        if (!enabled || (app != null && app != pkg) || (during != null && during !in on)) return false
        // No words: everything from the app.
        if (words.isEmpty()) return true
        val mentioned = words.indices.any { i ->
            val word = words[i]
            if (isPattern(word)) patterns[i]?.containsMatchIn(text) == true else text.contains(word, ignoreCase = true)
        }
        return if (allowOnly) !mentioned else mentioned
    }

    /** A word written /like this/ is a pattern, as in NotiFilter; anything else is plain text. */
    private fun isPattern(word: String) = word.length > 2 && word.startsWith('/') && word.endsWith('/')

    /** The patterns among [words], made once rather than for every notification; null for plain words and broken patterns. */
    private val patterns by lazy {
        words.map { word -> if (isPattern(word)) runCatching { Regex(word.substring(1, word.length - 1), RegexOption.IGNORE_CASE) }.getOrNull() else null }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("app", app).put("words", JSONArray(words))
        .put("allowOnly", allowOnly).put("during", during ?: 0).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): NotificationFilter? = runCatching {
            val words = o.getJSONArray("words")
            NotificationFilter(
                o.getLong("id"), o.optString("app").ifEmpty { null },
                (0 until words.length()).map { words.getString(it) },
                o.optBoolean("allowOnly"), o.optLong("during").takeIf { it != 0L }, o.optBoolean("enabled", true),
            )
        }.getOrNull()

        /** Turns "sale, promo code" into its words, ignoring blanks. */
        fun parseWords(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** A notification that was hidden, kept so it can still be read. */
data class HiddenNotification(val app: String, val title: String, val text: String, val time: Long, val why: String) {
    fun toJson(): JSONObject = JSONObject().put("app", app).put("title", title).put("text", text).put("time", time).put("why", why)

    companion object {
        fun fromJson(o: JSONObject) = HiddenNotification(
            o.getString("app"), o.optString("title"), o.optString("text"), o.getLong("time"), o.optString("why"),
        )
    }
}

/** The saved filters and the list of what they hid, shared by settings and the listener. */
class NotificationFilters private constructor(context: Context) {
    private val sp = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    var filters: List<NotificationFilter> = readFilters()
        private set

    private fun readFilters(): List<NotificationFilter> {
        val array = runCatching { JSONArray(sp.getString("filters", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { NotificationFilter.fromJson(array.getJSONObject(it)) }
    }

    private fun saveFilters(next: List<NotificationFilter>) {
        filters = next
        sp.edit().putString("filters", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun put(filter: NotificationFilter) =
        saveFilters(if (filters.any { it.id == filter.id }) filters.map { if (it.id == filter.id) filter else it } else filters + filter)

    fun remove(id: Long) = saveFilters(filters.filter { it.id != id })

    /** Newest first. Read once, then kept up in memory as notifications are hidden. */
    val history: List<HiddenNotification>
        get() = historyList ?: run {
            val array = runCatching { JSONArray(sp.getString("history", "[]")) }.getOrDefault(JSONArray())
            (0 until array.length()).map { HiddenNotification.fromJson(array.getJSONObject(it)) }.also { historyList = it }
        }
    @Volatile private var historyList: List<HiddenNotification>? = null

    fun remember(hidden: HiddenNotification) {
        val next = (listOf(hidden) + history).take(HISTORY_SIZE)
        historyList = next
        sp.edit().putString("history", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun clearHistory() {
        historyList = emptyList()
        sp.edit().remove("history").apply()
    }

    companion object {
        private const val HISTORY_SIZE = 100


        @Volatile private var instance: NotificationFilters? = null

        fun get(context: Context): NotificationFilters =
            instance ?: synchronized(this) { instance ?: NotificationFilters(context.applicationContext).also { instance = it } }
    }
}
