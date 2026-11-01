package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** An app on the home screen or in the dock: icon, with its name underneath outside the dock. */
@SuppressLint("ViewConstructor")
class AppIconView(context: Context, val item: HomeItem) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = NameView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        // The keypad highlight is drawn on the icon only, an even square around it.
        icon.setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET))
        icon.isDuplicateParentStateEnabled = true
        icon.background = Ui.focusHighlight(context, ICON_RADIUS + INSET)
        addView(icon, LayoutParams(dp(ICON + 2 * INSET), dp(ICON + 2 * INSET)))
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(4), 0, dp(4), 0)
            height = dp(LABEL_HEIGHT)
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pressScale()
    }

    /** Icon size in dp: [ICON] on pages, [DOCK_ICON] in the dock. */
    private var size = ICON

    fun bind(bitmap: Bitmap?, name: String, showLabel: Boolean, locked: Boolean = false, isNew: Boolean = false,
             badge: Int = 0, iconDp: Int = ICON) {
        if (iconDp != size) {
            size = iconDp
            icon.layoutParams = LayoutParams(dp(size + 2 * INSET), dp(size + 2 * INSET))
            icon.background = Ui.focusHighlight(context, ICON_RADIUS * size / ICON + INSET)
        }
        icon.setImageBitmap(bitmap)
        IconBadges.apply(icon, locked, isNew, size + 2 * INSET, badge)
        label.text = name
        label.visibility = if (showLabel) VISIBLE else GONE
        contentDescription = name
    }

    /** The icon's bounds in this view, for the keypad highlight and drag image. */
    val iconView: ImageView get() = icon

    /** An app's name on one line: its letters drawn a little closer when that makes it fit, else cut short with an ellipsis. */
    private class NameView(context: Context) : TextView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val room = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
            val name = text.toString()
            fun width(spacing: Float) = android.text.TextPaint(paint).apply { letterSpacing = spacing }.measureText(name)
            val spacing = if (width(0f) > room && width(TIGHT) <= room) TIGHT else 0f
            // Only when it changes, since setting it asks for another layout.
            if (letterSpacing != spacing) letterSpacing = spacing
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    companion object {
        const val ICON = 56
        /**
         * Dock icons are larger, as in Smart Launcher: with the same padding around them,
         * smaller icons leave wide gaps between them and the dock looks spread out.
         */
        const val DOCK_ICON = 62
        const val INSET = 6
        /** Roughly the corner radius of a squircle icon at [ICON]. */
        const val ICON_RADIUS = 16
        /** Height of the icon with its highlight, which is also a dock cell's height. */
        const val BOX = ICON + 2 * INSET
        /** Fixed height of the name under the icon, so icons line up across rows. */
        const val LABEL_HEIGHT = 16
        /** How much closer a name's letters may be drawn to fit on its line, in ems. */
        private const val TIGHT = -0.05f
    }
}

/**
 * The built-in clock, always centred. On top, one small line about the day: the date, the
 * Hebrew date, the parsha and the like. Under it the time, as large as the widget
 * allows, and under that the weather and what's coming up. Transparent, straight on the
 * wallpaper in white, or Solid, on a card in the wallpaper's colors ([Prefs.widgetSolid]).
 */
@SuppressLint("ViewConstructor")
class ClockWidget(context: Context, val item: HomeItem) : FitColumn(context) {
    /** The time on one line. */
    private val time = clockText("h:mm", "H:mm")
    /** The hours over the minutes, as Android's own tall clock has them: two lines drawn apart, so each is measured whole. */
    private val hours = clockText("hh", "HH")
    private val minutes = clockText("mm", "mm")
    private val stackedTime = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = GONE
        addView(hours)
        addView(minutes)
    }
    /** "Sun, Nov 1 · 10 Cheshvan · Lech Lecha", over two lines if it must. */
    private val day = WidgetStyle.text(context, 15f, WidgetStyle.body)
    /** The weather and up to two things coming up. */
    private val pills = FitRow(context).apply { gap = Ui.dp(context, 14) }
    private val glance = Glance(context)
    private var place: ClockInfo.Place? = null
    private var solid = false
    /** The text sizes, in pixels, worked out from the widget's width (see [sizeFor]). */
    private var pillPx = 30f
    private var sizedFor = 0
    /** The time's size on one line and stacked, before it's made smaller to fit. */
    private var timePx = 200f
    private var stackedPx = 200f

    /** Tapping something coming up: the day's zmanim, the event, the alarms. */
    var onTap: ((Glance.Item, ClockInfo.Place?) -> Unit)? = null
    /** Tapping the weather. */
    var onWeather: (() -> Unit)? = null

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    // Holding anywhere picks the clock up, on an event under the time too ([WholeHold]).
    private val hold = WholeHold(this)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent) = hold.touch(ev) { super.dispatchTouchEvent(it) }

    override fun performLongClick() = hold.longClick { super.performLongClick() }

    private fun clockText(format12: String, format24: String) = TextClock(context).apply {
        format12Hour = format12
        format24Hour = format24
        typeface = WidgetStyle.display
        letterSpacing = -0.02f
        includeFontPadding = false
        // Room for the soft shadow, which would otherwise be cut off square at the text's edges
        // (the margins below take the room back, so nothing moves).
        setPadding(dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM))
    }

    init {
        isFocusable = true
        gravity = Gravity.CENTER
        // The text's soft shadows reach past their own edges.
        clipChildren = false
        clipToPadding = false
        day.apply {
            maxLines = 2
            gravity = Gravity.CENTER
        }
        // In a short clock the line under the time goes first, then this one; the time stays.
        addView(FitColumn.dropFirst(day, 1), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val room = -dp(SHADOW_ROOM)
        for (shown in listOf(time, stackedTime)) {
            addView(FitColumn.dropFirst(shown, -1), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(room, room + dp(2), room, room)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            // The time does what the clock does (its alarms), so the keypad can reach it like the date and pills.
            shown.setOnClickListener { performClick() }
            shown.setOnLongClickListener { performLongClick() }
            // Its highlight hugs the digits, inside the room left for the shadow, so it stays within the clock.
            shown.foreground = android.graphics.drawable.InsetDrawable(Ui.focusRing(context, Ui.RADIUS_L, WidgetStyle.WHITE), dp(SHADOW_ROOM))
        }
        // Pulled up into the room under the digits (they have no descenders), so the space below the
        // time looks the same as the space above it.
        addView(FitColumn.dropFirst(pills, 2), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = -dp(5) })
        setSolid(false)
    }

    /** The space last measured for and what fitted it, so the next pass changes nothing. */
    private var fitted: Fit? = null
    private class Fit(val width: Int, val height: Int, val choice: Choice)
    /** The time stacked or on one line, at [scale] of its size for the widget's width. */
    private data class Choice(val stacked: Boolean, val scale: Float, val px: Float)

    private val stacked get() = stackedTime.visibility == VISIBLE

    /**
     * The time is as large as the widget lets it be, on one line or, where that makes it larger,
     * the hours over the minutes. The lines above and below it stay unless keeping them would make
     * the time much smaller than without them ([KEEP]); then the one under it goes first.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        sizeFor(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        val choice = fitted?.takeIf { it.width == widthMeasureSpec && it.height == heightMeasureSpec }?.choice ?: run {
            val tall = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
            fun tries(lines: Boolean) = listOfNotNull(
                fit(widthMeasureSpec, heightMeasureSpec, false, lines),
                if (tall) fit(widthMeasureSpec, heightMeasureSpec, true, lines) else null,
            ).maxByOrNull { it.px }
            val whole = tries(lines = true)
            val any = tries(lines = false)
            (if (whole != null && (any == null || whole.px >= any.px * KEEP)) whole else any) ?: Choice(false, MIN_SCALE, timePx * MIN_SCALE)
        }
        stack(choice.stacked)
        setTime(choice.px)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        fitted = Fit(widthMeasureSpec, heightMeasureSpec, choice)
    }

    /**
     * The time [stacked] or not at the largest scale at which it fits, keeping every line if
     * [lines], else with lines left out as needed; null if nothing down to [MIN_SCALE] does.
     */
    private fun fit(widthMeasureSpec: Int, heightMeasureSpec: Int, stacked: Boolean, lines: Boolean): Choice? {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (stacked) stackedPx else timePx
        stack(stacked)
        var scale = 1f
        while (scale >= MIN_SCALE - 0.001f) {
            setTime(size * scale)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val shown = (0 until childCount).map(::getChildAt)
            val cut = shown.any { it.getTag(R.id.fit_hidden) == true && it.visibility == GONE }
            // Each row's own height, not squeezed into what's left (which always fits).
            val across = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, MeasureSpec.AT_MOST)
            val used = paddingTop + paddingBottom + shown.filter { it.visibility != GONE }.sumOf {
                val lp = it.layoutParams as MarginLayoutParams
                it.measure(across, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                it.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            val fits = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || used <= height
            if (fits && (!lines || !cut)) return Choice(stacked, scale, size * scale)
            scale -= 0.05f
        }
        return null
    }

    private fun stack(on: Boolean) {
        time.visibility = if (on) GONE else VISIBLE
        stackedTime.visibility = if (on) VISIBLE else GONE
    }

    /** The time's size in pixels; stacked, the minutes tuck up under the hours, as close as digits allow. */
    private fun setTime(px: Float) {
        if (!stacked) return time.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        hours.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        minutes.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        // Digits fill less than a line's height; the room above and below them overlaps, leaving a small gap.
        (minutes.layoutParams as LinearLayout.LayoutParams).topMargin = -(2 * dp(SHADOW_ROOM) + (0.34f * px).toInt())
    }

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

    /** The text's color: the card's, or white on the wallpaper. */
    private val main get() = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE

    /** Solid: on a card with the cards' corners; transparent: straight on the wallpaper. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        background = if (solid) android.graphics.drawable.InsetDrawable(WidgetStyle.cardBackground(context), dp(CardWidget.GAP)) else null
        // The same room on every side of the card, and the same without one, so both styles lay out alike.
        val edge = dp(CardWidget.GAP + 20)
        setPadding(edge, edge, edge, edge)
        listOf(time, hours, minutes).forEach { it.setTextColor(if (solid) WidgetStyle.accent() else WidgetStyle.WHITE) }
        listOf(time, hours, minutes, day).forEach { shade(it) }
        sizedFor = 0
        fitted = null
        refresh()
        requestLayout()
    }

    /** The soft shadow on the wallpaper; none on a card. */
    private fun shade(text: TextView) {
        if (solid) text.setShadowLayer(0f, 0f, 0f, 0)
        else text.setShadowLayer(dp(if (text is TextClock) 10 else 6).toFloat(), 0f, dp(1).toFloat(), 0x59000000)
    }

    /** Works out the date and details again; runs every minute. */
    fun refresh() {
        val lines = glance.lines(System.currentTimeMillis()) { if (isAttachedToWindow) refresh() }
        place = lines.place
        // Each part stays whole: a long line breaks only between them. All in one color, and in
        // regular weight, the one Hebrew has too (its font has no medium).
        day.text = (listOf(lines.date) + lines.detail?.split(" · ").orEmpty()).joinToString(" · ") { it.replace(' ', '\u00A0') }
        day.setTextColor(main)
        pills.removeAllViews()
        // The same as Up next, the two most pressing: the clock is Up next with the time.
        val coming = lines.coming.take(2)
        lines.weather?.let { w ->
            pills.addView(pill(WeatherArt.icon(w.sky, w.night), "${w.temperature}°  ${w.description}", tint = false).apply {
                setOnClickListener { onWeather?.invoke() }
                // With two things coming up and room for only some, the weather makes way.
                if (coming.size >= 2) FitColumn.dropFirst(this)
            })
        }
        coming.forEach { item ->
            pills.addView(pill(context.getDrawable(item.icon), item.text, tint = true).apply {
                setOnClickListener { onTap?.invoke(item, place) }
            })
        }
        pills.visibility = if (pills.childCount > 0) VISIBLE else GONE
        fitted = null
    }

    private fun pill(icon: android.graphics.drawable.Drawable?, words: String, tint: Boolean) =
        WidgetStyle.pill(context, icon?.mutate()?.apply { if (tint) setTint(main) }, words, pillPx).apply {
            setTextColor(main)
            // Regular, like the line about the day above the time.
            typeface = WidgetStyle.body
            shade(this)
        }

    /**
     * Sizes the text from the widget's [width] in pixels, so it takes the same share of the screen
     * on every phone, whatever its display size setting. The line about the day and the pills
     * step down from the time in fixed proportions.
     */
    private fun sizeFor(width: Int) {
        if (width <= 0 || width == sizedFor) return
        sizedFor = width
        fitted = null
        val unit = width / 100f
        // As wide as the widget: four digits ("12:59") fill it, edge to edge.
        val paint = android.graphics.Paint().apply {
            typeface = time.typeface
            letterSpacing = time.letterSpacing
            textSize = 100f
        }
        timePx = 100f * width / paint.measureText("00:00")
        stackedPx = 100f * width / paint.measureText("00") * 0.8f
        // No smaller than can be read easily, however narrow the clock.
        val least = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        day.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(4.6f * unit, least))
        pillPx = maxOf(4.2f * unit, least)
        for (i in 0 until pills.childCount) (pills.getChildAt(i) as? TextView)?.let { WidgetStyle.sizePill(it, pillPx) }
    }

    val dateView: TextView get() = day
}

/** The smallest the clock's time is made, as a share of its size for the widget's width. */
private const val MIN_SCALE = 0.25f
/** The lines around the time stay unless the time would then be less than this share of its size without them. */
private const val KEEP = 0.6f

/** Padding around the clock's time so its shadow isn't clipped. */
private const val SHADOW_ROOM = 12
