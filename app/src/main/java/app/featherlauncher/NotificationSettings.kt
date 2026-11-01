package app.featherlauncher

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The Notifications page, the page for one filter, and the list of what was hidden. */
class NotificationSettings(private val s: SettingsActivity) {
    private val store = NotificationFilters.get(s)

    fun build(p: Palette) {
        val content = s.content
        if (!NotificationService.isEnabled(s)) {
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_unlock, "Notification access", "Off", color = p.danger,
                    chevron = true, inset = Ui.SPACE_L) {
                    Setup.NOTIFICATIONS.start(s)
                },
            )))
            content.addView(Components.footnote(s, p,
                "Allow notification access to filter incoming notifications. Open ${s.getString(R.string.app_name)} in the list and turn on access."))
        }

        content.addView(s.heading(p, "Filters"))
        content.addView(s.spacer())
        val rows = store.filters.map { f ->
            Components.row(s, p, R.drawable.ic_filter, appName(f.app), subtitle = summary(f),
                color = if (f.enabled) p.text else p.textTertiary, chevron = true, inset = Ui.SPACE_L) {
                edit(f, isNew = false)
            }
        } + Components.row(s, p, R.drawable.ic_add, "Add filter", color = p.accent, inset = Ui.SPACE_L) {
            edit(NotificationFilter(System.currentTimeMillis(), null, emptyList()), isNew = true)
        }
        content.addView(Components.group(s, p, rows))
        content.addView(Components.footnote(s, p, "Hide notifications from an app, or only the ones that mention certain words."))


        val count = store.history.size
        content.addView(s.heading(p, "History"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_history, "Hidden notifications", if (count == 0) "None" else count.toString(),
                chevron = true, inset = Ui.SPACE_L) { s.open(SettingsActivity.Page.NOTIFICATION_HISTORY) },
        )))
    }

    private fun edit(filter: NotificationFilter, isNew: Boolean) {
        s.filterDraft = filter
        s.filterIsNew = isNew
        s.open(SettingsActivity.Page.FILTER)
    }

    /** "Hides ones that mention sale, promo", in plain words. */
    private fun summary(f: NotificationFilter): String {
        val what = when {
            f.words.isEmpty() -> "Hides all"
            f.allowOnly -> "Hides all except ones that mention ${f.words.joinToString(", ")}"
            else -> "Hides ones that mention ${f.words.joinToString(", ")}"
        }
        return what + when {
            !f.enabled -> " · Off"
            f.during != null -> limitName(f.during)?.let { " · During $it" } ?: ""
            else -> ""
        }
    }

    private fun appName(pkg: String?) =
        if (pkg == null) "All apps" else s.repo.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg

    // region Filter

    fun buildFilter(p: Palette) {
        val f = s.filterDraft ?: return s.open(SettingsActivity.Page.NOTIFICATIONS)
        val content = s.content
        fun update(next: NotificationFilter) {
            s.filterDraft = next
            s.build()
        }

        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_app_list, "App", appName(f.app), chevron = true, inset = Ui.SPACE_L) {
                pickApp(f.app) { update(f.copy(app = it)) }
            },
            Components.row(s, p, R.drawable.ic_message, "Words or phrases",
                subtitle = if (f.words.isEmpty()) "Any notification" else f.words.joinToString(", "), chevron = true, inset = Ui.SPACE_L) {
                s.overlays.showInputAllowingEmpty("Words or phrases", f.words.joinToString(", "), "sale, promo code") {
                    update(f.copy(words = NotificationFilter.parseWords(it)))
                }
            },
        )))
        content.addView(Components.footnote(s, p, "Separate words or phrases with commas. Matches ignore capitalization. Leave blank to hide all notifications from the selected app."))

        if (f.words.isNotEmpty()) {
            content.addView(s.heading(p, "Hide"))
            content.addView(s.spacer())
            content.addView(Components.segmented(s, p, listOf("Ones that match", "All except these"), if (f.allowOnly) 1 else 0) {
                update(f.copy(allowOnly = it == 1))
            })
        }

        // Always, or only while one of the app limits is on.
        val limits = AppLimits.get(s).limits
        if (limits.isNotEmpty()) {
            content.addView(s.heading(p, "When"))
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_time, "Hide notifications", f.during?.let(::limitName)?.let { "During $it" } ?: "Always",
                    chevron = true, inset = Ui.SPACE_L) {
                    Components.choiceSheet(s, p, s.overlays, "Hide notifications",
                        listOf("Always" to null) + limits.map { "During ${it.name}" to null },
                        limits.indexOfFirst { it.id == f.during } + 1) { i ->
                        update(f.copy(during = if (i == 0) null else limits[i - 1].id))
                    }
                },
            )))
        }

        if (!s.filterIsNew) {
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.toggleRow(s, p, R.drawable.ic_filter, "Filter is on", f.enabled) {
                    s.filterDraft = s.filterDraft?.copy(enabled = it)
                },
            )))
        }

        content.addView(Components.footnote(s, p, sentence(f)))

        content.addView(LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_L), 0)
            addView(Components.pillButton(s, if (s.filterIsNew) "Add filter" else "Save", p.accent, p.onAccent) {
                if (f.app == null && f.words.isEmpty()) {
                    // That would hide every notification on the phone.
                    android.widget.Toast.makeText(s, "Choose an app or add words first", android.widget.Toast.LENGTH_SHORT).show()
                    return@pillButton
                }
                store.put(s.filterDraft ?: f)
                s.filterDraft = null
                s.open(SettingsActivity.Page.NOTIFICATIONS)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)))
            if (!s.filterIsNew) {
                addView(TextView(s).apply {
                    text = "Delete filter"
                    gravity = Gravity.CENTER
                    style(TextStyle.Label, p.danger)
                    background = Ui.pressable(s, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener {
                        store.remove(f.id)
                        s.filterDraft = null
                        s.open(SettingsActivity.Page.NOTIFICATIONS)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)).apply { topMargin = s.dp(Ui.SPACE_S) })
            }
        })
    }

    /** The whole filter as one sentence, so it's clear what it will do before saving. */
    private fun sentence(f: NotificationFilter): String {
        val from = if (f.app == null) "every app" else appName(f.app)
        val which = when {
            f.words.isEmpty() -> "All notifications from $from"
            f.allowOnly -> "Notifications from $from that don't mention ${f.words.joinToString(" or ") { "“$it”" }}"
            else -> "Notifications from $from that mention ${f.words.joinToString(" or ") { "“$it”" }}"
        }
        val during = f.during?.let(::limitName)?.let { " during $it" } ?: ""
        return "$which will be hidden$during. You can read the latest 100 hidden notifications in Hidden notifications."
    }

    private fun limitName(id: Long): String? = AppLimits.get(s).find(id)?.name

    private fun pickApp(current: String?, onPick: (String?) -> Unit) {
        val apps = s.visibleApps.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        val options = listOf("All apps" to null) + apps.map { e -> e.label to ImageView(s).apply { setImageBitmap(s.repo.icon(e)) } }
        val selected = if (current == null) 0 else apps.indexOfFirst { it.packageName == current } + 1
        Components.choiceSheet(s, s.palette, s.overlays, "App", options, selected) { i ->
            onPick(if (i == 0) null else apps[i - 1].packageName)
        }
    }

    // endregion

    // region History

    fun buildHistory(p: Palette) {
        val content = s.content
        val history = store.history
        content.addView(s.spacer())
        if (history.isEmpty()) {
            content.addView(Components.footnote(s, p, "No hidden notifications."))
            return
        }
        content.addView(Components.group(s, p, history.map { h -> historyRow(p, h) }))
        content.addView(LinearLayout(s).apply {
            setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_L), 0)
            addView(TextView(s).apply {
                text = "Clear history"
                gravity = Gravity.CENTER
                style(TextStyle.Label, p.danger)
                background = Ui.pressable(s, Ui.RADIUS_PILL)
                isFocusable = true
                setOnClickListener {
                    store.clearHistory()
                    s.build()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)))
        })
    }

    /** App icon, who it's from and what it said, and when and why it was hidden. Tapping opens the app. */
    private fun historyRow(p: Palette, h: HiddenNotification) = LinearLayout(s).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M))
        background = Ui.pressable(s, 0)
        isFocusable = true
        val entry = s.repo.apps.firstOrNull { it.packageName == h.app }
        addView(ImageView(s).apply {
            val bitmap = entry?.let(s.repo::icon)
            if (bitmap != null) setImageBitmap(bitmap) else runCatching { setImageDrawable(s.packageManager.getApplicationIcon(h.app)) }
        },
            LinearLayout.LayoutParams(s.dp(32), s.dp(32)).apply { topMargin = s.dp(2) })
        addView(LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(s).apply {
                text = h.title.ifEmpty { entry?.label ?: h.app }
                style(TextStyle.Label, p.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (h.text.isNotEmpty()) addView(TextView(s).apply {
                text = h.text
                style(TextStyle.Secondary, p.textSecondary)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, s.dp(Ui.SPACE_XS), 0, 0)
            })
            addView(TextView(s).apply {
                val now = System.currentTimeMillis()
                val ago = if (now - h.time < DateUtils.MINUTE_IN_MILLIS) "Just now"
                    else DateUtils.getRelativeTimeSpanString(h.time, now, DateUtils.MINUTE_IN_MILLIS)
                text = "$ago · ${h.why}"
                style(TextStyle.Secondary, p.textTertiary)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, s.dp(Ui.SPACE_XS), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = s.dp(Ui.SPACE_M) })
        setOnClickListener {
            s.packageManager.getLaunchIntentForPackage(h.app)?.let(s::startSafely)
        }
    }

    // endregion
}
