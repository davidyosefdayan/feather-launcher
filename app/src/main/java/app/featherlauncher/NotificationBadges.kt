package app.featherlauncher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * How many notifications each app has showing, for the red badge on its icon. Android has no
 * way for apps to report an unread count, so this is worked out from their notifications:
 * one per notification, or the count an app puts on one ("3 new messages").
 */
object NotificationBadges {
    /** Package name to count; apps with none aren't in it. */
    @Volatile var counts: Map<String, Int> = emptyMap()
        private set

    /** Called on the main thread when any count changes. */
    var onChange: (() -> Unit)? = null

    fun count(pkg: String) = counts[pkg] ?: 0

    /** Recounts from what's showing now. Called by [NotificationService] whenever that changes. */
    fun update(service: NotificationListenerService, active: Array<StatusBarNotification>?) {
        val ranking = runCatching { service.currentRanking }.getOrNull()
        val r = NotificationListenerService.Ranking()
        val next = mutableMapOf<String, Int>()
        active.orEmpty().forEach { sbn ->
            val n = sbn.notification
            // Group summaries repeat their children; music, calls and downloads aren't news.
            if (sbn.packageName == service.packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@forEach
            // The app (or the person, in the app's notification settings) turned dots off for this kind.
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) return@forEach
            next[sbn.packageName] = (next[sbn.packageName] ?: 0) + n.number.coerceAtLeast(1)
        }
        set(next)
    }

    /** Access was taken away: nothing is known anymore. */
    fun clear() = set(emptyMap())

    private fun set(next: Map<String, Int>) {
        if (next == counts) return
        counts = next
        onChange?.invoke()
    }
}
