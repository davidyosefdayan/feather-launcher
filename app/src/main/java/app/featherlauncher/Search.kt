package app.featherlauncher

import java.text.Normalizer

/**
 * App search. A query of only digits is read as T9 (2 = abc, 3 = def, ...);
 * anything else is matched as plain text. Both match against the start of the
 * label, the start of any word, word initials, and finally anywhere.
 */
object Search {
    private const val LETTER_DIGITS = "22233344455566677778889999"
    private val MARKS = Regex("\\p{Mn}+")

    /** Lowercase words of a label, also splitting camel case ("WhatsApp" → whats, app). */
    fun words(label: String): List<String> {
        val plain = MARKS.replace(Normalizer.normalize(label, Normalizer.Form.NFD), "")
        val words = ArrayList<String>()
        val current = StringBuilder()
        var prev = ' '
        for (c in plain) {
            if (!c.isLetterOrDigit()) {
                if (current.isNotEmpty()) words += current.toString().also { current.clear() }
            } else {
                if (c.isUpperCase() && prev.isLowerCase() && current.isNotEmpty()) {
                    words += current.toString().also { current.clear() }
                }
                current.append(c.lowercaseChar())
            }
            prev = c
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    fun toDigits(word: String): String = buildString(word.length) {
        for (c in word) {
            append(
                when (c) {
                    in 'a'..'z' -> LETTER_DIGITS[c - 'a']
                    in '0'..'9' -> c
                    else -> '_' // Non-Latin letters have no keypad digit, so they never match T9.
                }
            )
        }
    }

    fun isT9(query: String) = query.isNotEmpty() && query.all { it in '0'..'9' }

    fun normalizeText(query: String) = words(query).joinToString("")

    /**
     * Lower is better; null means no match. Every name is scored on every key press, so this
     * looks through the words where they are rather than joining them into new strings.
     */
    fun score(query: String, words: List<String>): Int? {
        if (query.isEmpty()) return 0
        if (joinedMatches(words, query, 0, 0)) return 0
        for (i in 1 until words.size) if (words[i].startsWith(query)) return 1
        if (query.length > 1 && initialsStartWith(words, query)) return 2
        for (w in words.indices) for (c in words[w].indices) if (joinedMatches(words, query, w, c)) return 3
        return null
    }

    /** Whether the words run together are exactly [query]. */
    fun joinedEquals(words: List<String>, query: String): Boolean =
        words.sumOf { it.length } == query.length && joinedMatches(words, query, 0, 0)

    /** Whether the words run together, from letter [char] of word [word] on, start with [query]. */
    private fun joinedMatches(words: List<String>, query: String, word: Int, char: Int): Boolean {
        var w = word
        var c = char
        for (q in query) {
            while (w < words.size && c >= words[w].length) { w++; c = 0 }
            if (w >= words.size || words[w][c] != q) return false
            c++
        }
        return true
    }

    /** Whether the words' first letters, run together, start with [query]. */
    private fun initialsStartWith(words: List<String>, query: String): Boolean {
        var i = 0
        for (w in words) {
            if (w.isEmpty()) continue
            if (i == query.length) return true
            if (w[0] != query[i]) return false
            i++
        }
        return i == query.length
    }
}

/** The kinds of Smart Search results, each its own section, in the order they're shown. */
enum class SearchSection(val title: String, val icon: Int) {
    TOP("Top match", R.drawable.ic_cat_star),
    CALL("Call", R.drawable.ic_phone_call),
    CONTACTS("Contacts", R.drawable.ic_contact),
    APPS("Apps", R.drawable.ic_cat_apps),
    SHORTCUTS("Shortcuts", R.drawable.ic_set_link),
    SETTINGS("Settings", R.drawable.ic_settings),
    FILES("Files", R.drawable.ic_folder),
    WEB("Web", R.drawable.ic_search),
}
