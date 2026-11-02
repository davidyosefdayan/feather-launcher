package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar

/**
 * The time on a clock face: a soft round face with twelve marks and no numbers, slim hands and a
 * fine second hand, with the same line about the day as the digital clock under it. Solid, on a
 * card with the face in the accent's soft tone; Transparent, a glass face on the wallpaper.
 */
@SuppressLint("ViewConstructor")
class AnalogClockWidget(context: Context, val item: HomeItem) : FrameLayout(context) {
    private val face = Face(context)
    /** "Sun, Nov 1 · 10 Cheshvan · Lech Lecha", over two lines if it must. */
    private val day = WidgetStyle.text(context, 15f, WidgetStyle.body).apply {
        maxLines = 2
        gravity = Gravity.CENTER
    }
    private val glance = Glance(context)
    var solid = false
        private set

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    // Holding anywhere picks it up, as on the other widgets ([WholeHold]).
    private val hold = WholeHold(this)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent) = hold.touch(ev) { super.dispatchTouchEvent(it) }

    override fun performLongClick() = hold.longClick { super.performLongClick() }

    init {
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        addView(face)
        addView(day)
        setSolid(false)
    }

    /** Solid: on a card with the cards' corners; transparent: straight on the wallpaper. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        background = if (solid) android.graphics.drawable.InsetDrawable(WidgetStyle.cardBackground(context), dp(CardWidget.GAP)) else null
        val edge = dp(CardWidget.GAP + EDGE)
        setPadding(edge, edge, edge, edge)
        day.setTextColor(if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE)
        if (solid) day.setShadowLayer(0f, 0f, 0f, 0) else day.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x59000000)
        face.invalidate()
        requestLayout()
    }

    /** Works out the line about the day again; runs every minute. */
    fun refresh() {
        val lines = glance.lines(System.currentTimeMillis()) { if (isAttachedToWindow) refresh() }
        // Each part stays whole: a long line breaks only between them.
        day.text = (listOf(lines.date) + lines.detail?.split(" · ").orEmpty()).joinToString(" · ") { it.replace(' ', ' ') }
        face.invalidate()
        requestLayout()
    }

    /**
     * The face as large as the room allows, with the line about the day under it; in a space too
     * short for both, the line goes and the face takes its room.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) width else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val inner = width - paddingLeft - paddingRight
        val tall = height - paddingTop - paddingBottom
        // The line about the day takes the same share of the screen on every phone, and stays easy to read.
        val least = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val most = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        day.setTextSize(TypedValue.COMPLEX_UNIT_PX, (inner * 0.075f).coerceIn(least, most))
        day.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val withDay = minOf(inner, tall - day.measuredHeight - dp(GAP))
        val alone = minOf(inner, tall)
        // The line stays unless keeping it would make the face much smaller than without it.
        day.visibility = if (day.text.isNotEmpty() && withDay >= alone * KEEP) VISIBLE else GONE
        val size = (if (day.visibility == VISIBLE) withDay else alone).coerceAtLeast(0)
        face.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }

    /** The face and the line under it, together in the middle. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = day.visibility == VISIBLE
        val used = face.measuredHeight + if (shown) dp(GAP) + day.measuredHeight else 0
        var top = paddingTop + (b - t - paddingTop - paddingBottom - used) / 2
        val middle = (r - l) / 2
        face.layout(middle - face.measuredWidth / 2, top, middle + face.measuredWidth / 2, top + face.measuredHeight)
        top += face.measuredHeight + dp(GAP)
        if (shown) day.layout(middle - day.measuredWidth / 2, top, middle + day.measuredWidth / 2, top + day.measuredHeight)
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

    private fun dp(value: Int) = Ui.dp(context, value)

    /** The face itself, drawn to its size; the second hand moves only while it's on screen. */
    private inner class Face(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val calendar = Calendar.getInstance()
        private var showing = false
        private val second = Runnable { invalidate() }
        /** The face's shading, made again only when its size or color changes, not every second. */
        private var shading: RadialGradient? = null
        private var shadingFor = 0L

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            showing = isVisible
            if (isVisible) invalidate() else removeCallbacks(second)
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            if (r <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            val hands = if (solid) WidgetStyle.onAccentFill() else WidgetStyle.WHITE
            val sweep = WidgetStyle.tertiary()

            // The face: the accent's soft tone, a touch lighter at the middle; on the wallpaper, glass.
            paint.style = Paint.Style.FILL
            paint.clearShadowLayer()
            if (solid) {
                val tone = WidgetStyle.accentFill()
                val key = (width.toLong() shl 48) or (height.toLong() shl 32) or (tone.toLong() and 0xFFFFFFFFL)
                if (key != shadingFor || shading == null) {
                    shading = RadialGradient(cx, cy * 0.85f, r * 1.1f, blend(tone, Color.WHITE, 0.06f), tone, Shader.TileMode.CLAMP)
                    shadingFor = key
                }
                paint.shader = shading
            } else {
                paint.shader = null
                paint.color = 0x24FFFFFF
            }
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            if (!solid) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.012f
                paint.color = 0x40FFFFFF
                canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
            }

            // Twelve marks, the quarters a little longer and bolder; no numbers.
            paint.style = Paint.Style.STROKE
            paint.color = withAlpha(hands, if (solid) 0.55f else 0.75f)
            for (i in 0 until 12) {
                val quarter = i % 3 == 0
                paint.strokeWidth = r * (if (quarter) 0.045f else 0.028f)
                canvas.save()
                canvas.rotate(i * 30f, cx, cy)
                canvas.drawLine(cx, cy - r * 0.86f, cx, cy - r * (if (quarter) 0.70f else 0.76f), paint)
                canvas.restore()
            }

            calendar.timeInMillis = System.currentTimeMillis()
            val h = calendar.get(Calendar.HOUR)
            val m = calendar.get(Calendar.MINUTE)
            val s = calendar.get(Calendar.SECOND)
            // On the wallpaper, a soft shadow keeps the hands clear over any picture.
            if (!solid) paint.setShadowLayer(r * 0.04f, 0f, r * 0.015f, 0x40000000)
            paint.color = hands
            hand(canvas, cx, cy, (h + m / 60f) * 30f, r * 0.50f, r * 0.10f, r * 0.075f)
            hand(canvas, cx, cy, (m + s / 60f) * 6f, r * 0.78f, r * 0.10f, r * 0.05f)
            paint.color = sweep
            hand(canvas, cx, cy, s * 6f, r * 0.84f, r * 0.18f, r * 0.018f)
            paint.clearShadowLayer()

            // The middle: a dot in the second hand's color, with the face showing through.
            paint.style = Paint.Style.FILL
            paint.color = sweep
            canvas.drawCircle(cx, cy, r * 0.05f, paint)
            paint.color = if (solid) WidgetStyle.accentFill() else hands
            canvas.drawCircle(cx, cy, r * 0.018f, paint)

            contentDescription = android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
            // Ticks on the second while it's on screen.
            removeCallbacks(second)
            if (showing) postDelayed(second, 1000L - System.currentTimeMillis() % 1000L)
        }

        /** A hand at [degrees] from twelve, [length] out from the middle and [tail] behind it. */
        private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, tail: Float, width: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            canvas.save()
            canvas.rotate(degrees, cx, cy)
            canvas.drawLine(cx, cy + tail, cx, cy - length, paint)
            canvas.restore()
        }

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        private fun withAlpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
    }

    private companion object {
        /** The room (dp) between the clock's card and what's on it, as on the digital clock. */
        const val EDGE = 16
        /** Between the face and the line under it. */
        const val GAP = 10
        /** The line stays unless the face would then be less than this share of its size without it. */
        const val KEEP = 0.72f
    }
}
