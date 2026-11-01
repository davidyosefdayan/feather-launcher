package app.featherlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs

/** Home screen pages side by side; swipe sideways to move between them. */
class PagedView(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {
    var currentPage = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null
    /** How far a slide to a page has come, from 0 to 1 once it's there. */
    var onSlide: ((Float) -> Unit)? = null
    private var slideFrom = 0
    /** Long press on empty space (no app or widget under the finger), in window coordinates. */
    var onEmptyLongPress: ((x: Float, y: Float) -> Unit)? = null

    private var pressX = 0f
    private var pressY = 0f
    private val longPress = Runnable {
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onEmptyLongPress?.invoke(pressX, pressY)
    }

    private val scroller = OverScroller(context, DecelerateInterpolator(2f))
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    val pageCount get() = childCount
    fun page(index: Int) = getChildAt(index) as CellLayout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val spec = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) getChildAt(i).measure(spec(w), spec(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(i * width, 0, (i + 1) * width, height)
        if (!scroller.isFinished) return
        scrollTo(currentPage * width, 0)
    }

    fun snapTo(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != currentPage
        currentPage = target
        if (animate && width > 0) {
            slideFrom = scrollX
            scroller.startScroll(scrollX, 0, target * width - scrollX, 0, 280)
            postInvalidateOnAnimation()
        } else {
            scroller.forceFinished(true)
            scrollTo(target * width, 0)
        }
        updateFocusability()
        if (changed) onPageChanged?.invoke(target)
    }

    /** Only the visible page takes keypad focus. */
    fun updateFocusability() {
        for (i in 0 until childCount) {
            (getChildAt(i) as ViewGroup).descendantFocusability =
                if (i == currentPage) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
            val distance = scroller.finalX - slideFrom
            onSlide?.invoke(if (scroller.isFinished || distance == 0) 1f else ((scrollX - slideFrom).toFloat() / distance).coerceIn(0f, 1f))
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = !scroller.isFinished
                if (dragging) {
                    scroller.forceFinished(true)
                    onSlide?.invoke(1f)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                if (!dragging && childCount > 1 && dx > slop && dx > abs(ev.y - downY)) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only reached when no app or widget took the touch, so this is empty space.
                downX = ev.x; downY = ev.y; lastX = ev.x
                pressX = ev.rawX; pressY = ev.rawY
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
                if (!dragging && abs(ev.x - downX) > slop && childCount > 1) dragging = true
                if (dragging) {
                    val maxScroll = (childCount - 1) * width
                    val dx = lastX - ev.x
                    // Resist past the first and last page.
                    val resist = if (scrollX + dx < 0 || scrollX + dx > maxScroll) 0.35f else 1f
                    scrollBy((dx * resist).toInt(), 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    val v = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    val target = when {
                        v < -minFling -> currentPage + 1
                        v > minFling -> currentPage - 1
                        else -> (scrollX + width / 2) / width
                    }
                    snapTo(target)
                }
                dragging = false
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) snapTo(currentPage)
                dragging = false
                recycleVelocity()
            }
        }
        return true
    }

    private fun track(ev: MotionEvent) {
        if (velocity == null) velocity = VelocityTracker.obtain()
        // Track in screen space; scrolling moves this view's own coordinates.
        val copy = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocity?.addMovement(copy)
        copy.recycle()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) velocity?.clear()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    override fun addView(child: View?, index: Int, params: LayoutParams?) {
        super.addView(child, index, params)
        updateFocusability()
    }
}

/** The small dots under the pages showing which page is shown. */
class PageDots(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var count = 1
        set(value) { field = value; invalidate() }
    var current = 0
        set(value) { field = value; invalidate() }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (count < 2) return
        val r = Ui.dp(context, 3).toFloat()
        val gap = Ui.dp(context, 8).toFloat()
        val active = Ui.dp(context, 16).toFloat()
        val total = (count - 1) * (2 * r + gap) + active
        var x = (width - total) / 2
        // Cards end a gap short of the page, so the dots go up half that to sit midway between
        // the cards and the dock.
        val cy = height / 2f - Ui.dp(context, CardWidget.GAP / 2f)
        for (i in 0 until count) {
            val w = if (i == current) active else 2 * r
            paint.color = if (i == current) 0xFFFFFFFF.toInt() else 0x73FFFFFF
            canvas.drawRoundRect(x, cy - r, x + w, cy + r, r, r, paint)
            x += w + gap
        }
    }
}
