package app.featherlauncher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Sees every notification as it arrives. Ones a filter matches are dismissed and kept in the
 * history; ones from apps an app limit has locked are held back until it ends, if it holds them. What's left is
 * counted for the badges on app icons.
 */
class NotificationService : NotificationListenerService() {
    private var media: MediaGuard? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * What's showing, by key, kept up as notifications come and go: asking Android for all of
     * them on every one copied every notification over, on the thread that handles key presses.
     */
    private val showing = LinkedHashMap<String, StatusBarNotification>()
    private val refilterNow = Runnable { reread() }

    /** Starts again from what Android says is showing. */
    private fun reread() {
        val active = runCatching { activeNotifications }.getOrNull()
        active?.forEach(::filter)
        showing.clear()
        active?.forEach { showing[it.key] = it }
        recount()
    }

    override fun onListenerConnected() {
        running = this
        media = MediaGuard(this).also { it.start() }
        // Ones already showing when access was given (or the service restarted) are filtered too.
        // Just after the phone starts, apps are still putting theirs up (some before this
        // connects, unseen), so it looks again a few times.
        reread()
        listOf(5_000L, 30_000L, 120_000L).forEach { main.postDelayed(refilterNow, it) }
    }

    override fun onListenerDisconnected() {
        running = null
        main.removeCallbacks(refilterNow)
        media?.stop()
        media = null
        showing.clear()
        NotificationBadges.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        showing[sbn.key] = sbn
        filter(sbn)
        recount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        showing.remove(sbn.key)
        recount()
    }

    // Someone turned an app's notification dots on or off.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = recount()

    private fun recount() = NotificationBadges.update(this, showing.values)

    private fun filter(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        val limits = AppLimits.get(this)
        val filters = NotificationFilters.get(this)
        val now = System.currentTimeMillis()

        // From an app a limit has locked, held until the limit ends, if it holds them. Calls,
        // music and other ongoing ones are left to MediaGuard.
        val until = if (sbn.isOngoing) null else limits.holdNotificationsUntil(sbn.packageName, now)
        if (until != null) {
            // Snoozed, not dismissed: it comes back by itself when the limit ends.
            snoozeNotification(sbn.key, until - now)
            filters.remember(HiddenNotification(sbn.packageName, title, text, now, "Held until ${AppLimits.formatTime(this, until)}"))
            return
        }

        val on = limits.limits.filter { it.isOn(now) }.map { it.id }.toSet()
        val filter = filters.filters.firstOrNull { it.hides(sbn.packageName, "$title\n$text", on) } ?: return
        if (sbn.isClearable) {
            cancelNotification(sbn.key)
        } else {
            // Ones that can't be dismissed (an app's permanent notification) are snoozed instead;
            // when one comes back, it's filtered again.
            snoozeNotification(sbn.key, PERMANENT_SNOOZE)
        }
        // A permanent notification coming back every few hours would fill the history.
        if (filters.history.firstOrNull()?.let { it.app == sbn.packageName && it.title == title && it.text == text } != true) {
            filters.remember(HiddenNotification(sbn.packageName, title, text, now, describe(filter)))
        }
    }

    private fun describe(filter: NotificationFilter) = when {
        filter.words.isEmpty() -> "All from this app"
        filter.allowOnly -> "Didn't mention ${filter.words.joinToString(", ")}"
        else -> "Mentioned ${filter.words.joinToString(", ")}"
    }

    companion object {
        private const val PERMANENT_SNOOZE = 5 * 60 * 60 * 1000L

        private var running: NotificationService? = null

        /** Who has a notification showing right now (empty without notification access). */
        fun showingFrom(): List<String> = runCatching { running?.activeNotifications?.map { it.packageName } }.getOrNull().orEmpty()

        /** Filters again what's showing, after a filter is added or changed. */
        fun refilter() {
            val service = running ?: return
            runCatching { service.activeNotifications }.getOrNull()?.forEach(service::filter)
        }

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, NotificationService::class.java)
            return context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(me)
        }
    }
}
