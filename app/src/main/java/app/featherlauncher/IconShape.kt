package app.featherlauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** How icons are drawn: as the app made them, dark (iOS-style), or one color in the accent. */
enum class IconLook { NORMAL, DARK, TINTED }

enum class IconShape(val label: String) {
    SQUIRCLE("Squircle"),
    ROUNDED("Rounded"),
    CIRCLE("Circle"),
    SQUARE("Square");

    fun path(size: Float): Path = Path().apply {
        when (this@IconShape) {
            SQUIRCLE -> {
                // Superellipse: smoother corners than a rounded rectangle.
                val r = size / 2
                val n = 4.0
                for (i in 0..120) {
                    val t = 2 * Math.PI * i / 120
                    val c = cos(t)
                    val s = sin(t)
                    val x = r + r * sign(c) * abs(c).pow(2 / n)
                    val y = r + r * sign(s) * abs(s).pow(2 / n)
                    if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                }
                close()
            }
            ROUNDED -> addRoundRect(0f, 0f, size, size, size * 0.26f, size * 0.26f, Path.Direction.CW)
            CIRCLE -> addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
            SQUARE -> addRoundRect(0f, 0f, size, size, size * 0.12f, size * 0.12f, Path.Direction.CW)
        }
    }

    /**
     * Draws an app icon into this shape, so every icon on the phone has the same outline.
     * [look] DARK puts the icon's symbol on a deep charcoal plate; TINTED draws the symbol in
     * [tint] on that plate.
     */
    fun render(drawable: Drawable, size: Int, look: IconLook = IconLook.NORMAL, tint: Int = 0, monochrome: Drawable? = null): Bitmap {
        // Dark and tinted icons are cut out at twice the size and scaled down, for smooth edges.
        if (look != IconLook.NORMAL) {
            val big = renderAt(drawable, size * 2, look, tint, monochrome)
            return Bitmap.createScaledBitmap(big, size, size, true).also { if (it !== big) big.recycle() }
        }
        return renderAt(drawable, size, look, tint, monochrome)
    }

    private fun renderAt(drawable: Drawable, size: Int, look: IconLook, tint: Int, monochrome: Drawable?): Bitmap {
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layers)
        val legacyWithOwnShape: Boolean
        if (drawable is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with the visible 72dp in the middle.
            val inset = size / 4
            if (look == IconLook.NORMAL) {
                drawable.background?.run { setBounds(-inset, -inset, size + inset, size + inset); draw(canvas) }
                drawable.foreground?.run { setBounds(-inset, -inset, size + inset, size + inset); draw(canvas) }
            } else {
                IconLooks.drawAdaptive(canvas, drawable, size, inset, look, tint, monochrome)
            }
            legacyWithOwnShape = false
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val corners = listOf(layers.getPixel(1, 1), layers.getPixel(size - 2, 1), layers.getPixel(1, size - 2), layers.getPixel(size - 2, size - 2))
            legacyWithOwnShape = corners.any { Color.alpha(it) < 250 }
        }
        // An old-style full picture: its plate is lifted out the same way as for adaptive icons.
        if (look != IconLook.NORMAL && drawable !is AdaptiveIconDrawable && !legacyWithOwnShape) IconLooks.darkenWhole(layers)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)
        val path = path(size.toFloat())
        if (legacyWithOwnShape) {
            // Old-style icon with its own outline: put it on a tile of our shape (white, or charcoal when dark).
            outCanvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (look == IconLook.NORMAL) Color.WHITE else IconLooks.PLATE_TOP
            })
            val pad = (size * 0.16f).toInt()
            drawable.setBounds(pad, pad, size - pad, size - pad)
            drawable.draw(outCanvas)
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            outCanvas.drawPath(path, paint)
        }
        layers.recycle()
        return out
    }
}
