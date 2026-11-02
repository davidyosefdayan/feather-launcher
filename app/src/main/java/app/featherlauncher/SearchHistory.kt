package app.featherlauncher

import android.content.Context
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * What was picked in Smart Search, and what had been typed when it was: so the same start finds
 * it first next time ("chr" → Chrome). Only results' ids and the typed text are kept, on the
 * phone, for the last [LIMIT] picks.
 */
class SearchHistory private constructor(context: Context) {
    private class Pick(val typed: String, val id: String, val time: Long)

    private val store = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private var picks: List<Pick> = store.getString("picks", null).orEmpty().lines().mapNotNull { line ->
        val (time, id, typed) = line.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
        Pick(typed, id, time.toLongOrNull() ?: return@mapNotNull null)
    }

    /** [id] was picked after typing [raw] (empty when picked from the suggestions). */
    fun record(raw: String, id: String) {
        val typed = if (Search.isT9(raw)) raw else Search.normalizeText(raw)
        picks = (picks + Pick(typed, id, System.currentTimeMillis())).takeLast(LIMIT)
        store.edit().putString("picks", picks.joinToString("\n") { "${it.time}\t${it.id}\t${it.typed}" }).apply()
    }

    fun clear() {
        picks = emptyList()
        store.edit().clear().apply()
    }

    val isEmpty get() = picks.isEmpty()

    /**
     * Each result's standing for the typed [raw], from 0 up: how well its name matches, what was
     * picked before after typing much the same, and how much and how lately it's been used.
     */
    fun ranker(raw: String): Ranker {
        val t9 = Search.isT9(raw)
        val query = if (t9) raw else Search.normalizeText(raw)
        val now = System.currentTimeMillis()
        val learned = HashMap<String, Double>()
        val uses = HashMap<String, Int>()
        val lastUse = HashMap<String, Long>()
        picks.forEach { pick ->
            uses[pick.id] = (uses[pick.id] ?: 0) + 1
            lastUse[pick.id] = pick.time
            // Typed letters count for a T9 search by their keys; typed digits never match letters.
            val typed = if (t9) Search.toDigits(pick.typed) else pick.typed.takeUnless { Search.isT9(it) } ?: return@forEach
            if (query.isEmpty() || typed.isEmpty()) return@forEach
            // Picked after typing more ("chr" when now it's "c") counts for a share; after typing less, fully.
            val share = when {
                query.startsWith(typed) -> 1.0
                typed.startsWith(query) -> query.length.toDouble() / typed.length
                else -> return@forEach
            }
            learned[pick.id] = (learned[pick.id] ?: 0.0) + share * 0.5.pow(days(now - pick.time) / 30)
        }
        return Ranker(query, t9, now, learned, uses, lastUse)
    }

    class Ranker internal constructor(
        private val query: String,
        private val t9: Boolean,
        private val now: Long,
        private val learned: Map<String, Double>,
        private val uses: Map<String, Int>,
        private val lastUse: Map<String, Long>,
    ) {
        /** Points for picking [id] after typing much the same before; 0 if never. */
        fun learned(id: String) = LEARNED * min(learned[id] ?: 0.0, 3.0)

        /**
         * [id] named [words] (letters, or digits for T9): null if the name doesn't match, unless
         * it matched another way ([elsewhere]: a contact's number, a file's name in the middle).
         * [launches] counts opening it (or calling them) from anywhere, [lastUsed] the latest of
         * those if known; [starred] is a favorite contact.
         */
        fun points(
            id: String, words: List<String>, elsewhere: Boolean = false,
            launches: Int = 0, lastUsed: Long = 0, starred: Boolean = false,
        ): Double? {
            val name = when {
                Search.joinedEquals(words, query) -> 100.0
                else -> when (Search.score(query, words)) {
                    0 -> 60.0
                    1 -> 40.0
                    2 -> 25.0
                    3 -> 10.0
                    else -> if (elsewhere) 10.0 else return null
                }
            }
            val used = min(20.0, 6 * ln(1.0 + launches + (uses[id] ?: 0)))
            val lately = maxOf(lastUse[id] ?: 0, lastUsed).takeIf { it > 0 }?.let { 10 * 0.5.pow(days(now - it) / 7) } ?: 0.0
            return name + learned(id) + used + lately + if (starred) 5.0 else 0.0
        }

        fun wordsOf(title: String) = Search.words(title).let { if (t9) it.map(Search::toDigits) else it }

        /**
         * Whether [id], with [points], is clearly ahead of the next best result's [next]. A single
         * letter matches too much to go on names alone; it needs [id] picked after it before.
         */
        fun clearlyAhead(id: String, points: Double, next: Double) =
            points >= 60 && points - next >= 25 && (query.length > 1 || learned(id) >= LEARNED)
    }

    companion object {
        private const val LIMIT = 500
        /** Points for one recent pick after typing the same; up to three count. */
        private const val LEARNED = 35.0

        private fun days(ms: Long) = ms / 86_400_000.0

        @Volatile private var instance: SearchHistory? = null

        fun get(context: Context): SearchHistory =
            instance ?: synchronized(this) { instance ?: SearchHistory(context.applicationContext).also { instance = it } }
    }
}
