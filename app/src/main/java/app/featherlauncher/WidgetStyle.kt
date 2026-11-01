package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * How Feather's own widgets look, in the style of Android's own: cards in the Material You
 * colors Android makes from the wallpaper (the ones Google's widgets use), light or dark with
 * the app's theme, with large rounded corners; and on the wallpaper itself, white type with a
 * soft shadow and nothing behind it, like the Pixel's At a glance.
 */
// Android 12's color ids are also how Android 11 asks for its own version of them (see system).
@SuppressLint("InlinedApi")
object WidgetStyle {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SOFT_WHITE = 0xD9FFFFFF.toInt()
    /** A card's corners, as Android's own widgets have them. */
    const val RADIUS = 28

    /** The home screen's colors, from the wallpaper; set by [HomeScreen]. */
    var palette: () -> Palette? = { null }
    /** For reading Android's colors; set by [HomeScreen]. */
    var context: Context? = null

    private fun dark() = palette()?.dark ?: true

    private fun system(dark: Int, light: Int, fallback: Int): Int {
        val ctx = context ?: return fallback
        val id = if (dark()) dark else light
        // Android 11 doesn't have these colors, so they're made the same way from the wallpaper.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return MaterialYou.color(ctx, id, palette()) ?: fallback
        return ctx.getColor(id)
    }

    /** A card's background, its text, and its quieter text. */
    fun card() = system(android.R.color.system_accent2_800, android.R.color.system_accent2_50, 0xFF2B2D31.toInt())
    fun onCard() = system(android.R.color.system_neutral1_50, android.R.color.system_neutral1_900, WHITE)
    fun onCardQuiet() = system(android.R.color.system_neutral2_200, android.R.color.system_neutral2_700, SOFT_WHITE)
    /** The accent, for what matters most on a card. */
    fun accent() = system(android.R.color.system_accent1_200, android.R.color.system_accent1_600, 0xFFA8C7FA.toInt())
    /** A softer accent fill, and text on it. */
    fun accentFill() = system(android.R.color.system_accent1_700, android.R.color.system_accent1_100, 0xFF0B57D0.toInt())
    fun onAccentFill() = system(android.R.color.system_accent1_100, android.R.color.system_accent1_900, WHITE)
    /** Text and icons on a solid [accent] fill. */
    fun onAccent() = system(android.R.color.system_accent1_800, android.R.color.system_accent1_0, 0xFF062E6F.toInt())

    fun cardBackground(ctx: Context) = Ui.rounded(ctx, card(), RADIUS)

    val display: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val title: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val body: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val label: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)

    /** White text with the soft shadow that keeps it readable on any wallpaper. */
    fun text(ctx: Context, sp: Float, face: Typeface, color: Int = WHITE) = TextView(ctx).apply {
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(Ui.dp(ctx, 6).toFloat(), 0f, Ui.dp(ctx, 1).toFloat(), 0x59000000)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Text on a card: [color] is one of the card colors above. */
    fun cardText(ctx: Context, sp: Float, face: Typeface, color: Int = onCard()) = TextView(ctx).apply {
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** An icon and a few words straight on the wallpaper, for something to tap; [px] is the text size in pixels. */
    fun pill(ctx: Context, icon: Drawable?, words: String, px: Float) = text(ctx, 14f, label).apply {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        text = words
        gravity = Gravity.CENTER_VERTICAL
        foreground = Ui.pressable(ctx, Ui.RADIUS_PILL, focusColor = WHITE)
        setCompoundDrawablesRelative(icon?.mutate(), null, null, null)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        sizePill(this, px)
    }

    /** Sets a pill's text to [px] pixels, its icon a little larger. */
    fun sizePill(pill: TextView, px: Float) {
        pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        val size = (px * 1.25f).toInt()
        // Set again after resizing: the text measures its icon when it's given one.
        pill.compoundDrawablesRelative[0]?.let { icon ->
            icon.setBounds(0, 0, size, size)
            pill.setCompoundDrawablesRelative(icon, null, null, null)
        }
        pill.compoundDrawablePadding = (px * 0.4f).toInt()
    }

    /** One of the app's line icons, white for the wallpaper. */
    fun icon(ctx: Context, res: Int): Drawable? = ctx.getDrawable(res)?.mutate()?.apply { setTint(WHITE) }
}

/**
 * A row of pills that only shows the ones that fit, in order: on a small screen the last ones
 * are left out rather than squeezed, or first one marked with [FitColumn.dropFirst]. Centred,
 * or from the start with [alignStart].
 */
class FitRow(context: Context) : ViewGroup(context) {
    var gap = Ui.dp(context, 8)
    var alignStart = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).let { if (it == 0) Int.MAX_VALUE else it }
        // A pill marked to go first is left out when everything doesn't fit.
        val all = (0 until childCount).map(::getChildAt)
        all.forEach { it.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED) }
        val total = all.sumOf { it.measuredWidth } + gap * (all.size - 1).coerceAtLeast(0)
        val skip = all.filter { (it.getTag(R.id.fit_priority) as? Int ?: 0) > 0 }.takeIf { total > available && all.size > 1 }.orEmpty()
        var used = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child in skip) {
                child.visibility = View.GONE
                continue
            }
            val next = used + (if (used > 0) gap else 0) + child.measuredWidth
            // The first pill always shows, shortened if it must be; later ones only if they fit whole.
            if (used > 0 && next > available) {
                child.visibility = View.GONE
                continue
            }
            child.visibility = View.VISIBLE
            used = minOf(next, available)
            height = maxOf(height, child.measuredHeight)
        }
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) available else used, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = (0 until childCount).map(::getChildAt).filter { it.visibility == View.VISIBLE }
        val total = shown.sumOf { it.measuredWidth } + gap * (shown.size - 1).coerceAtLeast(0)
        var x = if (alignStart) 0 else ((r - l) - total).coerceAtLeast(0) / 2
        for (child in shown) {
            val w = minOf(child.measuredWidth, r - l - x)
            child.layout(x, 0, x + w, child.measuredHeight)
            x += w + gap
        }
    }
}

/**
 * A column that leaves out its last rows when they don't fit, instead of letting them spill
 * over or get cut in half: on a small screen a card shows fewer rows, not broken ones. A row
 * marked with [dropFirst] goes before the others; the first row only goes if it's marked so.
 */
open class FitColumn(context: Context) : android.widget.LinearLayout(context) {
    companion object {
        /** Marks [view] to be left out before rows with a lower [priority] (0 by default); below 0, it's never left out. */
        fun dropFirst(view: View, priority: Int = 1) = view.apply { setTag(R.id.fit_priority, priority) }
    }

    init {
        orientation = VERTICAL
    }

    /** Shares the room left over evenly between the rows, so the top and bottom keep just the padding. A single row is centered. */
    var spread = false
        set(value) {
            field = value
            requestLayout()
        }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!spread) return super.onLayout(changed, l, t, r, b)
        val shown = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        // Margins between rows give way to the even spacing.
        val used = shown.sumOf { it.measuredHeight }
        val spare = (b - t - paddingTop - paddingBottom - used).coerceAtLeast(0)
        val gap = if (shown.size > 1) spare / (shown.size - 1) else 0
        var y = paddingTop + if (shown.size == 1) spare / 2 else 0
        for (child in shown) {
            val width = r - l - paddingLeft - paddingRight
            child.layout(paddingLeft, y, paddingLeft + minOf(child.measuredWidth, width).let { if (child.layoutParams.width == LayoutParams.MATCH_PARENT) width else it }, y + child.measuredHeight)
            y += child.measuredHeight + gap
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) getChildAt(i).let { if (it.getTag(R.id.fit_hidden) == true) it.visibility = View.VISIBLE }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val room = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val width = MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.AT_MOST)
        while (used(width) > room) {
            // The row with the highest [dropFirst] priority goes; among equals, the last.
            val last = (childCount - 1 downTo 0).map(::getChildAt)
                .filter { it.visibility == View.VISIBLE && (it.getTag(R.id.fit_priority) as? Int ?: if (it === getChildAt(0)) -1 else 0) >= 0 }
                .maxByOrNull { it.getTag(R.id.fit_priority) as? Int ?: 0 } ?: break
            last.visibility = View.GONE
            last.setTag(R.id.fit_hidden, true)
        }
        // Checking the rows' own heights measured them loosely; measure them properly to lay out.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The rows' own heights, as they'd be with room to spare (the column would squeeze the last one to fit). */
    private fun used(width: Int) = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.sumOf {
        val lp = it.layoutParams as MarginLayoutParams
        it.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        it.measuredHeight + lp.topMargin + lp.bottomMargin
    }
}

/**
 * Text that says as much as fits on one line: the first of [options] that fits its width
 * ("in 25 min · 1:00 – 2:00 PM", then "in 25 min · 1:00 PM", then "1:00 PM"), the last cut short
 * if even that doesn't.
 */
class FitText(context: Context) : TextView(context) {
    var options: List<String> = emptyList()
        set(value) {
            field = value
            text = value.firstOrNull()
            requestLayout()
        }

    init {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (options.size > 1 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val pick = options.firstOrNull { paint.measureText(it) <= room } ?: options.last()
            if (text.toString() != pick) text = pick
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
