package app.featherlauncher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * An outlined target shown while dragging (go to the home screen, next page, back to the app
 * list). Holding the dragged item over it fills it up; when full, [onTrigger] runs. Moving
 * away before then empties it again, so passing over one by accident does nothing.
 */
@SuppressLint("ViewConstructor")
class DropZone(
    context: Context,
    icon: Int,
    private val color: Int,
    private val radiusDp: Int,
    /** False for targets laid over something already outlined (a peeking page): only the fill shows. */
    private val outlined: Boolean = true,
) : FrameLayout(context) {
    var onTrigger: (() -> Unit)? = null

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var hovering = false
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 2).toFloat()
        this.color = this@DropZone.color and 0x00FFFFFF or 0xB3000000.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@DropZone.color }

    init {
        setWillNotDraw(false)
        if (icon != 0) {
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(color)
            }, LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22), Gravity.CENTER))
        }
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = stroke.strokeWidth / 2
        val r = Ui.dp(context, radiusDp).toFloat()
        box.set(inset, inset, width - inset, height - inset)
        // Faint base, then a fill that grows with the hold.
        // Empty until held over, then fills up.
        fill.alpha = (progress * 0x4D).toInt()
        canvas.drawRoundRect(box, r, r, fill)
        if (outlined) canvas.drawRoundRect(box, r, r, stroke)
    }

    /** Whether the point (window coordinates) is over this target. */
    fun contains(x: Float, y: Float): Boolean {
        if (!isAttachedToWindow) return false
        getLocationInWindow(location)
        return x.toInt() in location[0] until location[0] + width && y.toInt() in location[1] until location[1] + height
    }

    // Made once: drawing happens every frame of a fill, and the drag asks [contains] at every move.
    private val box = RectF()
    private val location = IntArray(2)

    fun hover(over: Boolean) {
        if (over == hovering) return
        hovering = over
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, if (over) 1f else 0f).apply {
            duration = if (over) (HOLD * (1 - progress)).toLong() else 150
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation && hovering && progress >= 1f) onTrigger?.invoke()
                }
            })
            start()
        }
    }

    /** Empties it after it acted, so it can act again on the next hold (flipping pages one by one). */
    fun restart() {
        animator?.cancel()
        hovering = false
        progress = 0f
        invalidate()
    }

    fun dismiss() {
        animator?.cancel()
        onTrigger = null
        animate().alpha(0f).setDuration(120).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }

    companion object {
        /** How long to hold over a target (ms) before it acts. */
        const val HOLD = 500L
    }
}
