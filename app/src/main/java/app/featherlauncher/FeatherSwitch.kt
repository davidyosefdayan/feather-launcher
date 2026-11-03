package app.featherlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.PathInterpolator

/**
 * An on/off switch drawn the way Material 3 draws it, so it looks the same on every phone
 * (Android's own takes the phone maker's style). Off: an outlined track with a small thumb.
 * On: a filled track with a larger thumb and a check. The thumb grows while the row is pressed.
 * It doesn't take touches itself; the row it's in flips it.
 */
@SuppressLint("ViewConstructor")
class FeatherSwitch(context: Context, private val p: Palette, initial: Boolean) : View(context) {
    var checked = initial
        set(value) {
            if (value == field) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    /** 0 off, 1 on, in between while it slides. */
    private var progress = if (initial) 1f else 0f
    /** 0 at rest, 1 while pressed. */
    private var press = 0f
    private var slide: ValueAnimator? = null
    private var squeeze: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = ArgbEvaluator()
    private val check = context.getDrawable(R.drawable.ic_check)!!.mutate()
    /**
     * On the app list's wallpaper-colored background instead of a sheet: in its text color, as
     * everything else there is.
     */
    var onWall = false
        set(value) { field = value; invalidate() }

    /** Material's outline: a mid tone that shows on light and dark. */
    private val outline get() = ((if (onWall) p.onWall else p.text) and 0xFFFFFF) or 0x8C000000.toInt()

    init {
        isDuplicateParentStateEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun animateTo(target: Float) {
        slide?.cancel()
        slide = ValueAnimator.ofFloat(progress, target).apply {
            duration = 250
            interpolator = EASE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val target = if (isPressed) 1f else 0f
        if (squeeze == null && press == target) return
        squeeze?.cancel()
        squeeze = ValueAnimator.ofFloat(press, target).apply {
            duration = 150
            interpolator = EASE
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val h = HEIGHT * d
        val w = WIDTH * d
        val top = (height - h) / 2f
        val left = (width - w) / 2f
        val r = h / 2f

        // The track: outlined when off, filled with the accent when on.
        paint.style = Paint.Style.FILL
        val off = if (onWall) (p.onWall and 0xFFFFFF) or 0x1F000000 else p.surfaceHigh
        val on = if (onWall) p.onWall else p.accent
        val thumb = if (onWall) p.drawer or 0xFF000000.toInt() else p.onAccent
        paint.color = colors.evaluate(progress, off, on) as Int
        canvas.drawRoundRect(left, top, left + w, top + h, r, r, paint)
        if (progress < 1f) {
            val stroke = 2 * d
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = outline
            paint.alpha = (android.graphics.Color.alpha(outline) * (1 - progress)).toInt()
            canvas.drawRoundRect(left + stroke / 2, top + stroke / 2, left + w - stroke / 2, top + h - stroke / 2, r - stroke / 2, r - stroke / 2, paint)
        }

        // The thumb: 16dp off, 24dp on, 28dp while pressed, sliding from one end to the other.
        val size = (16 + 8 * progress + (28 - 16 - 8 * progress) * press) * d
        val cx = left + r + (w - 2 * r) * progress
        val cy = top + r
        paint.style = Paint.Style.FILL
        paint.color = colors.evaluate(progress, outline, thumb) as Int
        canvas.drawCircle(cx, cy, size / 2, paint)

        if (progress > 0.5f) {
            val icon = (16 * d).toInt()
            check.setTint(on)
            check.alpha = ((progress - 0.5f) * 2 * 255).toInt()
            check.setBounds((cx - icon / 2).toInt(), (cy - icon / 2).toInt(), (cx + icon / 2).toInt(), (cy + icon / 2).toInt())
            check.draw(canvas)
        }
    }

    companion object {
        const val WIDTH = 52
        const val HEIGHT = 32
        /** Material's standard easing. */
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
