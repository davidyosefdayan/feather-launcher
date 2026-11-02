package app.featherlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.concurrent.Executors

/**
 * Contacts for Smart Search, loaded in the background and kept in memory; loaded again only
 * once the contacts or the call history have changed.
 */
class Contacts(private val context: Context) {
    /**
     * A person, shown and called at [number]: their primary number, or the one a search
     * matched. [numbers] has all of them. [email] is their main email address, if they have one.
     */
    class Contact(
        val id: Long,
        val name: String,
        val number: String,
        val numbers: List<String>,
        val photo: Uri?,
        val starred: Boolean,
        val email: String? = null,
    ) {
        val words = Search.words(name)
        val digitWords = words.map(Search::toDigits)

        /** The number whose digits contain [digits], if any. */
        fun numberMatching(digits: String) = numbers.firstOrNull { n -> digits in n.filter { it.isDigit() } }

        fun withNumber(n: String) = Contact(id, name, n, numbers, photo, starred, email)
    }

    var all: List<Contact> = emptyList()
        private set
    /** Calls with each person (by id) in the recent call history: how many, and when the last was. */
    var calls: Map<Long, Pair<Int, Long>> = emptyMap()
        private set
    var onLoaded: (() -> Unit)? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    /** Contacts or calls changed since they were last read (or they never were). */
    @Volatile private var stale = true
    private var watching = false

    val allowed get() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load() {
        if (!allowed || loading || !stale) return
        loading = true
        stale = false
        watch()
        io.execute {
            // Every number per person, the primary one first.
            val numbers = LinkedHashMap<Long, MutableList<String>>()
            val details = HashMap<Long, Triple<String, Uri?, Boolean>>()
            runCatching {
                context.contentResolver.query(
                    Phone.CONTENT_URI,
                    arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED, Phone.IS_SUPER_PRIMARY),
                    null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val number = c.getString(2) ?: continue
                        val list = numbers.getOrPut(id) { ArrayList() }
                        val digits = number.filter { it.isDigit() }
                        // The same number saved twice (with and without +1, say) counts once.
                        if (list.any { it.filter(Char::isDigit).let { d -> d.endsWith(digits) || digits.endsWith(d) } }) continue
                        if (c.getInt(5) == 1) list.add(0, number) else list.add(number)
                        details.getOrPut(id) { Triple(name, c.getString(3)?.let(Uri::parse), c.getInt(4) == 1) }
                    }
                }
            }
            // One email per person, the primary one if it's marked.
            val emails = HashMap<Long, String>()
            runCatching {
                context.contentResolver.query(
                    Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.ADDRESS, Email.IS_SUPER_PRIMARY), null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val address = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val id = c.getLong(0)
                        if (id !in emails || c.getInt(2) == 1) emails[id] = address
                    }
                }
            }
            // The last few hundred calls, in or out, matched to people by their numbers' last digits.
            val byNumber = HashMap<String, Long>()
            numbers.forEach { (id, list) -> list.forEach { byNumber[key(it)] = id } }
            val calls = HashMap<Long, Pair<Int, Long>>()
            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC",
                )?.use { c ->
                    var read = 0
                    while (c.moveToNext() && read++ < CALLS_READ) {
                        val id = byNumber[key(c.getString(0) ?: continue)] ?: continue
                        val (count, last) = calls[id] ?: (0 to c.getLong(1))
                        calls[id] = count + 1 to last
                    }
                }
            }
            // Made here, not on the main thread: each works out its words for searching as it's made.
            val next = numbers.map { (id, list) ->
                val (name, photo, starred) = details.getValue(id)
                Contact(id, name, list.first(), list, photo, starred, emails[id])
            }
            val before = all
            val changed = next.map { Triple(it.id, it.numbers, it.email) } != before.map { Triple(it.id, it.numbers, it.email) }
            main.post {
                this.calls = calls
                all = next
                loading = false
                if (changed) onLoaded?.invoke()
            }
        }
    }

    /** Watches the contacts and the call history, so a change is read the next time the home screen shows. */
    private fun watch() {
        if (watching) return
        watching = true
        val observer = object : android.database.ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { stale = true }
        }
        runCatching { context.contentResolver.registerContentObserver(android.provider.ContactsContract.AUTHORITY_URI, true, observer) }
        runCatching { context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }
    }

    /** Matches by name (letters or T9) and, for digits, by phone number too. */
    fun search(raw: String): List<Contact> {
        val t9 = Search.isT9(raw)
        val query = if (t9) raw else Search.normalizeText(raw)
        return all.mapNotNull { c ->
            Search.score(query, if (t9) c.digitWords else c.words)?.let { return@mapNotNull c to it }
            // Typed digits that are part of one of their numbers: shown with that number.
            val number = if (t9 && raw.length >= 3) c.numberMatching(raw) else null
            number?.let { c.withNumber(it) to 4 }
        }.sortedWith(compareBy<Pair<Contact, Int>> { it.second }.thenBy { !it.first.starred }).map { it.first }
    }

    val favorites get() = all.filter { it.starred }

    /** The same number saved or dialed with or without a country code gives the same key. */
    private fun key(number: String) = number.filter { it.isDigit() }.takeLast(9)

    private companion object {
        const val CALLS_READ = 500
    }
}
