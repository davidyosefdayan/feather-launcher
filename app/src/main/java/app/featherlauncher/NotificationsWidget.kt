package app.featherlauncher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

/**
 * The notifications showing now, in the order Android's own shade puts them. Kept up to date
 * by [NotificationService]; everything else reads it and listens for changes.
 */
object ActiveNotifications {
    @Volatile var list: List<StatusBarNotification> = emptyList()
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    /** Whether [list] has come from the service since access was last taken away. */
    private var known = false

    /** Called on the main thread whenever [list] changes. */
    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)

    fun update(service: NotificationListenerService, active: Collection<StatusBarNotification>) {
        val ranking = runCatching { service.currentRanking }.getOrNull()
        val r = NotificationListenerService.Ranking()
        val order = ranking?.orderedKeys?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val next = active.filter { sbn ->
            val n = sbn.notification
            // Group summaries repeat their children; minimized ones aren't in the shade either.
            sbn.packageName != service.packageName && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                (ranking == null || !ranking.getRanking(sbn.key, r) || r.importance > NotificationManager.IMPORTANCE_MIN) &&
                // One drawn by its app alone (a timer) still has its buttons; it goes by its app's name.
                (Note.title(n).isNotEmpty() || Note.text(n).isNotEmpty() || !n.actions.isNullOrEmpty())
        }.sortedWith(compareBy<StatusBarNotification> { order[it.key] ?: Int.MAX_VALUE }.thenByDescending { it.postTime })
        // Rankings are updated often without anything new to show; a posted one is a new object.
        if (known && next.size == list.size && next.indices.all { next[it] === list[it] }) return
        known = true
        list = next
        changed()
    }

    /** Access was taken away: nothing is known anymore. */
    fun clear() {
        known = false
        list = emptyList()
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/** Something in a widget that the keypad's left or right sends away (a notification, cleared). */
interface SwipesAway {
    /** Returns true when it took the key. */
    fun swipeAway(direction: Int): Boolean
}

/** One notification, as the widget and the sheet show it. */
class Note(private val context: Context, val sbn: StatusBarNotification) {
    private val n = sbn.notification
    val key: String = sbn.key
    val title = title(n)
    val text = text(n)
    /** All of the text, for when it's opened up and the sheet: an inbox's lines, a conversation's messages. */
    val longText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        ?: n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf { it.isNotBlank() }
        ?: messages()?.takeIf { it.isNotBlank() } ?: text
    val app: String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
    }.getOrDefault(sbn.packageName)
    val time = if (n.`when` > 0) n.`when` else sbn.postTime
    val actions: List<Notification.Action> = n.actions.orEmpty().filterNotNull()
    /** A music or video player's: its buttons are pictures (back, play, skip). */
    val media = n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
        n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
    /** One with buttons, or more text than its line, which opens up in the widget to show them. */
    val expandable = actions.isNotEmpty() || longText != text
    /** Whether it can be cleared: not one an app keeps showing, such as music that's playing. */
    val clearable = sbn.isClearable

    /** A conversation's messages, a line each with who sent it. */
    @Suppress("DEPRECATION")
    private fun messages() = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { m ->
        val words = (m as? android.os.Bundle)?.getCharSequence("text") ?: return@mapNotNull null
        m.getCharSequence("sender")?.let { "$it: $words" } ?: words.toString()
    }?.joinToString("\n")

    fun icon(): Drawable? = runCatching { context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    /** The picture an app puts on it: album art, a contact's photo. */
    fun art(): Drawable? = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
    fun actionIcon(a: Notification.Action): Drawable? = runCatching { a.getIcon()?.loadDrawable(context) }.getOrNull()

    /** "3:41 PM" today, "Oct 30" before; nothing for one whose app says its time doesn't matter (a timer). */
    fun whenText(): String = if (!n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)) "" else
        if (android.text.format.DateUtils.isToday(time)) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))
        else android.text.format.DateFormat.format("MMM d", time).toString()

    /** Opens what it's about, as tapping it in the shade does, or else its app. */
    fun open() {
        val sent = n.contentIntent?.let { send(context, it) } == true
        if (!sent) context.packageManager.getLaunchIntentForPackage(sbn.packageName)?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) NotificationService.cancel(key)
    }

    /** Clears it, as swiping it away in the shade does. Returns false when it can't be. */
    fun clear(): Boolean = clearable && NotificationService.cancel(key)

    /** Presses one of its buttons. A reply needs typing, which its app is for. */
    fun press(a: Notification.Action) {
        if (!a.remoteInputs.isNullOrEmpty() || a.actionIntent == null) open() else send(context, a.actionIntent)
    }

    /** Whether [a] plays or pauses, the button that stands out on a player. */
    fun isPlayPause(a: Notification.Action) = a.title?.toString()?.let { Regex("play|pause", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true

    companion object {
        fun title(n: Notification) = (n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()

        fun text(n: Notification) = (n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim()?.replace('\n', ' ').orEmpty()

        /** Sends an app's [PendingIntent] as the launcher on screen, which may open its activities. */
        fun send(context: Context, intent: PendingIntent): Boolean = runCatching {
            val options = ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
            intent.send(context, 0, null, null, null, null, options)
        }.isSuccess
    }
}

/**
 * The notifications showing, a line each: its title and text, cut short with … when it's long
 * (on a narrow card, the text under the title). As many lines as fit, spaced evenly down the card.
 * A tap opens its app; one with buttons, or more text than fits, has a › and opens up instead,
 * filling the card with all of its text (scrolling, if it must) and its buttons. When they don't all fit,
 * the heading says how many more, and opens them all in a sheet. Swiping a line to the side,
 * or left or right on the keypad, clears it. Only on a card: a list of words on the wallpaper is hard to read.
 */
@SuppressLint("ViewConstructor")
class NotificationsWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** All of them, in a sheet. */
    var onMore: (() -> Unit)? = null
    /** After the card is made again, with new things in it to press. */
    var onRebuilt: (() -> Unit)? = null

    /** The one open to show its buttons, if any. */
    private var expanded: String? = null
    /** Where the keypad goes once the line it was on is cleared. */
    private var refocus: String? = null
    private val changed = { refresh() }
    /** While the card is being made again, when the highlight may briefly go elsewhere. */
    private var rebuilding = false
    /** The keypad highlight leaving the card closes the open one. */
    private val focusMoved = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (!rebuilding && expanded != null && now != null && !isInside(now)) collapse()
    }

    private fun isInside(view: View): Boolean = generateSequence(view) { it.parent as? View }.any { it === this }

    init {
        // The lines' highlights reach a little past the card's room for its contents, into its
        // padding; drawn whole, not cut off at the sides.
        clipToPadding = false
        clipChildren = false
        content.clipChildren = false
    }

    /** Narrow: each line's text goes under its title, and the times are left out. */
    private var narrow = false
    /** Not wide enough for the times beside the words. */
    private var timeless = false
    /** So narrow the apps' icons are left out too. */
    private var iconless = false

    override fun sizeChanged(w: Int, h: Int): Boolean {
        val nextNarrow = w < dp(260)
        val nextTimeless = w < dp(320)
        val nextIconless = w < dp(180)
        if (nextNarrow == narrow && nextTimeless == timeless && nextIconless == iconless) return false
        narrow = nextNarrow
        timeless = nextTimeless
        iconless = nextIconless
        return true
    }

    override fun onAttachedToWindow() {
        ActiveNotifications.listen(changed)
        viewTreeObserver.addOnGlobalFocusChangeListener(focusMoved)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        ActiveNotifications.unlisten(changed)
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusMoved)
        super.onDetachedFromWindow()
    }

    /** Another app, a system dialog, or the screen going off: the open one closes. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) collapse()
    }

    override fun refresh() {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    private fun rebuild() {
        if (!NotificationService.isEnabled(context)) {
            expanded = null
            return prompt(R.drawable.ic_notification, "Notifications", "Allow notification access to see your notifications") {
                allow(Setup.NOTIFICATIONS)
            }
        }
        val active = ActiveNotifications.list
        if (active.none { it.key == expanded }) expanded = null
        // Opened up, a tap between its buttons does nothing, rather than opening the sheet over it.
        setOnClickListener { if (expanded == null) onMore?.invoke() }
        // Only what's on the card: an app updating a notification often changes none of it. The
        // day is there for the times, which say the date once it isn't today.
        if (same(listOf(narrow, timeless, iconless, expanded, TimeFormat.is24(context), JewishCalendar.day(System.currentTimeMillis(), java.util.TimeZone.getDefault()),
                active.map { sbn -> sbn.notification.let { n -> listOf(sbn.key, if (n.`when` > 0) n.`when` else sbn.postTime, Note.title(n), Note.text(n),
                    n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(), n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
                    n.actions?.map { it.title?.toString() }, sbn.isClearable) } }))) return
        // The keypad stays on what it was on, if that's still here.
        val focused = findFocus()?.takeIf { !it.isInTouchMode }?.tag as? String
        val all = active.map { Note(context, it) }
        val open = all.firstOrNull { it.key == expanded }
        content.removeAllViews()
        content.gravity = Gravity.TOP
        content.spread = false
        setCard(WidgetStyle.cardBackground(context))
        if (open != null) {
            content.addView(FitColumn.dropFirst(expandedView(open), -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (all.isEmpty()) {
            // Nothing to show is calm, not a problem: a quiet line in the middle of the card.
            content.addView(heading(R.drawable.ic_notification, "Notifications"))
            content.addView(FitColumn.dropFirst(quiet("No notifications").apply { gravity = Gravity.CENTER }, -1),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            // The lines' highlights reach a little past the heading's edges; their contents line up with it.
            content.addView(FitColumn.dropFirst(lines(all), -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                marginStart = -dp(ROW_PAD); marginEnd = -dp(ROW_PAD)
            })
        }
        // One cleared with the keypad hands the highlight to the line that took its place.
        (focused?.let { findViewWithTag<View>(it) } ?: refocus?.let { findViewWithTag<View>(it) })?.requestFocusFromTouch()
        refocus = null
        onRebuilt?.invoke()
    }

    /** Whether one is open to show its buttons, rather than the list. */
    val isExpanded get() = expanded != null

    /** Back, or the close button: the open one closes again. Returns false when none is open. */
    fun collapse(): Boolean {
        val key = expanded ?: return false
        val keypad = findFocus()?.isInTouchMode == false
        expanded = null
        refresh()
        if (keypad) findViewWithTag<View>(key)?.requestFocusFromTouch()
        return true
    }

    private fun expand(note: Note, keypad: Boolean) {
        expanded = note.key
        refresh()
        // The keypad goes straight to its buttons.
        if (keypad) (findViewWithTag<View>("${note.key}#0") ?: findViewWithTag("${note.key}#open"))?.requestFocusFromTouch()
    }

    /**
     * The heading and a line for each notification that fits. The heading says how many there
     * are; when they don't all fit, it says how many more instead, and opens them all.
     */
    private fun lines(all: List<Note>): Lines {
        val count = WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = all.size.toString() }
        val more = WidgetStyle.cardText(context, 13f, WidgetStyle.label, WidgetStyle.accent()).apply {
            tag = "more"
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(null, null, context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply {
                setTint(WidgetStyle.accent())
                setBounds(0, 0, dp(16), dp(16))
            }, null)
            compoundDrawablePadding = dp(2)
            background = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = WidgetStyle.onCard())
            setPadding(dp(8), dp(3), dp(2), dp(3))
            setOnClickListener { onMore?.invoke() }
        }
        val top = heading(R.drawable.ic_notification, "Notifications").apply {
            addView(count)
            addView(more)
        }
        val title = top.getChildAt(1) as TextView
        fun View.shows(visibility: Int) {
            if (this.visibility != visibility) this.visibility = visibility
        }
        return Lines(context, top, dp(ROW_PAD), dp(6)) { hidden, width ->
            if (more.text.toString() != "$hidden more") more.text = "$hidden more"
            val end = if (hidden > 0) more else count
            end.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            // On a narrow card, when they don't both fit: the title goes before how many more, the count before the title.
            val fits = dp(26) + title.paint.measureText(title.text.toString()) + end.measuredWidth <= width
            title.shows(if (fits || hidden == 0) View.VISIBLE else View.INVISIBLE)
            count.shows(if (hidden == 0 && fits) View.VISIBLE else View.GONE)
            more.shows(if (hidden > 0) View.VISIBLE else View.GONE)
        }.apply { all.forEach { addView(Row(it)) } }
    }

    /**
     * A line: the app's icon, then the title and its text, cut short with … when it's long, then
     * when. One with buttons, or text that doesn't fit, has a › after the time, and opens up.
     */
    private inner class Row(val note: Note) : LinearLayout(context), SwipesAway {
        // Narrow, the title has a line to itself and its text goes under it; otherwise the text follows it.
        private val words = if (narrow) WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply { text = note.title.ifEmpty { note.app } }
            else WidgetStyle.cardText(context, 15f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = styled(note.title.ifEmpty { note.app }, note.text) }
        private val below = if (narrow && note.text.isNotEmpty()) WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
            text = note.text
            setPadding(0, dp(2), 0, 0)
        } else null
        private val time = WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
            text = note.whenText()
            if (timeless) visibility = View.GONE
        }
        private val more = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(WidgetStyle.onCardQuiet())
        }
        /** Whether a tap opens it up, rather than opening its app. */
        private var opens = note.expandable

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = note.key
            background = Ui.pressable(context, Ui.RADIUS_S, focusColor = WidgetStyle.onCard())
            // Two lines of words need less room around them to look as roomy.
            val v = dp(if (below != null) ROW_V - 2 else ROW_V)
            setPadding(dp(ROW_PAD), v, dp(ROW_PAD), v)
            setOnClickListener { if (opens) expand(note, !isInTouchMode && isFocused) else note.open() }
            if (!iconless) addView(ImageView(context).apply { setImageDrawable(note.icon()) }, LayoutParams(dp(ICON), dp(ICON)).apply { marginEnd = dp(10) })
            addView(if (below == null) words else LinearLayout(context).apply {
                orientation = VERTICAL
                addView(words)
                addView(below)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(more, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(if (timeless) 4 else 8) })
        }

        // Swiping it to either side clears it, as in the shade. One that can't be cleared still
        // takes the swipe, so it stays put and doesn't turn the page either.
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private var velocity: VelocityTracker? = null

        // A tap still goes through the row's own onTouchEvent, which clicks it.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(ev) }
            // Raw positions: the row itself moves under the finger.
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    swiping = false
                    // Sideways is the row's until the finger shows it's going up or down instead;
                    // otherwise the pages would take it first.
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!swiping) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        swiping = true
                        // No tap, press or hold once it's a swipe.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    } else if (abs(dy) > slop) {
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val vx = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (swiping) {
                        swiping = false
                        if (!note.clearable) return true
                        val flung = abs(vx) > dp(FLING) && sign(vx) == sign(dx)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && (abs(dx) > width / 3 || flung)) {
                            away(if (dx > 0) 1 else -1, keypad = false)
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(150).start()
                        }
                        return true
                    }
                }
            }
            if (swiping) {
                if (!note.clearable) return true
                translationX = dx
                alpha = 1f - (abs(dx) / width).coerceIn(0f, 0.8f)
                return true
            }
            return super.onTouchEvent(ev)
        }

        override fun swipeAway(direction: Int): Boolean {
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return false
            // Left or right on one that can't be cleared does nothing, rather than leaving the widget.
            if (note.clearable) away(if (direction == View.FOCUS_RIGHT) 1 else -1, keypad = true)
            return true
        }

        /** Slides off to the [side] (1 right, -1 left) and clears it; it slides back if it can't be. */
        private fun away(side: Int, keypad: Boolean) {
            if (keypad) {
                // The highlight goes to the next line, or the one before when it was the last.
                val rows = (parent as ViewGroup).let { p -> (0 until p.childCount).map(p::getChildAt) }
                    .filter { it.visibility == View.VISIBLE && it.isClickable }
                val i = rows.indexOf(this)
                refocus = (rows.getOrNull(i + 1) ?: rows.getOrNull(i - 1))?.tag as? String
            }
            animate().translationX(side * width.toFloat()).alpha(0f).setDuration(180).withEndAction {
                if (!note.clear()) {
                    refocus = null
                    animate().translationX(0f).alpha(1f).setDuration(150).start()
                }
            }.start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Whether the text fits beside the time and the ›'s room, worked out before laying out,
            // so the row isn't laid out twice. Visibility only changes when the answer does.
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - (if (iconless) 0 else dp(ICON + 10)) - dp(18 + if (timeless) 4 else 8) -
                (if (timeless) 0 else dp(8) + time.paint.measureText(time.text.toString()).toInt())
            opens = note.expandable || android.text.Layout.getDesiredWidth(words.text, words.paint) > room ||
                below != null && below.paint.measureText(note.text) > room
            // Its room stays when it has none, so the times line up.
            more.visibility = if (opens) View.VISIBLE else View.INVISIBLE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** The title stands out; the text after it is quieter. */
    private fun styled(title: String, text: String?): CharSequence = SpannableStringBuilder().apply {
        append(title, ForegroundColorSpan(WidgetStyle.onCard()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(WidgetStyle.title), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!text.isNullOrEmpty()) append("  ").append(text)
    }

    /** One notification filling the card: its app and title on one line, then its text (which opens it), and its buttons. */
    private fun expandedView(note: Note) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageDrawable(note.icon()) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            // The app, then the title beside it, leaving the rest of the card for the text. Narrow,
            // the icon says which app, and the time is left out, as on the lines.
            if (!narrow) addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, WidgetStyle.accent()).apply { text = note.app },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply {
                text = if (narrow) note.title.ifEmpty { note.app } else note.title.takeIf { it != note.app }.orEmpty()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (!timeless) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = note.whenText() })
            addView(iconButton(R.drawable.ic_close, WidgetStyle.onCard(), null, 32) { collapse() }.apply {
                tag = "${note.key}#close"
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) })
        })
        // All of its text (and its picture, if it has one), which open it; text that doesn't fit scrolls, by touch or keypad.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "${note.key}#open"
            background = Ui.pressable(context, Ui.RADIUS_S, focusColor = WidgetStyle.onCard())
            setPadding(dp(ROW_PAD), dp(4), dp(ROW_PAD), dp(4))
            setOnClickListener { note.open() }
            note.art()?.let { art ->
                addView(ImageView(context).apply {
                    setImageDrawable(art)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = Ui.rounded(context, WidgetStyle.accentFill(), 12)
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            }
            if (note.longText.isNotEmpty()) addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(8))
                addView(WidgetStyle.cardText(context, 15f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
                    text = note.longText
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    // A tap on the text opens it too; the scroll view only takes drags.
                    setOnClickListener { note.open() }
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4); marginStart = -dp(ROW_PAD); marginEnd = -dp(ROW_PAD)
        })
        if (note.actions.isNotEmpty()) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            note.actions.forEachIndexed { i, a ->
                val icon = if (note.media) note.actionIcon(a) else null
                val words = a.title?.toString().orEmpty()
                val button = if (icon != null || words.isBlank()) {
                    val main = note.isPlayPause(a)
                    iconButton(0, if (main) WidgetStyle.onAccent() else WidgetStyle.onCard(), if (main) WidgetStyle.accent() else null, 44) { note.press(a) }.apply {
                        setImageDrawable((icon ?: note.actionIcon(a))?.mutate())
                        contentDescription = words
                    }.also { b -> b.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(4) } }
                } else {
                    pill(words) { note.press(a) }.also { b -> b.layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(3); marginEnd = dp(3) } }
                }
                addView(button.apply { tag = "${note.key}#$i" })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** A round button, as on the Now playing card; [fill] makes it solid. */
    private fun iconButton(icon: Int, color: Int, fill: Int?, sizeDp: Int, onClick: () -> Unit) = ImageView(context).apply {
        if (icon != 0) setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(sizeDp / 4)
        setPadding(inset, inset, inset, inset)
        background = fill?.let { Ui.rounded(context, it, Ui.RADIUS_PILL) }
        foreground = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = WidgetStyle.onCard())
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * A button with words on it, in the accent's soft fill. Words that don't fit are drawn a little
     * closer, else cut short with …, as an app's name is on the home screen.
     */
    private fun pill(words: String, onClick: () -> Unit) = AppIconView.NameView(context).apply {
        setTextColor(WidgetStyle.onAccentFill())
        typeface = WidgetStyle.label
        includeFontPadding = false
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        text = words
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = Ui.rounded(context, WidgetStyle.accentFill(), Ui.RADIUS_PILL)
        foreground = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = WidgetStyle.onCard())
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * [top] (the heading, [inset] in from the sides, [gap] above the lines) and the lines, as many
     * as fit. [hidden] is told how many don't, and [top]'s width, before [top] is measured, so it can say so. When
     * some don't fit, the room left over is shared out between the lines, so the last sits on the
     * bottom of the card; when they all do, they keep to the top.
     */
    private class Lines(context: Context, private val top: View, private val inset: Int, private val gap: Int,
                        private val hidden: (Int, Int) -> Unit) : ViewGroup(context) {
        init {
            addView(top)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rows = (1 until childCount).map(::getChildAt)
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val exactly = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun heading(left: Int): Int {
                hidden(left, width - 2 * inset)
                top.measure(MeasureSpec.makeMeasureSpec(width - 2 * inset, MeasureSpec.EXACTLY), loose)
                return top.measuredHeight + gap
            }
            rows.forEach { it.measure(exactly, loose) }
            val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
            var shown = rows.size
            var used = heading(0) + rows.sumOf { it.measuredHeight }
            if (used > room) {
                // The heading may be a little taller saying how many more.
                used = heading(1)
                shown = 0
                while (shown < rows.size && used + rows[shown].measuredHeight <= room) used += rows[shown++].measuredHeight
                heading(rows.size - shown)
                // Each line a little taller, its contents in the middle of it.
                val spare = room - used
                rows.take(shown).forEachIndexed { i, v ->
                    val extra = spare / shown + if (i < spare % shown) 1 else 0
                    v.measure(exactly, MeasureSpec.makeMeasureSpec(v.measuredHeight + extra, MeasureSpec.EXACTLY))
                }
            }
            rows.forEachIndexed { i, v ->
                val next = if (i < shown) View.VISIBLE else View.GONE
                if (v.visibility != next) v.visibility = next
            }
            setMeasuredDimension(width, if (shown == rows.size) minOf(used, room) else room)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            top.layout(inset, 0, inset + top.measuredWidth, top.measuredHeight)
            var y = top.measuredHeight + gap
            for (i in 1 until childCount) {
                val v = getChildAt(i)
                if (v.visibility != View.VISIBLE) continue
                v.layout(0, y, v.measuredWidth, y + v.measuredHeight)
                y += v.measuredHeight
            }
        }
    }

    private companion object {
        /** Room around a row's contents, inside its highlight. */
        const val ROW_PAD = 8
        /** Room above and below a row's contents, at the least. */
        const val ROW_V = 7
        /** A row's app icon. */
        const val ICON = 20
        /** How fast a swipe (dp a second) clears a row however short it is. */
        const val FLING = 1000
    }
}

/** Every notification showing, in a sheet: what the widget's heading opens when they don't all fit. */
object NotificationsSheet {
    fun show(ctx: Context, p: Palette, overlays: Overlays) {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val all = ActiveNotifications.list.map { Note(ctx, it) }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_S), 0, dp(Ui.SPACE_S), dp(Ui.SPACE_M))
        }
        if (all.isEmpty()) column.addView(TextView(ctx).apply {
            text = "No notifications"
            style(TextStyle.Secondary, p.textSecondary)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
        })
        all.forEach { note ->
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(52)
                setPadding(dp(Ui.SPACE_L - Ui.SPACE_S), dp(10), dp(Ui.SPACE_L - Ui.SPACE_S), dp(10))
                background = Ui.pressable(ctx, Ui.RADIUS_M)
                isFocusable = true
                setOnClickListener {
                    overlays.dismiss()
                    note.open()
                }
                addView(ImageView(ctx).apply { setImageDrawable(note.icon()) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(Ui.SPACE_M); topMargin = dp(2)
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply {
                        text = "${note.app} · ${note.whenText()}"
                        style(TextStyle.Secondary, p.textTertiary)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = note.title.ifEmpty { note.app }
                        style(TextStyle.Label, p.text)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                    if (note.longText.isNotEmpty()) addView(TextView(ctx).apply {
                        text = note.longText
                        style(TextStyle.Secondary, p.textSecondary)
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                    // Ones with buttons that matter show them here too.
                    if (note.expandable) addView(actions(ctx, p, overlays, note), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Ui.SPACE_S) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet("Notifications", scroll, scrollable = scroll)
    }

    private fun actions(ctx: Context, p: Palette, overlays: Overlays, note: Note) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val dp = { v: Int -> Ui.dp(ctx, v) }
        note.actions.forEach { a ->
            val icon = if (note.media) note.actionIcon(a) else null
            val words = a.title?.toString().orEmpty()
            val main = note.media && note.isPlayPause(a)
            val press = {
                overlays.dismiss()
                note.press(a)
            }
            if (icon != null || words.isBlank()) {
                addView(ImageView(ctx).apply {
                    setImageDrawable((icon ?: note.actionIcon(a))?.mutate())
                    imageTintList = ColorStateList.valueOf(if (main) p.onAccent else p.text)
                    contentDescription = words
                    val inset = dp(10)
                    setPadding(inset, inset, inset, inset)
                    background = Ui.rounded(ctx, if (main) p.accent else p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { press() }
                }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(Ui.SPACE_S) })
            } else {
                addView(AppIconView.NameView(ctx).apply {
                    text = words
                    style(TextStyle.Label, p.text)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(dp(Ui.SPACE_M), 0, dp(Ui.SPACE_M), 0)
                    background = Ui.rounded(ctx, p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { press() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(Ui.SPACE_S) })
            }
        }
    }
}
