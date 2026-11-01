package app.featherlauncher

import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The App limits pages in settings: every limit and what it takes to change them, and the page
 * for one limit. Limits can always be looked at; with a guard set, changing them needs a wait,
 * the PIN or a restart first.
 */
class LimitSettings(private val s: SettingsActivity) {
    private val limits = AppLimits.get(s)

    fun build(p: Palette) {
        val content = s.content
        accessNeeded(p)

        val locked = lockCard(p)

        content.addView(s.spacer())
        val rows = limits.limits.map { limit ->
            Components.row(s, p, limit.strictness.icon, limit.name, subtitle = summary(limit),
                color = if (limit.enabled) p.text else p.textTertiary, chevron = true, inset = Ui.SPACE_L) {
                s.limitId = limit.id
                s.open(SettingsActivity.Page.LIMIT)
            }
        } + Components.row(s, p, R.drawable.ic_add, "Add limit", color = p.accent, inset = Ui.SPACE_L) {
            guarded { templateSheet() }
        }.also { if (locked) freeze(it) }
        content.addView(Components.group(s, p, rows))
        content.addView(Components.footnote(s, p,
            "Each limit locks selected apps on a schedule and stops their audio."))

        val changing = content.childCount
        content.addView(s.heading(p, "Changing limits"))
        content.addView(s.spacer())
        val guard = limits.guard
        content.addView(Components.group(s, p, listOfNotNull(
            Components.row(s, p, R.drawable.ic_lock, "Changes allowed", guard.label, chevron = true, inset = Ui.SPACE_L) {
                guarded { guardSheet() }
            },
            if (guard == AppLimits.Guard.WAIT) Components.row(s, p, R.drawable.ic_hourglass, "Wait before changing limits",
                AppLimits.formatDuration(limits.guardWaitSeconds), chevron = true, inset = Ui.SPACE_L) {
                guarded {
                    durationSheet("Wait before changing limits", listOf(30, 60, 2 * 60, 5 * 60, 10 * 60, 15 * 60), limits.guardWaitSeconds) {
                        limits.guardWaitSeconds = it
                    }
                }
            } else null,
        )))
        content.addView(Components.footnote(s, p, when (guard) {
            AppLimits.Guard.NONE -> "Require a wait, a PIN or a phone restart before changing limits."
            AppLimits.Guard.WAIT -> "To change limits, start the wait and keep Settings open for ${AppLimits.formatDuration(limits.guardWaitSeconds)}."
            AppLimits.Guard.PIN -> "Changing limits needs the PIN."
            AppLimits.Guard.RESTART -> "Limits can only be changed in the first ${AppLimits.RESTART_WINDOW / AppLimits.MINUTE} minutes after the phone starts."
        }))
        if (locked) freezeFrom(changing)
    }

    /** One limit: on or off, its apps, when, how strict, and its notifications. */
    fun buildLimit(p: Palette) {
        val limit = s.limitId?.let(limits::find) ?: return s.open(SettingsActivity.Page.LIMITS)
        val content = s.content
        accessNeeded(p)
        val locked = lockCard(p)
        val frozen = content.childCount
        fun change(next: AppLimit) = guarded {
            limits.put(next)
            s.build()
        }

        content.addView(s.spacer())
        val count = limit.apps.count { pkg -> s.visibleApps.any { it.packageName == pkg } }
        content.addView(Components.group(s, p, listOf(
            Components.toggleRow(s, p, limit.strictness.icon, "Enable limit", limit.enabled) { change(limit.copy(enabled = it)) },
            Components.row(s, p, R.drawable.ic_edit, "Name", limit.name, chevron = true, inset = Ui.SPACE_L) {
                guarded {
                    s.overlays.showInput("Name", limit.name, "Name") { change(limit.copy(name = it)) }
                }
            },
            Components.row(s, p, R.drawable.ic_app_list, "Apps",
                if (count == 0) "Choose" else if (count == 1) "1 app" else "$count apps", chevron = true, inset = Ui.SPACE_L) {
                guarded { appsSheet(limit) }
            },
        )))

        content.addView(s.heading(p, "When"))
        content.addView(s.spacer())
        val whenRows = mutableListOf<View>(
            Components.toggleRow(s, p, R.drawable.ic_time, "All day", limit.allDay) { allDay ->
                change(if (allDay) limit.copy(start = 0, end = 0) else limit.copy(start = 22 * 60, end = 7 * 60))
            },
        )
        if (!limit.allDay) whenRows += LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(timeCell(p, R.drawable.ic_sunset, "Starts", limit.start) {
                guarded { Components.timeSheet(s, p, s.overlays, "Starts", limit.start) { change(limit.copy(start = it)) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(s).apply { setBackgroundColor(divider(p)) },
                LinearLayout.LayoutParams(s.dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, s.dp(Ui.SPACE_M), 0, s.dp(Ui.SPACE_M)) })
            addView(timeCell(p, R.drawable.ic_sunrise, "Ends", limit.end) {
                guarded { Components.timeSheet(s, p, s.overlays, "Ends", limit.end) { change(limit.copy(end = it)) } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        whenRows += Components.dayPicker(s, p, limit.days) { change(limit.copy(days = it)) }
        content.addView(Components.group(s, p, whenRows))

        content.addView(s.heading(p, "How strict"))
        content.addView(s.spacer())
        content.addView(Components.segmented(s, p, Strictness.entries.map { it.label }, limit.strictness.ordinal) {
            change(limit.copy(strictness = Strictness.entries[it]))
        })
        if (limit.strictness == Strictness.WAIT) {
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_hourglass, "Wait before opening", AppLimits.formatDuration(limit.waitSeconds),
                    chevron = true, inset = Ui.SPACE_L) {
                    guarded { durationSheet("Wait before opening", listOf(10, 15, 30, 60, 120), limit.waitSeconds) { change(limit.copy(waitSeconds = it)) } }
                },
                Components.row(s, p, R.drawable.ic_time, "Time allowed after opening", AppLimits.formatDuration(limit.openMinutes * 60),
                    chevron = true, inset = Ui.SPACE_L) {
                    guarded {
                        durationSheet("Time allowed after opening", listOf(1, 2, 5, 10, 15, 30).map { it * 60 }, limit.openMinutes * 60) {
                            change(limit.copy(openMinutes = it / 60))
                        }
                    }
                },
                Components.row(s, p, R.drawable.ic_history, "Cooldown", AppLimits.formatDuration(limit.cooldownMinutes * 60),
                    chevron = true, inset = Ui.SPACE_L) {
                    guarded {
                        durationSheet("Cooldown", listOf(0, 1, 2, 5, 10, 15, 30).map { it * 60 }, limit.cooldownMinutes * 60) {
                            change(limit.copy(cooldownMinutes = it / 60))
                        }
                    }
                },
            )))
            val cooldown = if (limit.cooldownMinutes == 0) "" else
                " Then it can't be opened again for ${AppLimits.formatDuration(limit.cooldownMinutes * 60)}."
            content.addView(Components.footnote(s, p,
                "To open a limited app, tap the button and keep the limit screen open for ${AppLimits.formatDuration(limit.waitSeconds)}. " +
                    "The app opens for ${AppLimits.formatDuration(limit.openMinutes * 60)}, then locks again.$cooldown"))
        } else {
            content.addView(Components.footnote(s, p, "Apps stay blocked until the limit ends. Trying to open one shows your message."))
        }

        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_message, "Message", subtitle = limit.message.ifBlank { "None" }, chevron = true, inset = Ui.SPACE_L) {
                guarded { s.overlays.showInputAllowingEmpty("Message", limit.message, "Message shown when you try to open a limited app") { change(limit.copy(message = it)) } }
            },
        )))

        content.addView(s.heading(p, "Notifications"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.toggleRow(s, p, R.drawable.ic_notification_off, "Hold notifications", limit.holdNotifications) {
                change(limit.copy(holdNotifications = it))
            },
        )))
        content.addView(Components.footnote(s, p, if (NotificationService.isEnabled(s))
            "Notifications from locked apps are held until the limit ends, then appear again. Ongoing notifications are not held."
            else "Notifications from locked apps are held until the limit ends. Ongoing notifications are not held. Requires notification access, under Notifications."))

        content.addView(LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_L), 0)
            addView(TextView(s).apply {
                text = "Delete limit"
                gravity = Gravity.CENTER
                style(TextStyle.Label, p.danger)
                background = Ui.pressable(s, Ui.RADIUS_PILL)
                isFocusable = true
                setOnClickListener { guarded { confirmDelete(p, limit) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)))
        })
        if (locked) freezeFrom(frozen)
    }

    private fun confirmDelete(p: Palette, limit: AppLimit) {
        val column = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.dp(Ui.SPACE_XL), 0, s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_L))
            addView(TextView(s).apply {
                text = "This limit will stop locking its selected apps. Other limits will still apply."
                style(TextStyle.Body, p.textSecondary)
            })
            addView(Components.pillButton(s, "Delete", p.danger, p.onAccent) {
                s.overlays.dismiss()
                limits.remove(limit.id)
                s.open(SettingsActivity.Page.LIMITS)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)).apply { topMargin = s.dp(Ui.SPACE_L) })
        }
        s.overlays.showSheet("Delete ${limit.name}?", column)
    }

    /** Limits need the accessibility service to cover apps however they're opened. */
    private fun accessNeeded(p: Palette) {
        if (LauncherService.isEnabled(s)) return
        s.content.addView(s.spacer())
        s.content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_unlock, "Accessibility access", "Off", color = p.danger,
                chevron = true, inset = Ui.SPACE_L) {
                Setup.ACCESSIBILITY.start(s)
            },
        )))
        s.content.addView(Components.footnote(s, p,
            "App limits need this to lock apps however they're opened. In Accessibility, open ${s.getString(R.string.app_name)} and turn it on."))
    }

    /** "Block · 11:00 PM–6:00 AM · 5 apps", or "Off". */
    private fun summary(limit: AppLimit): String {
        if (!limit.enabled) return "Off"
        val count = limit.apps.count { pkg -> s.visibleApps.any { it.packageName == pkg } }
        val apps = if (count == 1) "1 app" else "$count apps"
        return "${limit.strictness.label} · ${AppLimits.formatWhen(s, limit)} · $apps"
    }

    // region Guard

    /** Runs [change] if limits can be changed now. Locked, the rows that change things can't be tapped anyway. */
    private fun guarded(change: () -> Unit) {
        if (limits.canChange()) change()
    }

    /**
     * When changes are locked: a card at the top saying so, with the way to unlock (start the
     * wait, enter the PIN, or a note about restarting). Returns whether it's locked, so the
     * page can show the rest as something to look at, not change.
     */
    private fun lockCard(p: Palette): Boolean {
        if (limits.canChange()) return false
        val guard = limits.guard
        val wait = AppLimits.formatDuration(limits.guardWaitSeconds)
        val card = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_L))
            addView(LinearLayout(s).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(s).apply {
                    setImageResource(R.drawable.ic_lock)
                    imageTintList = ColorStateList.valueOf(p.accent)
                }, LinearLayout.LayoutParams(s.dp(22), s.dp(22)).apply { marginEnd = s.dp(Ui.SPACE_M) })
                addView(TextView(s).apply {
                    text = "Changes are locked"
                    style(TextStyle.Label, p.text)
                })
            })
            addView(TextView(s).apply {
                text = "You can look at your limits. " + when (guard) {
                    AppLimits.Guard.WAIT -> "To change them, start the wait and keep Settings open for $wait."
                    AppLimits.Guard.PIN -> "To change them, enter the PIN."
                    else -> "They can only be changed in the first ${AppLimits.RESTART_WINDOW / AppLimits.MINUTE} minutes after the phone starts, so restart it first."
                }
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(0, s.dp(Ui.SPACE_S), 0, 0)
            })
        }
        if (guard == AppLimits.Guard.WAIT || guard == AppLimits.Guard.PIN) {
            val progress = ClipDrawable(Ui.rounded(s, p.accentSurface, Ui.RADIUS_PILL), Gravity.START, ClipDrawable.HORIZONTAL)
            val button = TextView(s).apply {
                gravity = Gravity.CENTER
                style(TextStyle.Label, p.onAccent)
                background = LayerDrawable(arrayOf(Ui.rounded(s, p.accent, Ui.RADIUS_PILL), progress))
                foreground = Ui.pressable(s, Ui.RADIUS_PILL)
                isFocusable = true
            }
            card.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)).apply { topMargin = s.dp(Ui.SPACE_L) })
            if (guard == AppLimits.Guard.PIN) {
                button.text = "Enter PIN"
                button.setOnClickListener {
                    PinPad.ask(s, p, s.overlays, limits.guardPin ?: return@setOnClickListener) {
                        limits.unlock()
                        s.build()
                    }
                }
            } else {
                val tick = object : Runnable {
                    override fun run() {
                        val started = limits.waitStartedAt
                        if (started == 0L) {
                            button.text = "Start wait · $wait"
                            progress.level = 0
                            return
                        }
                        val total = limits.guardWaitSeconds * 1000L
                        val elapsed = SystemClock.elapsedRealtime() - started
                        if (elapsed >= total) {
                            limits.unlock()
                            s.build()
                            return
                        }
                        val left = ((total - elapsed + 999) / 1000).toInt()
                        button.text = "%d:%02d left · Tap to stop".format(left / 60, left % 60)
                        progress.level = (elapsed * 10000 / total).toInt()
                        // Stops with the page; the wait itself carries on while Settings stays open.
                        if (button.isAttachedToWindow) button.postDelayed(this, 200)
                    }
                }
                button.setOnClickListener {
                    limits.waitStartedAt = if (limits.waitStartedAt == 0L) SystemClock.elapsedRealtime() else 0L
                    button.removeCallbacks(tick)
                    tick.run()
                }
                button.post(tick)
            }
        }
        s.content.addView(s.spacer())
        s.content.addView(Components.group(s, p, listOf(card)))
        return true
    }

    /** Shows [view] as something to look at: dimmed, and nothing in it can be tapped or highlighted. */
    private fun freeze(view: View) {
        view.alpha = 0.45f
        fun off(v: View) {
            v.isEnabled = false
            v.isClickable = false
            v.isFocusable = false
            (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) off(g.getChildAt(i)) }
        }
        off(view)
    }

    /** [freeze] for everything on the page from child [index] on. */
    private fun freezeFrom(index: Int) {
        for (i in index until s.content.childCount) freeze(s.content.getChildAt(i))
    }

    private fun guardSheet() {
        val p = s.palette
        val guards = AppLimits.Guard.entries
        Components.choiceSheet(s, p, s.overlays, "Changes allowed", guards.map { it.label to null }, limits.guard.ordinal) { i ->
            val next = guards[i]
            // Allowed to change things until Settings is left, so the rest can still be set up now.
            limits.unlock()
            if (next == AppLimits.Guard.PIN) {
                s.root.postDelayed({
                    PinPad.create(s, p, s.overlays) {
                        limits.guardPin = PinPad.hash(it)
                        limits.guard = next
                        s.build()
                    }
                }, 160)
                return@choiceSheet
            }
            limits.guard = next
            s.build()
        }
    }

    // endregion

    /** Ready-made limits to start from, each with what it's for. */
    private fun templateSheet() {
        val p = s.palette
        val column = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, s.dp(Ui.SPACE_M))
        }
        AppLimits.templates().forEach { (name, about, limit) ->
            column.addView(Components.row(s, p, limit.strictness.icon, name, subtitle = about) {
                s.overlays.dismiss()
                limits.put(limit)
                s.limitId = limit.id
                s.open(SettingsActivity.Page.LIMIT)
                // Choosing the apps comes next.
                s.root.postDelayed({ appsSheet(limit) }, 200)
            })
        }
        s.overlays.showSheet("New limit", column)
    }

    /** Every app, chosen ones first so they're easy to find and remove. */
    private fun appsSheet(limit: AppLimit) {
        val chosen = limit.apps.toMutableSet()
        val apps = s.visibleApps
            .filter { it.packageName != s.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.packageName !in chosen }, { it.label.lowercase() }))
        Components.multiChoiceSheet(s, s.palette, s.overlays, "Apps",
            apps.map { e -> e.label to ImageView(s).apply { setImageBitmap(s.repo.icon(e)) } },
            apps.indices.filter { apps[it].packageName in chosen }.toSet(),
            fill = true,
            onToggle = { i, checked ->
                if (checked) chosen += apps[i].packageName else chosen -= apps[i].packageName
                limits.find(limit.id)?.let { limits.put(it.copy(apps = chosen.toSet())) }
            },
            onClosed = { s.build() })
    }

    /** Half of the times: an icon and label over the time in large type. */
    private fun timeCell(p: Palette, icon: Int, label: String, minutes: Int, onClick: () -> Unit) = LinearLayout(s).apply {
        orientation = LinearLayout.VERTICAL
        // Inset and rounded like the group's other rows (set before the padding, which it would replace).
        background = android.graphics.drawable.InsetDrawable(Ui.pressable(s, Components.GROUP_RADIUS - 4), s.dp(4))
        setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_M))
        isFocusable = true
        addView(LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(s).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.textSecondary)
            }, LinearLayout.LayoutParams(s.dp(18), s.dp(18)).apply { marginEnd = s.dp(Ui.SPACE_S) })
            addView(TextView(s).apply {
                text = label
                style(TextStyle.Secondary, p.textSecondary)
            })
        })
        addView(TextView(s).apply {
            text = AppLimits.formatTime(s, minutes)
            style(TextStyle.Title, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(0, s.dp(Ui.SPACE_S), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun divider(p: Palette) = if (p.dark) 0x1AFFFFFF else 0x14000000

    private fun durationSheet(title: String, seconds: List<Int>, current: Int, onPick: (Int) -> Unit) {
        Components.choiceSheet(s, s.palette, s.overlays, title, seconds.map { AppLimits.formatDuration(it) to null },
            seconds.indexOf(current)) {
            onPick(seconds[it])
            s.build()
        }
    }
}
