package app.featherlauncher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Calendar
import java.util.Date

/**
 * Feather's own widgets: the clock, and the ones on cards. [ref] is the [HomeItem.ref] that says
 * which; [about] says what it's for. [minW] by [minH] cells is the smallest it can be made: it
 * looks right at every size from there up.
 */
enum class FeatherKind(val ref: String, val label: String, val w: Int, val h: Int, val about: String, val minW: Int = 2, val minH: Int = 1) {
    CLOCK("", "Clock", 4, 2, "The time, the date and what's coming up"),
    UP_NEXT("upnext", "Up next", 4, 1, "Your next event, zman and alarm, with the weather now"),
    CALENDAR("agenda", "Calendar", 4, 2, "Your upcoming events", minH = 2),
    WEATHER("weather", "Weather", 4, 2, "Current weather with hourly and daily forecasts"),
    MEDIA("media", "Now playing", 4, 1, "Play, pause and skip whatever's playing"),
    ZMANIM("zmanim", "Zmanim", 4, 2, "Upcoming zmanim", minH = 2),
    ;

    /**
     * How many rows it takes when added, for cells [cellHeightDp] tall. On short screens
     * (keypad phones) Weather takes three, or there's no room for the days under it.
     */
    fun height(cellHeightDp: Float) = if (this == WEATHER && cellHeightDp < 100f) 3 else h

    companion object {
        fun of(item: HomeItem) = entries.firstOrNull { it.ref == item.ref } ?: CLOCK
    }
}

/**
 * A Feather widget on a card, in Android's own widget style ([WidgetStyle]). Works its
 * contents out again every minute and whenever [refresh] is called.
 */
@SuppressLint("ViewConstructor")
abstract class CardWidget(context: Context, val item: HomeItem) : FrameLayout(context) {
    /** Rows that don't fit are left out, the last ones first. */
    protected val content = FitColumn(context).apply { gravity = Gravity.CENTER_VERTICAL }

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    /** The tallest the card gets, in dp; in a taller space it sits centred. Null: it fills the space. */
    protected open val maxHeight: Int? = null
    /** Room above and below the card when the space is taller than [maxHeight]. */
    private var extra = 0
    /** The padding inside the card, without [extra]. */
    private var inside = intArrayOf(0, 0, 0, 0)

    init {
        isFocusable = true
        pad(dp(GAP + 18), dp(GAP + 16), dp(GAP + 18), dp(GAP + 16))
        setCard(WidgetStyle.cardBackground(context))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The room between the card's edges and what's on it (the gap around the card included). */
    protected fun pad(left: Int, top: Int, right: Int, bottom: Int) {
        inside = intArrayOf(left, top, right, bottom)
        setPadding(left, top + extra, right, bottom + extra)
        // The keypad highlight is white around the card, as it is around app icons.
        foreground = InsetDrawable(Ui.focusHighlight(context, WidgetStyle.RADIUS + GAP), 0, extra, 0, extra)
    }

    /** The card behind everything, inset so cards next to each other have a gap between them. */
    protected fun setCard(drawable: android.graphics.drawable.Drawable) {
        background = InsetDrawable(drawable, dp(GAP), dp(GAP) + extra, dp(GAP), dp(GAP) + extra)
        // A new background brings its own padding; the card's stays.
        pad(inside[0], inside[1], inside[2], inside[3])
    }

    /** No card: straight on the wallpaper. */
    protected fun clearCard() {
        background = null
        pad(inside[0], inside[1], inside[2], inside[3])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = maxHeight?.let { ((h - dp(it)) / 2).coerceAtLeast(0) } ?: 0
        if (next != extra) {
            extra = next
            pad(inside[0], inside[1], inside[2], inside[3])
            post { refresh() }
        }
    }

    private val hold = WholeHold(this)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent) = hold.touch(ev) { super.dispatchTouchEvent(it) }

    override fun performLongClick() = hold.longClick { super.performLongClick() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_TICK)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        })
        refresh()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    abstract fun refresh()

    /** A small icon in the accent color and a label, at the top of a card: "Zmanim · Monsey". */
    protected fun heading(icon: Int, text: String, end: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(WidgetStyle.accent())
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, WidgetStyle.accent()).apply { this.text = text },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (end != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { this.text = end })
    }

    /**
     * What the widget shows when it can't show itself yet: an icon on a soft tile, its name, and
     * one line saying what it needs. Tapping anywhere on it does [onTap].
     */
    protected fun prompt(icon: Int, title: String, line: String, onTap: () -> Unit) {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        content.spread = false
        setCard(WidgetStyle.cardBackground(context))
        setOnClickListener { onTap() }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                background = Ui.rounded(context, WidgetStyle.accentFill(), 16)
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(WidgetStyle.onAccentFill())
                }, LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 16f, WidgetStyle.title).apply { text = title })
                addView(quiet(line, 14f).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    /** Opens Calendar and times, where the location is chosen. */
    protected fun chooseLocation() {
        context.startActivity(android.content.Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.Page.CALENDAR.name))
    }

    /** Asks for what [item] turns on, from the home screen. */
    protected fun allow(item: Setup.Item) {
        (context as? android.app.Activity)?.let(item.start)
    }

    /** A soft rounded shape where text will be, while it loads. */
    protected fun placeholder(w: Int, h: Int, color: Int) = View(context).apply {
        background = Ui.rounded(context, (color and 0xFFFFFF) or 0x26000000, h / 2)
        layoutParams = LinearLayout.LayoutParams(dp(w), dp(h))
    }

    protected fun quiet(text: String, sp: Float = 15f) = WidgetStyle.cardText(context, sp, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
        this.text = text
        maxLines = 2
    }

    protected fun time(t: Long, zone: java.util.TimeZone? = null): String =
        android.text.format.DateFormat.getTimeFormat(context).apply { zone?.let { timeZone = it } }.format(Date(t))

    protected fun dp(v: Int) = Ui.dp(context, v)

    protected fun gapAbove(v: View, gap: Int) = v.also {
        it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(gap) }
    }

    companion object {
        /** Half the space between two cards. */
        const val GAP = 5

        /** The view for a Feather widget that sits on a card, or null for the clock. */
        fun create(context: Context, item: HomeItem): CardWidget? = when (FeatherKind.of(item)) {
            FeatherKind.UP_NEXT -> UpNextWidget(context, item)
            FeatherKind.CALENDAR -> CalendarWidget(context, item)
            FeatherKind.WEATHER -> WeatherWidget(context, item)
            FeatherKind.MEDIA -> MediaWidget(context, item)
            FeatherKind.ZMANIM -> ZmanimWidget(context, item)
            else -> null
        }
    }
}

/**
 * What's coming up, in the spirit of Google's At a Glance: the most pressing thing in bold, when
 * it is under it ("in 25 min · 4:00 – 5:00 PM"), then where an event is or what comes after, and
 * the weather now in a tile on the right. On a card (Solid) or straight on the wallpaper
 * (Transparent). It grows no taller than [MAX_HEIGHT]: in a bigger space it sits centred, as
 * Google's and the clock do. With nothing coming up, it shows the day; without a location,
 * where to choose one.
 */
@SuppressLint("ViewConstructor")
class UpNextWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** Tapping something coming up: the day's zmanim, the event, the alarms. */
    var onTap: ((Glance.Item, ClockInfo.Place?) -> Unit)? = null
    /** Without a location: where to choose one. */
    var onLocation: (() -> Unit)? = null
    var solid = true
        set(value) {
            field = value
            refresh()
        }

    private val glance = Glance(context)
    override val maxHeight = MAX_HEIGHT
    /** Wide enough for the weather beside what's coming up. */
    private var roomy = true

    init {
        pad(dp(GAP + 22), dp(GAP + 12), dp(GAP + 12), dp(GAP + 12))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= dp(250)
        if (next != roomy) {
            roomy = next
            post { refresh() }
        }
    }

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        if (solid) setCard(WidgetStyle.cardBackground(context)) else clearCard()
        val main = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE
        val quiet = if (solid) WidgetStyle.onCardQuiet() else WidgetStyle.SOFT_WHITE
        val now = System.currentTimeMillis()
        val lines = glance.lines(now) { if (isAttachedToWindow) refresh() }
        val coming = lines.coming
        val first = coming.firstOrNull()

        val (icon, title, detail) = when {
            first != null -> Triple(first.icon, first.name, whenText(first, now))
            lines.place == null -> Triple(R.drawable.ic_location, "Set your location", listOf("For zmanim and weather"))
            else -> Triple(R.drawable.ic_calendar, android.text.format.DateFormat.format("EEEE, MMMM d", now).toString(),
                listOf(lines.detail?.substringBefore(" · ") ?: "Nothing coming up"))
        }
        // Narrow, the weather makes way for what's coming up ([roomy]).
        setOnClickListener {
            when {
                first != null -> onTap?.invoke(first, lines.place)
                lines.place == null -> onLocation?.invoke()
            }
        }

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Lines that don't fit are left out, the last first.
            addView(FitColumn(context).apply {
                addView(text(main, 18f, WidgetStyle.title).apply {
                    text = title
                    maxLines = 2
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(main)
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                    addView(fitText(main, 15f).apply { options = detail })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                // Where the event is, otherwise what comes after, when there's room.
                val next = coming.getOrNull(1)
                val (thirdIcon, third) = when {
                    first?.event?.location != null -> R.drawable.ic_location to listOf(first.event.location)
                    next != null -> next.icon to listOf("Then ${next.text}", next.text)
                    else -> null to null
                }
                if (third != null) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        setImageResource(thirdIcon!!)
                        imageTintList = ColorStateList.valueOf(quiet)
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                    addView(fitText(quiet, 15f).apply { options = third })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            lines.weather?.takeIf { roomy }?.let { w ->
                // The weather now: its picture over the temperature, on a square tile in the accent
                // color, as tall as the card's room. Its corners run parallel to the card's, the
                // padding in from them.
                addView(Tile(context, dp(TILE)).apply {
                    background = Ui.rounded(context, WidgetStyle.accent(), WidgetStyle.RADIUS - 12)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                        addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, WidgetStyle.onAccent()).apply { text = "${w.temperature}°" },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * When it is, longest first, for [FitText]: "in 25 min · 4:00 – 5:00 PM" (within three hours),
     * then shorter ways of saying it, down to "4:00 PM" (or "Tomorrow").
     */
    private fun whenText(c: Glance.Item, now: Long): List<String> {
        val at = c.clock ?: return listOf(c.at.orEmpty())
        // An event's hours; its start alone is shorter.
        val start = at.substringBefore(" – ")
        val minutes = ((c.time ?: return listOf(at, start).distinct()) - now + 59_999) / 60_000
        val soon = when {
            minutes < 1 -> "now"
            minutes < 60 -> "in $minutes min"
            minutes < 180 -> if (minutes % 60 == 0L) "in ${minutes / 60} hr" else "in ${minutes / 60} hr ${minutes % 60} min"
            else -> return listOf(at, start).distinct()
        }
        return listOf("$soon · $at", "$soon · $start", soon).distinct()
    }

    private fun fitText(color: Int, sp: Float) = FitText(context).apply {
        setTextColor(color)
        typeface = WidgetStyle.body
        includeFontPadding = false
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        if (!solid) setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x59000000)
    }

    /** Text on the card, or white with a soft shadow on the wallpaper. */
    private fun text(color: Int, sp: Float, face: android.graphics.Typeface = WidgetStyle.body) =
        if (solid) WidgetStyle.cardText(context, sp, face, color) else WidgetStyle.text(context, sp, face, color)

    /** A square as tall as the card's room, up to [max] pixels. */
    private class Tile(context: Context, private val max: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val room = MeasureSpec.getSize(heightMeasureSpec)
            val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || room == 0) max else minOf(room, max)
            val exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
            super.onMeasure(exact, exact)
        }
    }

    private companion object {
        /** The tallest it gets, card and all, in dp. */
        const val MAX_HEIGHT = 112
        /** The weather tile's size, in dp. */
        const val TILE = 64
    }
}

/**
 * Today, in two columns: on the left the date (the weekday, the day of the month large in the
 * accent color, and the Hebrew date with the Hebrew calendar); on the right what's coming up,
 * each event in its calendar's color: the rest of today, then the next two weeks under each
 * day's name. The events scroll when there are more than fit.
 */
@SuppressLint("ViewConstructor")
class CalendarWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onEvent: ((ClockInfo.Event) -> Unit)? = null
    var onCalendar: (() -> Unit)? = null
    /** Too narrow for the date beside the events: it goes on a line above them. */
    private var narrow = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w < dp(260)
        if (next != narrow) {
            narrow = next
            post { refresh() }
        }
    }

    override fun refresh() {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return prompt(R.drawable.ic_calendar, "Calendar", "Allow calendar access to see your events") {
                allow(Setup.items.first { it.title == "Calendar" })
            }
        }
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onCalendar?.invoke() }
        val now = System.currentTimeMillis()
        val info = ClockInfo(context)
        val place = info.place()
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        val second = Prefs(context).let { it.secondCalendar.format(now, sunset, it.hebrewInHebrew) }

        val events = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Centred while they fit; from the top, scrolling, when there are more.
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(events, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (narrow) {
            // "SUN 1 · 21 Cheshvan" over the events, which get the whole width.
            content.gravity = Gravity.TOP
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.title, WidgetStyle.accent()).apply {
                    text = android.text.format.DateFormat.format("EEE d", now).toString().uppercase()
                    letterSpacing = 0.06f
                })
                if (second != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.bold, WidgetStyle.onCardQuiet()).apply {
                    text = second
                    setPadding(dp(8), 0, 0, 0)
                })
            })
            content.addView(FitColumn.dropFirst(scroll, -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        } else content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // The date opens the calendar, as the card does, so the keypad can reach it next to the events.
                background = Ui.pressable(context, Ui.RADIUS_S, focusColor = WidgetStyle.onCard())
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { onCalendar?.invoke() }
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.title, WidgetStyle.accent()).apply {
                    text = android.text.format.DateFormat.format("EEE", now).toString().uppercase()
                    letterSpacing = 0.08f
                })
                addView(WidgetStyle.cardText(context, 44f, WidgetStyle.display).apply {
                    text = android.text.format.DateFormat.format("d", now).toString()
                    setPadding(0, dp(2), 0, dp(2))
                })
                // Bold, the weight Hebrew has too (its font has no semibold).
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.bold).apply {
                    text = second ?: android.text.format.DateFormat.format("MMM", now).toString()
                    // On the left, in line with the day above it, in Hebrew too (which lines up on the right otherwise).
                    gravity = Gravity.LEFT
                })
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            // A thin line between the date and the events.
            addView(View(context).apply { setBackgroundColor((WidgetStyle.onCard() and 0xFFFFFF) or 0x1F000000) },
                LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(10); marginEnd = dp(14); topMargin = dp(6); bottomMargin = dp(6)
                })
            addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = info.events(now, midnight, 20)
        today.forEachIndexed { i, e -> events.addView(gapAbove(event(e), if (i == 0) 0 else 10)) }
        // The days after, each under its name, as far as two weeks ahead.
        val later = info.events(midnight, midnight + 14 * DAY, 40).filter { it.begin >= midnight || it.allDay }
        if (later.isEmpty() && today.isEmpty()) {
            events.addView(quiet("No events in the next two weeks"))
            return
        }
        if (today.isEmpty()) events.addView(quiet("Nothing else today"))
        var day = -1L
        later.forEach { e ->
            val start = dayOf(maxOf(e.begin, midnight))
            if (start != day) {
                day = start
                events.addView(gapAbove(WidgetStyle.cardText(context, 12f, WidgetStyle.title, WidgetStyle.accent()).apply {
                    text = if (start == midnight) "TOMORROW"
                    else android.text.format.DateFormat.format("EEEE, MMM d", start).toString().uppercase()
                    letterSpacing = 0.06f
                }, 16))
            }
            events.addView(gapAbove(event(e), 8))
        }
    }

    /** Midnight at the start of [t]'s day. */
    private fun dayOf(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** One event: a bar in its calendar's color, then its name over its time. */
    private fun event(e: ClockInfo.Event) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.pressable(context, Ui.RADIUS_S, focusColor = WidgetStyle.onCard())
        setOnClickListener { onEvent?.invoke(e) }
        addView(View(context).apply {
            background = Ui.rounded(context, if (e.color != 0) e.color or 0xFF000000.toInt() else WidgetStyle.accent(), 2)
        }, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.label).apply { text = e.title })
            addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
                val hours = if (e.allDay) "All day" else "${time(e.begin)} – ${time(e.end)}"
                text = hours
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}

/**
 * The weather on a card that looks like the sky: blue on a clear day, deep blue at night,
 * gray for clouds, slate for rain. The temperature large with a picture of the weather, today's
 * high and low and chance of rain, and the next hours with theirs.
 */
@SuppressLint("ViewConstructor")
class WeatherWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** A short card (a small screen): the top on one line. */
    private var compact = false
    /** A narrow card: just the weather now, stacked, as in iOS's small widget. */
    private var narrow = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val nextCompact = h < dp(250)
        val nextNarrow = w < dp(260)
        if (nextCompact != compact || nextNarrow != narrow) {
            compact = nextCompact
            narrow = nextNarrow
            post { refresh() }
        }
    }

    override fun refresh() {
        content.removeAllViews()
        content.spread = false
        val place = ClockInfo(context).place { if (isAttachedToWindow) refresh() }
        if (place == null) return prompt(R.drawable.ic_location, "Weather", "Set your location to see the weather", ::chooseLocation)
        setOnClickListener(null)
        val w = Weather.at(place) { if (isAttachedToWindow) refresh() }
        if (w == null) return loading(place.name)
        setCard(WeatherArt.sky(w.sky, w.night, dp(WidgetStyle.RADIUS).toFloat()))
        // Room left over goes between the rows, not above and below them.
        content.spread = true
        val white = WidgetStyle.WHITE
        val soft = 0xD9FFFFFF.toInt()
        content.gravity = Gravity.CENTER_VERTICAL
        if (narrow) {
            content.spread = false
            // When short, the high and low go first, then what the weather is, then the place;
            // the temperature stays.
            content.addView(FitColumn.dropFirst(WidgetStyle.cardText(context, 14f, WidgetStyle.title, white).apply {
                text = place.name
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, 1))
            content.addView(FitColumn.dropFirst(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(WidgetStyle.cardText(context, 40f, WidgetStyle.display, white).apply { text = "${w.temperature}°" },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }, -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            content.addView(FitColumn.dropFirst(WidgetStyle.cardText(context, 13f, WidgetStyle.label, white).apply {
                text = w.description
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }, 2))
            content.addView(FitColumn.dropFirst(WidgetStyle.cardText(context, 13f, WidgetStyle.label, soft).apply {
                text = listOfNotNull("↑${w.high}°  ↓${w.low}°", w.rainChance.takeIf { it >= 20 }?.let { "$it% rain or snow" }).joinToString("  ·  ")
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }, 3))
            // Taller, the coming days under it, as many as fit.
            w.days.take(5).forEachIndexed { i, d ->
                content.addView(FitColumn.dropFirst(gapAbove(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, white).apply {
                        text = if (i == 0) "Today" else android.text.format.DateFormat.format("EEE", d.time + 12 * 3_600_000L).toString()
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(d.sky, false)) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.body, soft).apply { text = "${d.low}°" },
                        LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, white).apply {
                        text = "${d.high}°"
                        gravity = Gravity.END
                    }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
                }, if (i == 0) 14 else 6), 4))
            }
            return
        }
        // In a short card everything up top fits on one line, leaving room for the hours.
        if (compact) {
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
                addView(WidgetStyle.cardText(context, 34f, WidgetStyle.display, white).apply { text = "${w.temperature}°" })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, white).apply { text = "${place.name} · ${w.description}" })
                    addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, soft).apply {
                        text = listOfNotNull("↑${w.high}°  ↓${w.low}°", w.rainChance.takeIf { it >= 20 }?.let { "$it% rain or snow" }).joinToString("  ·  ")
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
        } else
        // The place, the temperature and the weather on the left; its picture on the right.
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, white).apply { text = place.name })
                addView(WidgetStyle.cardText(context, 46f, WidgetStyle.display, white).apply {
                    text = "${w.temperature}°"
                    setPadding(0, dp(2), 0, dp(2))
                })
                addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, white).apply { text = w.description })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) },
                    LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.END })
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, white).apply {
                    text = "↑${w.high}°  ↓${w.low}°"
                    gravity = Gravity.END
                    setPadding(0, dp(4), 0, 0)
                })
                if (w.rainChance >= 20) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, soft).apply {
                    text = "${w.rainChance}% rain or snow"
                    gravity = Gravity.END
                    setPadding(0, dp(2), 0, 0)
                })
            })
        })
        // The next hours evenly across the card, then a line per day, as many as fit, so a taller
        // card shows more days instead of empty space. Days are always lines, never a row like
        // the hours: they're a different thing. Lines go before the hours when there's no room.
        content.addView(FitColumn.dropFirst(gapAbove((forecast(w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(5).map { h ->
            Column(android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h a", h.time).toString(),
                h.sky, h.night, "${h.temperature}°", null, h.rain)
        })), if (compact) 8 else 12), priority = 0))
        if (w.days.isNotEmpty()) {
            val low = w.days.minOf { it.low }
            val high = w.days.maxOf { it.high }
            val rainy = w.days.any { it.rain >= 20 }
            w.days.forEachIndexed { i, d ->
                // Midday: the forecast gives each day's start at today's offset from UTC, so after
                // the clocks change it lands late on the day before.
                val name = if (i == 0) "Today" else android.text.format.DateFormat.format("EEE", d.time + 12 * 3_600_000L).toString()
                val line = dayLine(name, d, low, high, rainy)
                content.addView(FitColumn.dropFirst(gapAbove(if (i == 0) divided(line) else line, if (i == 0) 10 else 4)))
            }
        }
    }

    /**
     * While the weather loads: the card as it will look, the place's name and soft shapes where
     * the temperature and the hours will be, on a calm sky.
     */
    private fun loading(name: String) {
        setCard(WeatherArt.sky(Sky.CLOUDY, false, dp(WidgetStyle.RADIUS).toFloat()))
        val soft = WidgetStyle.WHITE
        content.gravity = Gravity.CENTER_VERTICAL
        content.spread = !narrow
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, WidgetStyle.WHITE).apply {
                    text = name
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(placeholder(84, 40, soft).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })
                addView(placeholder(64, 12, soft).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!narrow) addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x26FFFFFF)
                }
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
        })
        if (narrow) return
        content.addView(FitColumn.dropFirst(gapAbove(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(5) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(placeholder(24, 10, soft))
                    addView(placeholder(24, 24, soft).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
                    addView(placeholder(24, 10, soft).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, 12)))
    }

    /** [row] under a thin line across the card, setting the days apart from what's above in a tall card. */
    private fun divided(row: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    /** A day in a tall card: its name, picture, low, a bar for its range within the week's, and high. */
    private fun dayLine(label: String, d: Weather.Day, low: Int, high: Int, rainy: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title, WidgetStyle.WHITE).apply { text = label },
            LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(d.sky, false)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        if (rainy) addView(WidgetStyle.cardText(context, 12f, WidgetStyle.label, RAIN).apply {
            text = if (d.rain >= 20) "${d.rain}%" else ""
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(WidgetStyle.cardText(context, 15f, WidgetStyle.body, SOFT).apply {
            text = "${d.low}°"
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        val span = (high - low).coerceAtLeast(1).toFloat()
        addView(RangeBar(context, (d.low - low) / span, (d.high - low) / span), LinearLayout.LayoutParams(0, dp(5), 1f))
        addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title, WidgetStyle.WHITE).apply {
            text = "${d.high}°"
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** A faint track with the part from [from] to [to] (fractions of its length) filled in. */
    private class RangeBar(context: Context, val from: Float, val to: Float) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = 0xE6FFFFFF.toInt()
            // At least a dot, for a day whose low and high are the same.
            val start = from * width
            canvas.drawRoundRect(start, 0f, maxOf(to * width, start + height), height.toFloat(), r, r, paint)
        }
    }

    /** One time in a forecast row: a label, the weather's picture, the temperature (a high and a low for days), the chance of rain. */
    private class Column(val label: String, val sky: Sky, val night: Boolean, val temperature: String, val low: String?, val rain: Int)

    private fun forecast(columns: List<Column>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // The line for the chance of rain only takes room when there's rain to show.
        val rainy = columns.any { it.rain >= 20 }
        columns.forEach { c ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(WidgetStyle.cardText(context, 12f, WidgetStyle.label, SOFT).apply {
                    gravity = Gravity.CENTER
                    text = c.label
                })
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(c.sky, c.night)) },
                    LinearLayout.LayoutParams(dp(if (compact) 24 else 28), dp(if (compact) 24 else 28)).apply { topMargin = dp(3); bottomMargin = dp(2) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, WidgetStyle.WHITE).apply { text = c.temperature })
                    if (c.low != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, SOFT).apply {
                        text = c.low
                        setPadding(dp(4), 0, 0, 0)
                    })
                })
                if (rainy) addView(WidgetStyle.cardText(context, 11f, WidgetStyle.label, RAIN).apply {
                    gravity = Gravity.CENTER
                    text = if (c.rain >= 20) "${c.rain}%" else " "
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private companion object {
        const val SOFT = 0xD9FFFFFF.toInt()
        /** A chance of rain, in the color of rain drops. */
        const val RAIN = 0xFFB3E0FF.toInt()
    }
}

/**
 * What's playing in any app, the way Android's own media player shows it: the card takes its
 * color from the artwork, the artwork fills its height, then the song and who it's by, and
 * buttons to go back, play or pause, and skip, as large as the card allows.
 */
@SuppressLint("ViewConstructor")
class MediaWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationService::class.java)
    private var controller: MediaController? = null
    private val changed = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    // All one row: taller than this, it sits centred rather than growing a huge picture.
    override val maxHeight = 128
    /** Narrow: a small picture, the title on up to two lines, and only play and pause. */
    private var narrow = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w < dp(260)
        if (next != narrow) {
            narrow = next
            post { refresh() }
        }
    }

    init {
        // Less room above and below than other cards: a 1-row card is short, and this one is all one row.
        pad(dp(GAP + 14), dp(GAP + 12), dp(GAP + 14), dp(GAP + 12))
    }

    override fun onAttachedToWindow() {
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener) }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(changed)
        controller = null
        super.onDetachedFromWindow()
    }

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        if (!NotificationService.isEnabled(context)) return prompt(R.drawable.ic_media_music, "Now playing",
            "Allow notification access to control what's playing") { allow(Setup.NOTIFICATIONS) }
        setOnClickListener(null)
        // The one playing, else the last one that was.
        val all = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())
        val c = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull { it.metadata != null }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(changed)
            controller = c
            c?.registerCallback(changed)
        }
        val meta = c?.metadata
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = meta?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        // With artwork, the card is a deep shade of its color, with white type; without, the usual card.
        val tint = art?.let(::shadeOf)
        setCard(if (tint != null) Ui.rounded(context, tint, WidgetStyle.RADIUS) else WidgetStyle.cardBackground(context))
        val main = if (tint != null) WidgetStyle.WHITE else WidgetStyle.onCard()
        val quiet = if (tint != null) 0xCCFFFFFF.toInt() else WidgetStyle.onCardQuiet()

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Square(context, if (narrow) dp(48) else Int.MAX_VALUE).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = Ui.rounded(context, if (tint != null) 0x33FFFFFF else WidgetStyle.accentFill(), 16)
                when {
                    art != null -> setImageBitmap(art)
                    c != null -> {
                        runCatching { setImageDrawable(context.packageManager.getApplicationIcon(c.packageName)) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    }
                    else -> {
                        setImageResource(R.drawable.ic_media_music)
                        imageTintList = ColorStateList.valueOf(WidgetStyle.onAccentFill())
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(if (narrow) 10 else 14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, if (narrow) 14f else 16f, WidgetStyle.title, main).apply {
                    text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
                    if (narrow) maxLines = 2
                })
                if (narrow) return@apply
                val by = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: c?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                    ?: "Music and podcasts show here"
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, quiet).apply {
                    text = by
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (c != null) {
                val fill = if (tint != null) WidgetStyle.WHITE else WidgetStyle.accent()
                val onFill = tint ?: WidgetStyle.onAccent()
                if (!narrow) addView(button(R.drawable.ic_previous, main, null) { c.transportControls.skipToPrevious() })
                addView(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play, onFill, fill) {
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                })
                if (!narrow) addView(button(R.drawable.ic_next, main, null) { c.transportControls.skipToNext() })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A round button the keypad can reach too, as tall as the card allows up to 44dp; [fill] makes it solid. */
    private fun button(icon: Int, color: Int, fill: Int?, onClick: () -> Unit) = Square(context, dp(44)).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(if (fill != null) 11 else 9)
        setPadding(inset, inset, inset, inset)
        background = fill?.let { Ui.rounded(context, it, Ui.RADIUS_PILL) }
        foreground = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = WidgetStyle.onCard())
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(2)
        }
    }

    /** A deep, rich shade of the artwork's overall color, dark enough for white type. */
    private fun shadeOf(art: android.graphics.Bitmap): Int {
        val average = runCatching { android.graphics.Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) }.getOrDefault(0xFF333333.toInt())
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(average, hsv)
        hsv[1] = hsv[1].coerceIn(0.25f, 0.7f)
        hsv[2] = 0.34f
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** An image as wide as it is tall: as tall as its row lets it be, up to [max] pixels. */
    private class Square(context: Context, private val max: Int) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = minOf(MeasureSpec.getSize(heightMeasureSpec), max)
            setMeasuredDimension(size, size)
        }
    }
}

/** The day's coming zmanim: the next one in a highlighted row, then the ones after it. */
@SuppressLint("ViewConstructor")
class ZmanimWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onOpen: ((ClockInfo.Place) -> Unit)? = null
    /** Narrow: smaller type and less room around each time, so their names fit. */
    private var narrow = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w < dp(260)
        if (next != narrow) {
            narrow = next
            post { refresh() }
        }
    }

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.TOP
        val info = ClockInfo(context)
        val place = info.place { if (isAttachedToWindow) refresh() }
        val zmanim = Prefs(context).dailyZmanim
        if (place == null) return prompt(R.drawable.ic_sunrise, if (zmanim) "Zmanim" else "Sunrise and sunset",
            if (zmanim) "Set your location to see today's zmanim" else "Set your location to see today's times", ::chooseLocation)
        setCard(WidgetStyle.cardBackground(context))
        content.addView(heading(R.drawable.ic_sunrise, if (zmanim) "Zmanim" else "Sunrise and sunset", place.name))
        setOnClickListener { onOpen?.invoke(place) }
        val now = System.currentTimeMillis()
        val today = info.zmanim(now, place).filter { it.time > now }
        val coming = (today + info.zmanim(now + 86_400_000L, place)).take(12)
        coming.forEachIndexed { i, z ->
            val next = i == 0
            // Where the list goes on into tomorrow, it says so.
            if (i == today.size) content.addView(gapAbove(WidgetStyle.cardText(context, 12f, WidgetStyle.title, WidgetStyle.accent()).apply {
                text = "TOMORROW"
                letterSpacing = 0.06f
                setPadding(dp(if (narrow) 8 else 12), 0, 0, 0)
            }, if (i == 0) 10 else 12))
            content.addView(gapAbove(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The next one stands out in a rounded row of the accent's soft fill.
                val side = dp(if (narrow) 8 else 12)
                if (next) {
                    background = Ui.rounded(context, WidgetStyle.accentFill(), 14)
                    setPadding(side, dp(8), side, dp(8))
                } else {
                    setPadding(side, dp(2), side, dp(2))
                }
                val sp = if (narrow) 14f else 15f
                val color = if (next) WidgetStyle.onAccentFill() else WidgetStyle.onCard()
                addView(WidgetStyle.cardText(context, sp, if (next) WidgetStyle.title else WidgetStyle.body, color).apply { text = z.name },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(if (narrow) 8 else 12) })
                addView(WidgetStyle.cardText(context, sp, if (next) WidgetStyle.title else WidgetStyle.body,
                    if (next) color else WidgetStyle.onCardQuiet()).apply { text = time(z.time, place.zone) })
            }, if (next) 10 else 4))
        }
    }
}

/**
 * Holding anywhere on a widget picks it up, even on an event or a button inside it, which would
 * otherwise take the touch as its own: after the hold, the rest of the gesture is the drag's, and
 * lifting the finger doesn't tap what's under it. [view] passes its touches through [touch] and
 * its long clicks through [longClick].
 */
class WholeHold(private val view: android.view.ViewGroup) {
    private var downX = 0f
    private var downY = 0f
    private var held = false
    private var dispatch: ((android.view.MotionEvent) -> Boolean)? = null
    private val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
    private val hold = Runnable {
        val now = android.os.SystemClock.uptimeMillis()
        // The button or event under the finger lets go, so lifting it doesn't tap it.
        android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, downX, downY, 0).let {
            dispatch?.invoke(it)
            it.recycle()
        }
        view.performLongClick()
    }

    fun touch(ev: android.view.MotionEvent, superDispatch: (android.view.MotionEvent) -> Boolean): Boolean {
        dispatch = superDispatch
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                held = false
                view.removeCallbacks(hold)
                view.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            android.view.MotionEvent.ACTION_MOVE ->
                if (kotlin.math.abs(ev.x - downX) > slop || kotlin.math.abs(ev.y - downY) > slop) view.removeCallbacks(hold)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.removeCallbacks(hold)
        }
        // After the hold, the rest of the gesture is the drag's, not the widget's.
        if (held && ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return true
        return superDispatch(ev)
    }

    fun longClick(superLongClick: () -> Boolean): Boolean {
        // The widget's own hold and this one can both fire; once is enough.
        if (held) return true
        held = view.isInTouchMode
        view.removeCallbacks(hold)
        return superLongClick()
    }
}
