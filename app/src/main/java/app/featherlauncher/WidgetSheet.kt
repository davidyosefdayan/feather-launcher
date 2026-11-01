package app.featherlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Draws each of Feather's widgets at every size it can be made, from its smallest to a whole
 * page, into a picture per widget (and per style, for those with Solid and Transparent),
 * in the app's files folder under widgets/. For checking every size from a computer:
 *
 *     adb shell am broadcast -n app.featherlauncher/.SetupImport --es widgetsheet 1
 *     adb pull /sdcard/Android/data/app.featherlauncher/files/widgets
 */
object WidgetSheet {
    private class Sample(val name: String, val kind: FeatherKind, val solid: Boolean)

    fun draw(context: Context, done: (String) -> Unit) {
        val (cellW, cellH) = CellLayout.pageCell ?: return done("Open the home screen first")
        val samples = FeatherKind.entries.flatMap { kind ->
            if (kind == FeatherKind.CLOCK || kind == FeatherKind.UP_NEXT)
                listOf(Sample("${kind.name.lowercase()}-solid", kind, true), Sample("${kind.name.lowercase()}-transparent", kind, false))
            else listOf(Sample(kind.name.lowercase(), kind, true))
        }
        var id = -90_000L
        val views = samples.associateWith { s ->
            // Only the sizes it can be made.
            (s.kind.minH..Workspace.ROWS).flatMap { h -> (s.kind.minW..Workspace.COLS).map { w -> w to h } }.map { (w, h) ->
                val item = HomeItem(id--, 0, 0, 0, w, h, HomeItem.Type.CLOCK, s.kind.ref)
                val view = CardWidget.create(context, item) ?: ClockWidget(context, item)
                when (view) {
                    is ClockWidget -> view.setSolid(s.solid)
                    is UpNextWidget -> view.solid = s.solid
                }
                lay(view, (w * cellW).toInt(), (h * cellH).toInt())
                (w to h) to view
            }
        }
        // Every piece of work is its own short turn on the main thread (refreshing one widget,
        // drawing one), and saving a picture is done off it, so the home screen keeps answering
        // touches the whole time. Some widgets work out what fits after their first layout, so
        // each is laid out again before it's drawn.
        val main = Handler(Looper.getMainLooper())
        val folder = java.io.File(context.getExternalFilesDir(null), "widgets").apply { mkdirs() }
        val steps = ArrayDeque<((next: () -> Unit) -> Unit)>()
        views.values.flatten().forEach { (_, v) ->
            steps += { next ->
                (v as? CardWidget)?.refresh()
                (v as? ClockWidget)?.refresh()
                lay(v, v.width, v.height)
                next()
            }
        }
        views.forEach { (sample, list) ->
            val sheet = Sheet(list, cellW, cellH)
            list.forEach { (size, v) -> steps += { next -> sheet.draw(size, v); next() } }
            steps += { next ->
                Thread {
                    java.io.File(folder, "${sample.name}.png").outputStream().use { sheet.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    next()
                }.start()
            }
        }
        fun next() {
            val step = steps.removeFirstOrNull()
            main.post { if (step != null) step(::next) else done(folder.path) }
        }
        main.postDelayed({ next() }, 1500)
    }

    private fun lay(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    /** One row per height, the widths side by side, each on a slice of sky like a wallpaper, with its size above it. */
    private class Sheet(list: List<Pair<Pair<Int, Int>, View>>, private val cellW: Float, private val cellH: Float) {
        private val gap = 24
        private val label = 28
        private val heights = list.map { it.first.second }.distinct().sorted()
        private val minW = list.minOf { it.first.first }
        val bitmap: Bitmap
        private val canvas: Canvas
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 20f }
        private val sky: Paint

        init {
            val width = (1..Workspace.COLS).sumOf { (it * cellW).toInt() + gap } + gap
            val height = heights.sumOf { (it * cellH).toInt() + label + gap } + gap
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap)
            canvas.drawColor(0xFF20242C.toInt())
            sky = Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF8FB4E3.toInt(), 0xFF3F6FB5.toInt(), android.graphics.Shader.TileMode.CLAMP)
            }
        }

        fun draw(size: Pair<Int, Int>, v: View) {
            val (w, h) = size
            val x = gap + (minW until w).sumOf { (it * cellW).toInt() + gap }
            val y = gap + heights.takeWhile { it < h }.sumOf { (it * cellH).toInt() + label + gap }
            canvas.drawText("$w × $h", x.toFloat(), (y + 20).toFloat(), text)
            val top = y + label
            canvas.drawRect(x.toFloat(), top.toFloat(), (x + v.width).toFloat(), (top + v.height).toFloat(), sky)
            canvas.save()
            canvas.translate(x.toFloat(), top.toFloat())
            v.draw(canvas)
            canvas.restore()
        }
    }
}
