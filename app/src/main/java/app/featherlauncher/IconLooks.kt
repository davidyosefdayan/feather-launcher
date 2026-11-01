package app.featherlauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import org.xmlpull.v1.XmlPullParser

/**
 * Dark and tinted icons, like iOS: the app's symbol on a deep charcoal plate instead of its own.
 *
 * Apps build icons in different ways (a symbol over a plain background, everything in one
 * picture, a plate inside the symbol layer), so the symbol is found step by step:
 * 1. the symbol layer itself, when it's a symbol over a plain background;
 * 2. otherwise the whole icon with its plate lifted out: the plain color around the edge,
 *    removed from the outside in, with soft edges;
 * 3. if neither leaves a clear symbol, the icon as the app made it (never a blank one).
 * Then the symbol is made readable on charcoal: a dark one turns light (keeping its hue), and a
 * white one on a colored plate takes the plate's color.
 */
object IconLooks {
    const val PLATE_TOP = 0xFF2C2D31.toInt()
    private const val PLATE_BOTTOM = 0xFF17181A.toInt()
    private const val LIGHT_GLYPH = 0xFFE8E9EC.toInt()

    /** Draws [icon] in [look] into [canvas] ([size] square; layers are drawn [inset] past the edges). */
    fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable, size: Int, inset: Int, look: IconLook, tint: Int, monochrome: Drawable? = null) {
        val bounds = Rect(-inset, -inset, size + inset, size + inset)
        val fg = icon.foreground?.let { layer(it, size, bounds) }
        val bg = icon.background?.let { layer(it, size, bounds) }
        val whole = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { c ->
            Canvas(c).apply { bg?.let { drawBitmap(it, 0f, 0f, null) }; fg?.let { drawBitmap(it, 0f, 0f, null) } }
        }
        if (look == IconLook.TINTED) {
            drawPlate(canvas, size)
            val mono = monochrome ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) icon.monochrome else null
            if (mono != null) {
                mono.mutate().apply {
                    this.bounds = bounds
                    colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    draw(canvas)
                }
            } else {
                val symbol = symbolOf(fg, bg, whole)?.first ?: whole
                canvas.drawBitmap(toTint(symbol, tint), 0f, 0f, null)
            }
            return
        }
        drawDark(canvas, size, symbolOf(fg, bg, whole), whole)
    }

    /**
     * The one-color layer an app gives its icon for themed icons. Android 11 and 12 skip that
     * layer when they load the icon (13 added it), so there it's read from the icon's file.
     */
    fun monochromeOf(context: Context, component: ComponentName): Drawable? = runCatching {
        val pm = context.packageManager
        val id = pm.getActivityInfo(component, 0).iconResource.takeIf { it != 0 } ?: return null
        val res = pm.getResourcesForApplication(component.packageName)
        val xml = res.getXml(id)
        try {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG || xml.name != "monochrome") continue
                val layer = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                return if (layer != 0) res.getDrawableForDensity(layer, DisplayMetrics.DENSITY_XHIGH, null) else null
            }
            null
        } finally {
            xml.close()
        }
    }.getOrNull()

    /** For old-style single-picture icons: [whole] is the icon as drawn. Changes it in place. */
    fun darkenWhole(whole: Bitmap) {
        val copy = whole.copy(Bitmap.Config.ARGB_8888, false)
        whole.eraseColor(0)
        drawDark(Canvas(whole), whole.width, liftPlate(copy)?.let { it.first to it.second }, copy)
    }

    /** The symbol on charcoal, made readable; or the icon unchanged if no clear symbol was found. */
    private fun drawDark(canvas: Canvas, size: Int, found: Pair<Bitmap, Int?>?, whole: Bitmap) {
        if (found == null) {
            canvas.drawBitmap(whole, 0f, 0f, null)
            return
        }
        val (symbol, plateColor) = found
        drawPlate(canvas, size)
        canvas.drawBitmap(readable(symbol, plateColor), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * The icon's symbol and the color of the plate it sat on, or null if there's no clear symbol.
     * Uses the symbol layer when it's a real symbol over a plain background, else lifts the plate out.
     */
    private fun symbolOf(fg: Bitmap?, bg: Bitmap?, whole: Bitmap): Pair<Bitmap, Int?>? {
        if (fg != null) {
            val coverage = coverage(fg)
            val plainBackground = bg == null || isPlain(bg)
            when {
                // A symbol over a plain background: the classic adaptive icon.
                coverage in 0.03f..0.55f && plainBackground -> {
                    val plate = bg?.let(::average)
                    // A symbol layer carrying its own copy of the background as a plate (Maps at some
                    // sizes): lift that out. Only when it matches the background; otherwise it's part
                    // of the symbol (Camera's white body, YouTube Music's red disc).
                    liftPlate(fg)?.let { (symbol, found) ->
                        if (plate != null && found != null && distance(found, plate) <= TOLERANCE) return symbol to plate
                    }
                    // The symbol layer as the app drew it: clean edges, and holes (Calculator's signs,
                    // the key's ring) show the charcoal through them.
                    return fg to plate
                }
                // The symbol layer is itself a plate with the symbol cut into it (Saleshandy).
                coverage > 0.55f -> liftPlate(fg)?.let { return it }
            }
        }
        // Everything in one picture (or a picture background): lift the plate out of the whole icon.
        return liftPlate(whole)
    }

    /**
     * Removes the plain color around the edge of [b], from the outside in, softening the edge
     * where the symbol begins. Returns the symbol and the removed color, or null if the edge isn't
     * one plain color or nearly nothing would be left.
     */
    private fun liftPlate(b: Bitmap): Pair<Bitmap, Int?>? {
        val w = b.width
        val h = b.height
        val pixels = IntArray(w * h)
        b.getPixels(pixels, 0, w, 0, 0, w, h)
        // The plate's color: what most of a ring inside the edge is (corners may be cut off). The
        // plate can start a little way in (Maps), so rings further in are tried too.
        var plate = 0
        var found = false
        for (f in floatArrayOf(0.1f, 0.17f, 0.24f)) {
            val ring = (w * f).toInt()
            val samples = ArrayList<Int>()
            for (i in 0 until 24) {
                val t = i / 24f
                val (x, y) = when (i % 4) {
                    0 -> (ring + t * (w - 2 * ring)).toInt() to ring
                    1 -> (w - 1 - ring) to (ring + t * (h - 2 * ring)).toInt()
                    2 -> (ring + t * (w - 2 * ring)).toInt() to (h - 1 - ring)
                    else -> ring to (ring + t * (h - 2 * ring)).toInt()
                }
                samples += pixels[y * w + x]
            }
            val opaque = samples.filter { Color.alpha(it) > 200 }
            if (opaque.size < 12) continue
            val candidate = opaque.groupBy { quantize(it) }.maxByOrNull { it.value.size }!!.value.let(::averageOf)
            val onPlate = opaque.count { distance(it, candidate) <= TOLERANCE }
            if (onPlate < opaque.size * 0.6f) continue
            // A deliberate two-color design (Translate's blue and white halves) has no single plate.
            val rest = opaque.filter { distance(it, candidate) > TOLERANCE }
            if (rest.size >= opaque.size * 0.4f && rest.count { distance(it, averageOf(rest)) <= TOLERANCE } >= rest.size * 0.8f) return null
            plate = candidate
            found = true
            break
        }
        if (!found) return null

        val out = pixels.copyOf()
        val seen = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        for (x in 0 until w) { queue.add(x); queue.add((h - 1) * w + x) }
        for (y in 0 until h) { queue.add(y * w); queue.add(y * w + w - 1) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (seen[i]) continue
            seen[i] = true
            val c = pixels[i]
            val d = distance(c, plate)
            when {
                Color.alpha(c) <= 24 || d <= TOLERANCE -> out[i] = 0
                // The symbol's soft edge: partly plate, so partly see-through, and no further.
                d <= TOLERANCE * 2.2f -> {
                    val keep = (d - TOLERANCE) / (TOLERANCE * 1.2f)
                    out[i] = (c and 0xFFFFFF) or ((Color.alpha(c) * keep).toInt().coerceIn(0, 255) shl 24)
                    continue
                }
                else -> continue
            }
            val x = i % w
            val y = i / w
            if (x > 0) queue.add(i - 1)
            if (x < w - 1) queue.add(i + 1)
            if (y > 0) queue.add(i - w)
            if (y < h - 1) queue.add(i + w)
        }
        // Plate color showing through inside the symbol (All Daf's frame) goes too, if it's a close match.
        // Plate color showing through inside the symbol (All Daf's frame, MyZmanim's globe) goes
        // too, except one large single area of it, which is part of the design (Maps' pin center).
        val inside = BooleanArray(w * h) { i -> !seen[i] && Color.alpha(out[i]) > 0 && distance(out[i], plate) <= TOLERANCE * 0.6f }
        val regions = ArrayList<List<Int>>()
        val visited = BooleanArray(w * h)
        for (start in inside.indices) {
            if (!inside[start] || visited[start]) continue
            val region = ArrayList<Int>()
            val q = ArrayDeque<Int>().apply { add(start) }
            visited[start] = true
            while (q.isNotEmpty()) {
                val i = q.removeFirst()
                region += i
                val x = i % w
                val y = i / w
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j >= 0 && inside[j] && !visited[j]) { visited[j] = true; q.add(j) }
                }
            }
            if (region.size > w * h / 400) regions += region
        }
        // On a light plate a few such areas are part of the design (Maps' pin center, a clock's
        // hands) and stay; many of them (MyZmanim's globe cells) are background showing through.
        val keepAll = luminance(plate) >= 0.5f && regions.size < 6
        if (!keepAll) for (i in inside.indices) if (inside[i]) out[i] = 0
        val keep: Set<Int>? = if (keepAll) inside.indices.filter { inside[it] }.toHashSet() else null
        // On a light plate, what's left still has the plate mixed into its soft edges (a pale
        // fringe). Each pixel gives up exactly the plate color mixed into it, as photo editors'
        // "color to alpha" does: edges come out smooth and in their true color.
        // Only along the edges, so the symbol's own colors inside stay exactly as they are.
        if (luminance(plate) > 0.75f) {
            val keepSet = keep ?: emptySet<Int>()
            val band = maxOf(2, w / 48)
            val edge = BooleanArray(w * h)
            for (i in out.indices) {
                if (Color.alpha(out[i]) != 0) continue
                val x0 = i % w
                val y0 = i / w
                for (dy in -band..band) for (dx in -band..band) {
                    val x = x0 + dx
                    val y = y0 + dy
                    if (x in 0 until w && y in 0 until h) edge[y * w + x] = true
                }
            }
            for (i in out.indices) {
                if (!edge[i] || Color.alpha(out[i]) == 0 || i in keepSet) continue
                out[i] = colorToAlpha(out[i], plate)
            }
        }
        val symbol = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
        val left = coverage(symbol)
        // Nothing left (the plate was the picture) or almost nothing removed: no clear symbol.
        if (left < 0.02f || left > coverage(b) * 0.92f) return null
        // A pattern running out to every edge (a grid) is the background's texture, not a symbol.
        if (borderCoverage(symbol) > 0.75f) return null
        return symbol to plate
    }

    /**
     * The symbol made to read on charcoal:
     * - mostly white on a colored plate: in the plate's color, brightened (WhatsApp's phone turns green);
     * - one dark color (ChatGPT, T3 Code): light gray, or its own hue made bright;
     * - mostly dark with a few light bits (CreditWise's text): only the dark parts are brightened;
     * - otherwise it already reads (Waze's white face keeps its dark outline).
     */
    private fun readable(symbol: Bitmap, plate: Int?): Bitmap {
        var n = 0; var dim = 0; var dark = 0; var darkNeutral = 0; var light = 0; var lightNeutral = 0
        // Colored parts in a different color from the plate (Gboard's G): the symbol has its own colors.
        var ownColors = 0
        var lightPlateHue = 0
        // Solid parts in the plate's own color (Camera's lens): recoloring the white would swallow them.
        var plateHueParts = 0
        val plateHue = plate?.let { FloatArray(3).also { h -> Color.colorToHSV(it, h) }[0] }
        val darkColors = ArrayList<Int>()
        val step = maxOf(1, symbol.width / 40)
        for (y in 0 until symbol.height step step) for (x in 0 until symbol.width step step) {
            val c = symbol.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            n++
            val l = luminance(c)
            if (l < 0.3f) dim++
            if (l < 0.25f) { dark++; darkColors += c }
            if (l < 0.25f && saturation(c) < 0.25f) darkNeutral++
            if (l > 0.7f && saturation(c) >= 0.18f && plateHue != null &&
                FloatArray(3).also { Color.colorToHSV(c, it) }[0].let { kotlin.math.abs(it - plateHue).let { d -> minOf(d, 360 - d) } } <= 30) {
                // A light tint of the plate's own color (AntennaPod's pale blue rings) counts as white.
                lightPlateHue++
            }
            if (plateHue != null && saturation(c) >= 0.3f && l in 0.15f..0.7f &&
                FloatArray(3).also { Color.colorToHSV(c, it) }[0].let { kotlin.math.abs(it - plateHue).let { d -> minOf(d, 360 - d) } } <= 30) plateHueParts++
            if (plateHue != null && saturation(c) > 0.3f && l > 0.15f) {
                val hue = FloatArray(3).also { Color.colorToHSV(c, it) }[0]
                val apart = kotlin.math.abs(hue - plateHue).let { minOf(it, 360 - it) }
                if (apart > 30) ownColors++
            }
            if (l > 0.6f) light++
            if (l > 0.7f && saturation(c) < 0.18f) lightNeutral++
        }
        if (n == 0) return symbol
        fun tinted(color: Int) = Bitmap.createBitmap(symbol.width, symbol.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(symbol, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) })
        }
        // A black-outlined white drawing on a colored plate (Waze): the white fill goes and the
        // outline turns white, so it reads as a line drawing on charcoal.
        // (Black outlines only: Fitbod's dark red shape inside its white hexagon is part of the design.)
        if (plate != null && saturation(plate) > 0.3f && darkNeutral > n * 0.2f && lightNeutral > n * 0.3f &&
            darkNeutral + lightNeutral > n * 0.85f) {
            val pixels = IntArray(symbol.width * symbol.height)
            symbol.getPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val a = Color.alpha(c)
                if (a == 0) continue
                // Darker means more outline: kept in white, with the in-between edge softened.
                val ink = ((0.75f - luminance(c)) / 0.5f).coerceIn(0f, 1f)
                pixels[i] = (LIGHT_GLYPH and 0xFFFFFF) or ((a * ink).toInt() shl 24)
            }
            return Bitmap.createBitmap(symbol.width, symbol.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            }
        }
        // Mostly white (allowing soft gray shading, as on Fitbod's hexagon) with no dark outline.
        // The white parts take the plate's color; any colored parts stay as they are.
        if (lightNeutral + lightPlateHue > n * 0.5f && darkNeutral < n * 0.2f && ownColors < n * 0.04f && plateHueParts < n * 0.03f && plate != null && saturation(plate) > 0.3f) {
            val color = brighten(plate)
            val pixels = IntArray(symbol.width * symbol.height)
            symbol.getPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                if (Color.alpha(c) == 0) continue
                if (luminance(c) < 0.5f) continue
                // Only white and the plate's own hue change; other colors (Wallpapers' dots) stay.
                if (saturation(c) >= 0.18f && plateHue != null &&
                    FloatArray(3).also { Color.colorToHSV(c, it) }[0].let { kotlin.math.abs(it - plateHue).let { d -> minOf(d, 360 - d) } } > 30) continue
                pixels[i] = (color and 0xFFFFFF) or (c and 0xFF000000.toInt())
            }
            return Bitmap.createBitmap(symbol.width, symbol.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            }
        }
        if (dark > n * 0.5f) {
            val darkAvg = averageOf(darkColors)
            val oneColor = darkColors.count { distance(it, darkAvg) <= TOLERANCE } >= darkColors.size * 0.8f
            // Only when (nearly) the whole symbol is that one dark color: CreditWise's red arc is not.
            if (oneColor && light < n * 0.1f && dark > n * 0.85f) return tinted(if (saturation(darkAvg) < 0.25f) LIGHT_GLYPH else brighten(darkAvg))
        }
        if (dim > n * 0.35f && light < n * 0.35f) {
            // Brighten just the dark pixels, keeping each one's hue.
            val pixels = IntArray(symbol.width * symbol.height)
            symbol.getPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            for (i in pixels.indices) {
                val c = pixels[i]
                if (Color.alpha(c) == 0 || luminance(c) >= 0.3f) continue
                val lifted = if (saturation(c) < 0.25f) LIGHT_GLYPH else brighten(c)
                pixels[i] = (lifted and 0xFFFFFF) or (c and 0xFF000000.toInt())
            }
            return Bitmap.createBitmap(symbol.width, symbol.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, symbol.width, 0, 0, symbol.width, symbol.height)
            }
        }
        return symbol
    }

    private fun drawPlate(canvas: Canvas, size: Int) {
        // A gentle top-to-bottom shade, as on iOS.
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), PLATE_TOP, PLATE_BOTTOM, Shader.TileMode.CLAMP)
        })
    }

    private fun layer(d: Drawable, size: Int, bounds: Rect): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.bounds = bounds
            d.draw(Canvas(it))
        }

    /** [c] with as much of [plate] taken out as possible, the rest made see-through to match. */
    private fun colorToAlpha(c: Int, plate: Int): Int {
        val ch = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        val pl = intArrayOf(Color.red(plate), Color.green(plate), Color.blue(plate))
        var a = 0f
        for (k in 0..2) {
            val share = when {
                ch[k] > pl[k] -> (ch[k] - pl[k]) / (255f - pl[k]).coerceAtLeast(1f)
                ch[k] < pl[k] -> (pl[k] - ch[k]) / pl[k].toFloat().coerceAtLeast(1f)
                else -> 0f
            }
            a = maxOf(a, share)
        }
        if (a <= 0.01f) return 0
        val rgb = IntArray(3) { k -> ((ch[k] - pl[k]) / a + pl[k]).toInt().coerceIn(0, 255) }
        val alpha = (a * Color.alpha(c)).toInt().coerceIn(0, 255)
        return Color.argb(alpha, rgb[0], rgb[1], rgb[2])
    }

    /**
     * How much of the edge a pattern covers: the least-covered of the four sides, so a design
     * touching one edge (CreditWise's arc) scores low and a grid running everywhere scores high.
     */
    private fun borderCoverage(b: Bitmap): Float {
        val w = b.width
        val inset = (w * 0.12f).toInt()
        val band = (w * 0.06f).toInt().coerceAtLeast(1)
        fun side(xs: IntRange, ys: IntRange): Float {
            var n = 0; var total = 0
            for (y in ys) for (x in xs) {
                total++
                if (Color.alpha(b.getPixel(x, y)) > 128) n++
            }
            return if (total == 0) 0f else n / total.toFloat()
        }
        val inner = inset until w - inset
        return minOf(
            side(inner, inset until inset + band), side(inner, w - inset - band until w - inset),
            side(inset until inset + band, inner), side(w - inset - band until w - inset, inner),
        )
    }

    /** Share of the icon's area that's visible. */
    private fun coverage(b: Bitmap): Float {
        var n = 0; var total = 0
        val step = maxOf(1, b.width / 40)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            total++
            if (Color.alpha(b.getPixel(x, y)) > 128) n++
        }
        return n / total.toFloat()
    }

    /** Whether [b] is (nearly) one color, like a plain icon background. */
    private fun isPlain(b: Bitmap): Boolean {
        val avg = average(b) ?: return true
        var n = 0; var off = 0
        val step = maxOf(1, b.width / 24)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            n++
            if (distance(c, avg) > 40) off++
        }
        return off <= n * 0.15f
    }

    /** The average color of the visible pixels, or null if there are none. */
    private fun average(b: Bitmap): Int? {
        val list = ArrayList<Int>()
        val step = maxOf(1, b.width / 32)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) >= 128) list += c
        }
        return if (list.isEmpty()) null else averageOf(list)
    }

    private fun averageOf(colors: List<Int>) = Color.rgb(
        colors.sumOf { Color.red(it) } / colors.size,
        colors.sumOf { Color.green(it) } / colors.size,
        colors.sumOf { Color.blue(it) } / colors.size,
    )

    /** One color, keeping the symbol's shape and shading: lighter parts come out stronger. */
    private fun toTint(fg: Bitmap, tint: Int): Bitmap {
        val out = Bitmap.createBitmap(fg.width, fg.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(fg.width * fg.height)
        fg.getPixels(pixels, 0, fg.width, 0, 0, fg.width, fg.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val strength = 0.45f + 0.55f * luminance(c)
            pixels[i] = (tint and 0xFFFFFF) or ((a * strength).toInt().coerceIn(0, 255) shl 24)
        }
        out.setPixels(pixels, 0, fg.width, 0, 0, fg.width, fg.height)
        return out
    }

    private const val TOLERANCE = 42f

    private fun quantize(c: Int) = (Color.red(c) / 24) * 10000 + (Color.green(c) / 24) * 100 + Color.blue(c) / 24

    private fun distance(a: Int, b: Int) = maxOf(
        kotlin.math.abs(Color.red(a) - Color.red(b)),
        kotlin.math.abs(Color.green(a) - Color.green(b)),
        kotlin.math.abs(Color.blue(a) - Color.blue(b)),
    ).toFloat()

    private fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun saturation(c: Int): Float {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        return hsv[1]
    }

    /** Bright enough to read on charcoal, keeping the hue. */
    private fun brighten(c: Int): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        hsv[2] = maxOf(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }
}
