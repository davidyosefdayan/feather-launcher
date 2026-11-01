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
    private val label = TextView(context)

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
            maxLines = 1
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
    }
}

/**
 * The built-in clock: the time (which can be hidden), the date, a quieter line with the Hebrew
 * date, the parsha and the like, then the weather and what's coming up as pills. Its layout options live in [ClockOptions]; the look in [WidgetStyle].
 */
@SuppressLint("ViewConstructor")
class ClockWidget(context: Context, val item: HomeItem) : FitColumn(context) {
    private val time = TextClock(context)
    private val date = WidgetStyle.text(context, 19f, WidgetStyle.title)
    /** The second calendar's date, today's holiday, the parsha, the Omer, Daf Yomi. */
    private val detail = WidgetStyle.text(context, 15f, WidgetStyle.body, WidgetStyle.SOFT_WHITE)
    /** The weather and up to two things coming up. */
    private val pills = FitRow(context).apply { gap = Ui.dp(context, 14) }
    private val glance = Glance(context)
    private var place: ClockInfo.Place? = null
    /** The text sizes, in pixels, worked out from the widget's width (see [sizeFor]). */
    private var pillPx = 30f
    private var sizedFor = 0
    private var sizeFactor = 1f
    /** The time's size, before it's made smaller to fit. */
    private var timePx = 200f
    /** The date's and the quieter line's sizes, before they're made larger with the time hidden. */
    private var datePx = 60f
    private var detailPx = 50f
    private val ring = Ui.focusRing(context, Ui.RADIUS_L, WidgetStyle.WHITE)
    /** How far the time's line reaches above its digits, last worked out by [spaceRows]. */
    private var timeHeadroom = -1
    private val digits = android.graphics.Rect()

    /** Tapping something coming up: the day's zmanim, the event, the alarms. */
    var onTap: ((Glance.Item, ClockInfo.Place?) -> Unit)? = null
    /** Tapping the weather. */
    var onWeather: (() -> Unit)? = null

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    init {
        isFocusable = true
        // In a tight space the pills go first, then the quieter line ([FitColumn]).
        // The text's soft shadows reach past their own edges.
        clipChildren = false
        clipToPadding = false
        time.apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(WidgetStyle.WHITE)
            typeface = WidgetStyle.display
            letterSpacing = -0.02f
            includeFontPadding = false
            setShadowLayer(dp(10).toFloat(), 0f, dp(1).toFloat(), 0x59000000)
            // Room for the soft shadow, which would otherwise be cut off square at the text's edges
            // (the margins below take the room back, so nothing moves).
            setPadding(dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM))
        }
        val room = -dp(SHADOW_ROOM)
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(room, room, room, room) })
        // The time does what the clock does (its alarms), so the keypad can reach it like the date and pills.
        time.setOnClickListener { performClick() }
        time.setOnLongClickListener { performLongClick() }
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(detail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // The pills' own padding below them is the clock's.
        addView(pills, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = -dp(WidgetStyle.PILL_PADDING) })
    }

    /** The space last measured for and the scales that fitted it, so the next pass changes nothing. */
    private class Fit(val width: Int, val height: Int, val time: Float, val lines: Float)
    private var fitted: Fit? = null

    /**
     * In a tight space, a smaller time comes first; only then are lines left out. Without the
     * time, the date and the quieter line take its place: they fill the width inside the same
     * padding as the pills, as far as the height allows without leaving out anything more.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        sizeFor(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        fitted?.takeIf { it.width == widthMeasureSpec && it.height == heightMeasureSpec }?.let {
            scale(it.time, it.lines)
            return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        if (time.visibility == GONE) return fillWithLines(widthMeasureSpec, heightMeasureSpec) { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        for (scale in listOf(1f, 0.85f, 0.72f)) {
            scale(scale, 1f)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            fitted = Fit(widthMeasureSpec, heightMeasureSpec, scale, 1f)
            if (cut().isEmpty()) return
        }
    }

    private fun fillWithLines(widthMeasureSpec: Int, heightMeasureSpec: Int, measure: () -> Unit) {
        scale(1f, 1f)
        measure()
        fitted = Fit(widthMeasureSpec, heightMeasureSpec, 1f, 1f)
        val lines = listOf(date, detail).filter { it.visibility == VISIBLE }
        fun widest() = lines.maxOfOrNull { it.paint.measureText(it.text.toString()) } ?: 0f
        val widest = widest().takeIf { it > 0f } ?: return
        // The pills' words sit their own padding in from the column's; the lines do the same, at the clock's size.
        val room = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - 2 * dp(WidgetStyle.PILL_PADDING)) * sizeFactor
        val before = cut()
        var grow = (room / widest).coerceAtMost(MAX_GROW)
        while (grow > 1.05f) {
            scale(1f, grow)
            measure()
            // Text doesn't widen exactly in step with its size, so it's measured again at this one.
            if (before.containsAll(cut()) && widest() <= room) {
                fitted = Fit(widthMeasureSpec, heightMeasureSpec, 1f, grow)
                return
            }
            grow *= 0.9f
        }
        scale(1f, 1f)
        measure()
    }

    /** The rows left out for want of room. */
    private fun cut() = (0 until childCount).map(::getChildAt).filter { it.getTag(R.id.fit_hidden) == true && it.visibility == GONE }

    private fun scale(time: Float, lines: Float) {
        this.time.setTextSize(TypedValue.COMPLEX_UNIT_PX, timePx * time)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, datePx * lines)
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, detailPx * lines)
        // A view keeps what it measured for each space it was offered, and text doesn't always
        // forget it when its size changes: a line kept the width of a larger size tried before
        // and was cut short.
        listOf(this.time, date, detail).forEach { it.forceLayout() }
        spaceRows()
    }

    /**
     * Each row's gap is from the row above it, so the first row shown has none. The first row
     * also starts at the top of its letters, not at the room its line leaves above them for taller
     * ones: a fifth of its size, which over the time showed as empty space, so the clock's lines
     * sat low and a short clock left out lines it had room for. The clock's own padding is
     * the space above and below.
     */
    private fun spaceRows() {
        var first = true
        for ((row, gap) in listOf(time to 0, date to 4, detail to 5, pills to 12)) {
            val shadow = if (row === time) -dp(SHADOW_ROOM) else 0
            (row.layoutParams as LayoutParams).topMargin = shadow + if (first) -headroom(row) else dp(gap)
            if (row.visibility != GONE || row.getTag(R.id.fit_hidden) == true) first = false
        }
        val headroom = headroom(time)
        if (headroom != timeHeadroom) {
            timeHeadroom = headroom
            // Its highlight hugs the digits, inside the room left for the shadow, so it stays within the clock.
            val below = (time.paint.fontMetrics.descent - digits.bottom).toInt().coerceAtLeast(0)
            time.foreground = android.graphics.drawable.InsetDrawable(ring,
                dp(SHADOW_ROOM), dp(SHADOW_ROOM) + headroom, dp(SHADOW_ROOM), dp(SHADOW_ROOM) + below)
        }
    }

    /** How far [row]'s line reaches above its tallest digits and capitals (they're the same height). */
    private fun headroom(row: android.view.View): Int {
        if (row !is TextView) return 0
        row.paint.getTextBounds(DIGITS, 0, DIGITS.length, digits)
        return (digits.top - row.paint.fontMetrics.ascent).toInt().coerceAtLeast(0)
    }

    /** Shows or hides a row for good; [FitColumn] would otherwise bring back a row it once left out. */
    private fun show(row: android.view.View, shown: Boolean) {
        row.visibility = if (shown) VISIBLE else GONE
        row.setTag(R.id.fit_hidden, null)
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

    /** Works out the date and details again; runs every minute. */
    fun refresh() {
        val lines = glance.lines(System.currentTimeMillis()) { if (isAttachedToWindow) refresh() }
        place = lines.place
        date.text = lines.date
        detail.text = lines.detail
        show(detail, lines.detail != null)
        pills.removeAllViews()
        lines.weather?.let { w ->
            pills.addView(WidgetStyle.pill(context, WeatherArt.icon(w.sky, w.night), "${w.temperature}°  ${w.description}", pillPx).apply {
                setOnClickListener { onWeather?.invoke() }
                // With two things coming up and room for only some, the weather makes way.
                if (lines.coming.size >= 2) FitColumn.dropFirst(this)
            })
        }
        lines.coming.forEach { item ->
            pills.addView(WidgetStyle.pill(context, WidgetStyle.icon(context, item.icon), item.text, pillPx).apply {
                setOnClickListener { onTap?.invoke(item, place) }
            })
        }
        show(pills, pills.childCount > 0)
        spaceRows()
        fitted = null
    }

    fun apply(options: ClockOptions) {
        sizeFactor = when (options.size) {
            ClockOptions.Size.LARGE -> 1f
            ClockOptions.Size.MEDIUM -> 0.85f
            ClockOptions.Size.SMALL -> 0.7f
        }
        sizedFor = 0
        fitted = null
        show(time, options.showTime)
        show(date, options.showDate)
        val horizontal = if (options.alignStart) Gravity.START else Gravity.CENTER_HORIZONTAL
        gravity = horizontal or Gravity.CENTER_VERTICAL
        // Above and below, the pills' own padding: their words and the time's digits sit as far from the edges.
        setPadding(dp(if (options.alignStart) 20 else 12), dp(WidgetStyle.PILL_PADDING), dp(12), dp(WidgetStyle.PILL_PADDING))
        (time.layoutParams as LayoutParams).gravity = horizontal
        (date.layoutParams as LayoutParams).gravity = horizontal
        (detail.layoutParams as LayoutParams).gravity = horizontal
        pills.alignStart = options.alignStart
        refresh()
        requestLayout()
    }

    /**
     * Sizes the text from the widget's [width] in pixels, so it takes the same share of the screen
     * on every phone, whatever its display size setting. The date, the quieter line and the
     * pills step down from the time in fixed proportions.
     */
    private fun sizeFor(width: Int) {
        if (width <= 0 || width == sizedFor) return
        sizedFor = width
        fitted = null
        val unit = width / 100f * sizeFactor
        timePx = 23f * unit
        datePx = 5.6f * unit
        detailPx = 4.6f * unit
        pillPx = 4.2f * unit
        for (i in 0 until pills.childCount) (pills.getChildAt(i) as? TextView)?.let { WidgetStyle.sizePill(it, pillPx) }
    }

    val dateView: TextView get() = date
}

/** Padding around the clock's time so its shadow isn't clipped. */
private const val SHADOW_ROOM = 12
/** What the time can show, for the height of its digits. */
private const val DIGITS = "0123456789"
/** How much larger the date can get with the time hidden, so a short line doesn't loom. */
private const val MAX_GROW = 2f

data class ClockOptions(val size: Size, val alignStart: Boolean, val showDate: Boolean, val showTime: Boolean = true) {
    enum class Size(val label: String) { SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }
}

