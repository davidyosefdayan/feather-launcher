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
        icon.background = Ui.focusHighlight(context, corner(ICON) + INSET)
        addView(icon, LayoutParams(dp(ICON + 2 * INSET), dp(ICON + 2 * INSET)))
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP)
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(NAME_PADDING), 0, dp(NAME_PADDING), 0)
            height = dp(LABEL_HEIGHT)
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pressScale()
    }

    /** Icon size in dp: [ICON], or [LARGE_ICON] with names turned off. */
    private var size = ICON
    /** Its corners' radius in dp, for the keypad highlight around it. */
    private var corner = 0

    fun bind(bitmap: Bitmap?, name: String, showLabel: Boolean, locked: Boolean = false, isNew: Boolean = false,
             badge: Int = 0, iconDp: Int = ICON) {
        if (iconDp != size || corner != corner(iconDp)) {
            size = iconDp
            corner = corner(iconDp)
            icon.layoutParams = LayoutParams(dp(size + 2 * INSET), dp(size + 2 * INSET))
            icon.background = Ui.focusHighlight(context, corner + INSET)
        }
        // Coming home binds every icon again; most have nothing new, and each change redraws it.
        if (bitmap !== shownBitmap) {
            shownBitmap = bitmap
            icon.setImageBitmap(bitmap)
        }
        val marks = listOf(locked, isNew, size, badge)
        if (marks != shownMarks) {
            shownMarks = marks
            IconBadges.apply(icon, locked, isNew, size + 2 * INSET, badge)
        }
        if (label.text.toString() != name) label.text = name
        label.visibility = if (showLabel) VISIBLE else GONE
        contentDescription = name
    }

    private var shownBitmap: Bitmap? = null
    private var shownMarks: List<Any>? = null

    /** The icon's bounds in this view, for the keypad highlight and drag image. */
    val iconView: ImageView get() = icon

    /**
     * An app's name on one line: its letters drawn a little closer when that makes it fit, else
     * cut short with an ellipsis. Words on a notification's buttons too ([NotificationsWidget]).
     */
    class NameView(context: Context) : TextView(context) {
        init {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val room = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
            val spacing = nameSpacing(paint, text.toString(), room.toFloat())
            // Only when it changes, since setting it asks for another layout.
            if (letterSpacing != spacing) letterSpacing = spacing
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        // On one line, centred text scrolls the view sideways, and a foreground (a button's
        // keypad highlight) would be drawn off to that side; it stays on the view instead.
        override fun onDrawForeground(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            super.onDrawForeground(canvas)
            canvas.restore()
        }
    }

    companion object {
        /** An icon's name, a widget's too ([CellLayout]): its text size (sp) and the room at its sides (dp). */
        const val NAME_SP = 12f
        const val NAME_PADDING = 4

        /** The letter spacing for [name] in [room] px: a little closer when that makes it fit. */
        fun nameSpacing(paint: android.graphics.Paint, name: String, room: Float): Float {
            measuring.set(paint)
            fun width(spacing: Float) = measuring.apply { letterSpacing = spacing }.measureText(name)
            return if (width(0f) > room && width(TIGHT) <= room) TIGHT else 0f
        }

        /** A copy of the paint to measure with, made once rather than twice per name measured (on the main thread only). */
        private val measuring = android.text.TextPaint()

        /** The home screen's icons, on pages and in the dock alike, as on iOS. */
        const val ICON = 62
        /**
         * The home screen's icons with names turned off: bigger, into the room the names leave,
         * as iOS's Large icons, in the dock too.
         */
        const val LARGE_ICON = 70
        const val INSET = 6
        /** The icons' shape, from Settings; set by [HomeScreen]. */
        var shape = IconShape.SQUIRCLE

        /**
         * About how round, in dp, the corners of an icon [sizeDp] big are in [shape]. Widgets'
         * corners, the keypad highlights and the page's outline all follow it.
         */
        fun corner(sizeDp: Int) = kotlin.math.round(shape.corner * sizeDp).toInt()
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
    var solid = false
        private set
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

    private val tick = MinuteTick(this) { refresh() }
    /** What the date and pills were last made from, so a minute that changes neither leaves them be. */
    private var shownKey: Any? = null

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
    // It only makes anything new when it has to fit the time again (new text or a new size).
    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As it's measured, so a clock being resized lays out once, at its new size.
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) pad(MeasureSpec.getSize(heightMeasureSpec))
        sizeFor(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        val choice = fitted?.takeIf { it.width == widthMeasureSpec && it.height == heightMeasureSpec }?.choice ?: run {
            val tall = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
            val fits = listOfNotNull(
                fit(widthMeasureSpec, heightMeasureSpec, false),
                if (tall) fit(widthMeasureSpec, heightMeasureSpec, true) else null,
            )
            val whole = fits.mapNotNull { it.first }.maxByOrNull { it.px }
            val any = fits.mapNotNull { it.second }.maxByOrNull { it.px }
            (if (whole != null && (any == null || whole.px >= any.px * KEEP)) whole else any) ?: Choice(false, MIN_SCALE, timePx * MIN_SCALE)
        }
        stack(choice.stacked)
        setTime(choice.px)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (fitted?.choice !== choice) fitted = Fit(widthMeasureSpec, heightMeasureSpec, choice)
    }

    /**
     * The time [stacked] or not at the largest scale at which it fits keeping every line, and at
     * the largest at which it fits with lines left out as needed (one pass finds both: the second
     * comes first or at the same scale); null where nothing down to [MIN_SCALE] does.
     */
    // Measuring itself at each size tried is the point: the layout's own measure, not its children's.
    @SuppressLint("WrongCall")
    private fun fit(widthMeasureSpec: Int, heightMeasureSpec: Int, stacked: Boolean): Pair<Choice?, Choice?>? {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (stacked) stackedPx else timePx
        stack(stacked)
        var scale = 1f
        var any: Choice? = null
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
            if (fits) {
                val choice = Choice(stacked, scale, size * scale)
                if (any == null) any = choice
                if (!cut) return choice to any
            }
            scale -= 0.05f
        }
        return any?.let { null to it }
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
        tick.attach()
        refresh()
    }

    override fun onDetachedFromWindow() {
        tick.detach()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        tick.shown()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) tick.shown()
    }

    /** The text's color: the card's, or white on the wallpaper. */
    private val main get() = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE

    /** Solid: on a card with the cards' corners; transparent: straight on the wallpaper. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        background = if (solid) android.graphics.drawable.InsetDrawable(WidgetStyle.cardBackground(context), dp(CardWidget.GAP)) else null
        // The same room on every side of the card, and the same without one, so both styles lay out alike.
        pad()
        listOf(time, hours, minutes).forEach { it.setTextColor(if (solid) WidgetStyle.accent() else WidgetStyle.WHITE) }
        listOf(time, hours, minutes, day).forEach { shade(it) }
        sizedFor = 0
        fitted = null
        shownKey = null
        refresh()
        requestLayout()
    }

    /**
     * The room around the time, the same on every side; on a short clock (one row, an icon's
     * height) the room above and below is a share of its height, as on the other cards.
     */
    private fun pad(height: Int = 0) {
        val edge = dp(CardWidget.GAP + EDGE)
        val card = height - 2 * dp(CardWidget.GAP)
        val vertical = dp(CardWidget.GAP) + if (card > 0) minOf(dp(EDGE), (card * 0.15f).toInt()) else dp(EDGE)
        setPadding(edge, vertical, edge, vertical)
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
        val dayText = (listOf(lines.date) + lines.detail?.split(" · ").orEmpty()).joinToString(" · ") { it.replace(' ', '\u00A0') }
        // The same as Up next, the two most pressing: the clock is Up next with the time.
        val coming = lines.coming.take(2)
        // Most minutes neither changes; then the clock stays as it is, fitted.
        val key = listOf(dayText, main, lines.weather?.let { listOf(it.sky, it.night, it.temperature, it.description) },
            coming.map { listOf(it.icon, it.text, it.time, it.tap, it.event?.id) }, place?.name)
        if (key == shownKey) return
        shownKey = key
        day.text = dayText
        day.setTextColor(main)
        pills.removeAllViews()
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

/** The room (dp) between the clock's card and what's on it. */
private const val EDGE = 20

/** Padding around the clock's time so its shadow isn't clipped. */
private const val SHADOW_ROOM = 12
