package app.featherlauncher

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Outline
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts another app's widget, with Android's rounded widget corners, and adds long-press (to
 * move and resize it) on top of its own touch handling, wherever it's held, buttons and lists too.
 */
class WidgetView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        // The widget's buttons and lists stop where they are: no click when the finger lifts.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0).let {
            super.dispatchTouchEvent(it)
            it.recycle()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    /**
     * Every widget gets rounded corners, square ones too (as on the Pixel launcher), exactly as
     * round as Feather's own ([WidgetStyle.RADIUS]), whatever the phone sets Android's to.
     * A widget that marks its background is rounded around that, the way Android does it.
     */
    private val rounded = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val box = android.graphics.Rect(0, 0, view.width, view.height)
            // [view] is the widget's own layout inside this one.
            (view as? android.view.ViewGroup)?.findViewById<View>(android.R.id.background)?.takeIf { it !== view && it.width > 0 }?.let { bg ->
                box.set(0, 0, bg.width, bg.height)
                view.offsetDescendantRectToMyCoords(bg, box)
            }
            val radius = WidgetStyle.RADIUS * resources.displayMetrics.density
            corner = minOf(radius, minOf(box.width(), box.height()) / 2f)
            cornerBox.set(box)
            cornerBox.offset(view.left, view.top)
            outline.setRoundRect(box, corner)
            // The keypad ring around the widget follows its corner, the gap out from it.
            val ring = ((corner + cornerInset) / resources.displayMetrics.density).roundToInt()
            if (ring != ringDp) {
                ringDp = ring
                post { foreground = Ui.focusRing(context, ring, 0xFFFFFFFF.toInt()) }
            }
        }
    }

    /** The rounded corner (px) the widget is cut to, and the box (in this view) it's cut around. */
    var corner = 0f
        private set
    val cornerBox = android.graphics.Rect()
    val cornerInset get() = cornerBox.left
    private var ringDp = 0

    /**
     * Android pads a widget 8dp on every side; it gets the same room as Feather's own cards
     * ([CardWidget.GAP]) instead, so widgets side by side line up and their outlines match.
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val gap = Ui.dp(context, CardWidget.GAP)
        super.setPadding(gap, gap, gap, gap)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Android sets its own corners while laying a widget out (and none on widgets that don't
        // mark their background); ours go back on after. The widget inside is rounded, not this
        // view, so the keypad highlight around it isn't cut off.
        clipToOutline = false
        getChildAt(0)?.let { inner ->
            inner.outlineProvider = rounded
            inner.clipToOutline = true
            inner.invalidateOutline()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Tell the widget its real size so it can pick a layout that fits.
        if (w > 0 && h > 0) {
            val density = resources.displayMetrics.density
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                updateAppWidgetSize(android.os.Bundle(), listOf(android.util.SizeF(w / density, h / density)))
            } else {
                // Android 11 widgets take a range of sizes; this one size is both ends of it.
                val (wDp, hDp) = (w / density).toInt() to (h / density).toInt()
                @Suppress("DEPRECATION")
                updateAppWidgetSize(android.os.Bundle(), wDp, hDp, wDp, hDp)
            }
        }
    }

    // Every touch is watched here, before the widget's own views: a list or button inside can
    // keep touches from the usual interception, and the hold would go unnoticed.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        // After a long press, the rest of the gesture is ours (the drag), not the widget's.
        if (longPressed && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        return super.dispatchTouchEvent(ev) || true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressed = false
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
    }
}
