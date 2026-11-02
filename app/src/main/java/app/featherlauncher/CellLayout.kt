package app.featherlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** A grid of equal cells; each child covers the cells given by its [LayoutParams]. */
class CellLayout(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    var cols = Workspace.COLS
    var rows = Workspace.ROWS

    class LayoutParams(var x: Int, var y: Int, var w: Int, var h: Int) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    /** Where a dragged item would land, drawn as a soft highlight. */
    var dropPreview: Rect? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The item being dropped is an app icon, so preview the icon's square, not the whole cell. */
    var dropPreviewIsIcon = false
    /** The widget being dropped is straight on the wallpaper (see [widgetRect]). */
    var dropPreviewBare = false
    /** Dropping here makes or fills a folder: draw the highlight stronger. */
    var dropPreviewGrow = false

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    /**
     * For the dock: the gap (px) from the bar's edges to the first and last icon, and from the
     * top. The icons in between are spaced evenly. -1 means a plain grid of equal cells.
     */
    var edgeGap = -1
    /** Size of the icons (px), needed for [edgeGap] spacing. */
    var iconSize = 0
    /**
     * For the dock: how many of its places are in use. They sit together in the middle, as on
     * iOS, as far apart as when it's full.
     */
    var slots = cols
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }
    /** Height (dp) of one icon with its highlight, for the dock's wrapped height and drop preview. */
    var boxDp = AppIconView.BOX

    /**
     * A page: every row is an icon's picture with its name under it, as on iOS. A widget's
     * card runs from the edge of the first icon it covers to the edge of the last, and has
     * its name under it too, so everything on a page lines up.
     */
    var isPage = false
    /** Names under icons and widgets on a page ([Prefs.showLabels]). */
    var names = true
        set(value) {
            if (field != value) { field = value; requestLayout(); invalidate() }
        }
    /** Size (dp) of a page's icons, without their highlight. */
    var iconDp = AppIconView.ICON
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }
    /**
     * Room (px) above and below the page, up to the status bar and the dock, that counts
     * toward the gaps at its top and bottom: the gaps above the first row, between rows and
     * under the last row's names all come out the same.
     */
    var roomAbove = 0
    var roomBelow = 0

    /** A widget whose name isn't drawn: it's being resized, and its frame and handles are there instead. */
    var nameless: View? = null
        set(value) { field = value; invalidate() }

    /** On a page: the gap (px) between one row's names and the next row's icons. */
    var rowGap = 0f
        private set

    private val icon get() = Ui.dp(context, iconDp).toFloat()
    /** An icon's picture with its name under it (px). */
    private val content get() = icon + if (names) Ui.dp(context, AppIconView.INSET + AppIconView.LABEL_HEIGHT) else 0

    /** Center-to-center distance of columns. */
    val cellWidth: Float
        get() = if (edgeGap >= 0 && cols > 1) (measuredWidth - 2f * edgeGap - iconSize) / (cols - 1)
        else (measuredWidth - paddingLeft - paddingRight) / cols.toFloat()
    val cellHeight get() = if (isPage) content + rowGap else (measuredHeight - paddingTop - paddingBottom) / rows.toFloat()
    /**
     * Where column 0 starts (px). With [edgeGap], a full dock's first icon is exactly that far in,
     * and fewer [slots] sit in the middle.
     */
    val originX: Float
        get() = if (edgeGap >= 0) originFor(slots) else paddingLeft.toFloat()

    /** Where column 0 would start with [count] slots in use. */
    fun originFor(count: Int) = (measuredWidth - count * cellWidth) / 2
    /** Where row 0 starts (px). On a page, a row runs from halfway up the gap above it to halfway down the gap below. */
    val originY: Float
        get() = if (isPage) rowGap / 2 - roomAbove else paddingTop.toFloat()
    /** How far above a cell's center the picture's center is on a page: half the name under it. */
    val iconOffset: Float
        get() = if (isPage) (content - icon) / 2 else 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            // Wrap content (the dock): one row of square-ish cells.
            paddingTop + paddingBottom + rows * Ui.dp(context, boxDp)
        }
        setMeasuredDimension(width, height)
        if (isPage) rowGap = (height + roomAbove + roomBelow - rows * content) / (rows + 1)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val r = childRect(child)
            child.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // A page's grid, for drawing widgets at their real sizes (see [WidgetSheet]) and sizing new ones.
        if (isPage && cellWidth > 0 && cellHeight > 0) grid = Grid(cellWidth, cellHeight, icon, Ui.dp(context, CardWidget.GAP))
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = childRect(child)
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    /**
     * Where a child goes. On a page an icon covers its cell's width, from its highlight's top to
     * its name's bottom; a widget covers the pictures of the cells it spans, plus the room for
     * its keypad highlight around them ([CardWidget.GAP]).
     */
    private fun childRect(child: View): Rect {
        val cells = (child.layoutParams as LayoutParams).let { Rect(it.x, it.y, it.x + it.w, it.y + it.h) }
        if (!isPage) return cellRect(cells)
        if (child is AppIconView) {
            val cell = cellRect(cells)
            val inset = Ui.dp(context, AppIconView.INSET)
            val top = (pictureTop(cells.top) - inset).roundToInt()
            val name = if (names) Ui.dp(context, AppIconView.LABEL_HEIGHT) else 0
            return Rect(cell.left, top, cell.right, (top + icon + 2 * inset + name).roundToInt())
        }
        return widgetRect(cells, child.getTag(R.id.home_bare) == true)
    }

    private fun pictureTop(row: Int) = originY + row * cellHeight + rowGap / 2

    /** Pixel bounds, in this view, of the cells in [cells] (left/top inclusive, right/bottom exclusive). */
    fun cellRect(cells: Rect): Rect = Rect(
        (originX + cells.left * cellWidth).roundToInt(),
        (originY + cells.top * cellHeight).roundToInt(),
        (originX + cells.right * cellWidth).roundToInt(),
        (originY + cells.bottom * cellHeight).roundToInt(),
    )

    /**
     * The pictures of the cells in [cells], as one: from the left edge of the first column's
     * icons to the right edge of the last's, and from the top of the first row's to the bottom
     * of the last row's. Where a widget's card goes.
     */
    fun pictureRect(cells: Rect): RectF {
        val half = (cellWidth - icon) / 2
        return RectF(
            originX + cells.left * cellWidth + half,
            pictureTop(cells.top),
            originX + cells.right * cellWidth - half,
            pictureTop(cells.bottom - 1) + icon,
        )
    }

    /**
     * A widget's bounds: its picture with the room for its keypad highlight around it. A [bare]
     * widget, straight on the wallpaper, has no edges to line up and no name: it reaches down
     * over the room for one.
     */
    fun widgetRect(cells: Rect, bare: Boolean = false): Rect {
        val gap = Ui.dp(context, CardWidget.GAP).toFloat()
        val r = pictureRect(cells)
        if (bare && names) r.bottom += Ui.dp(context, AppIconView.INSET + AppIconView.LABEL_HEIGHT)
        return Rect((r.left - gap).roundToInt(), (r.top - gap).roundToInt(), (r.right + gap).roundToInt(), (r.bottom + gap).roundToInt())
    }

    /** The bottom (px) of the names under the given row's icons, or of the icons with names off. */
    fun namesBottom(row: Int) = pictureTop(row) + content

    // The names under widgets, just as the names under icons ([AppIconView]).
    private val namePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, AppIconView.NAME_SP, resources.displayMetrics)
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(Ui.dp(context, 3).toFloat(), 0f, Ui.dp(context, 1).toFloat(), 0x99000000.toInt())
    }

    override fun dispatchDraw(canvas: Canvas) {
        dropPreview?.let { cells ->
            if (dropPreviewIsIcon) {
                // Exactly where the icon will be, with its corners (raised by half the name's
                // height on pages, where icons have names). Over a folder to be, it's the icon's
                // highlight around the one there, so it shows.
                val sizeDp = if (isPage) iconDp else boxDp - 2 * AppIconView.INSET
                val inset = if (dropPreviewGrow) AppIconView.INSET else 0
                val half = Ui.dp(context, sizeDp + 2 * inset) / 2f
                val r = cellRect(cells)
                val cx = r.exactCenterX()
                val cy = r.exactCenterY() - iconOffset
                val radius = Ui.dp(context, AppIconView.corner(sizeDp) + inset).toFloat()
                previewPaint.color = if (dropPreviewGrow) 0x55FFFFFF else 0x33FFFFFF
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, radius, radius, previewPaint)
            } else {
                // Exactly where the widget's card will be, with its corners.
                val radius = Ui.dp(context, WidgetStyle.radius).toFloat()
                val gap = Ui.dp(context, CardWidget.GAP).toFloat()
                val r = if (isPage) RectF(widgetRect(cells, dropPreviewBare)).apply { inset(gap, gap) } else RectF(cellRect(cells))
                canvas.drawRoundRect(r, radius, radius, previewPaint)
            }
        }
        super.dispatchDraw(canvas)
        if (isPage && names) for (i in 0 until childCount) drawName(canvas, getChildAt(i))
    }

    /** A widget's name, under its card where an icon's name is under the icon, moving and fading with it. */
    private fun drawName(canvas: Canvas, child: View) {
        val name = child.getTag(R.id.home_name) as? String ?: return
        if (child.visibility != VISIBLE || child.alpha == 0f || name.isEmpty() || child === nameless) return
        val gap = Ui.dp(context, CardWidget.GAP)
        val room = child.width - 2 * gap - 2 * Ui.dp(context, AppIconView.NAME_PADDING).toFloat()
        namePaint.letterSpacing = AppIconView.nameSpacing(namePaint, name, room)
        val text = android.text.TextUtils.ellipsize(name, namePaint, room, android.text.TextUtils.TruncateAt.END).toString()
        val m = namePaint.fontMetrics
        val top = child.bottom - gap + Ui.dp(context, AppIconView.INSET) + child.translationY
        val baseline = top + (Ui.dp(context, AppIconView.LABEL_HEIGHT) - (m.descent - m.ascent)) / 2 - m.ascent
        val x = child.left + child.width / 2f + child.translationX - namePaint.measureText(text) / 2
        namePaint.alpha = (child.alpha * 255).roundToInt()
        canvas.drawText(text, x, baseline, namePaint)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, 1, 1)
    override fun shouldDelayChildPressedState() = false

    fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        // Moved within this page, it just moves: taking it off and putting it back made a widget
        // stop and start again (its tick, its refresh) at every step of a resize.
        val lp = view.layoutParams as? LayoutParams
        if (view.parent === this && lp != null) {
            lp.x = x; lp.y = y; lp.w = w; lp.h = h
            view.requestLayout()
            invalidate()
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, LayoutParams(x, y, w, h))
    }

    /** A page's grid in pixels: its cells, its icons' size, and the room around a widget's card. */
    class Grid(val cellWidth: Float, val cellHeight: Float, val icon: Float, val gap: Int) {
        /** The size (px) of a widget [w] by [h] cells, its keypad highlight's room included ([widgetRect]). */
        fun widgetSize(w: Int, h: Int) =
            ((w - 1) * cellWidth + icon + 2 * gap).roundToInt() to ((h - 1) * cellHeight + icon + 2 * gap).roundToInt()
    }

    companion object {
        /** A page's grid, as last laid out. */
        @Volatile var grid: Grid? = null
    }
}
