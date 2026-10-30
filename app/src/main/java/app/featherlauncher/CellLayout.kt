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
    /** Dropping here makes or fills a folder: draw the highlight stronger. */
    var dropPreviewGrow = false
    /** The icons here have names under them (pages, when names are shown; never the dock). */
    var labels = false
    /** How far above the cell's center icons sit (they're raised by half the room kept for their names). */
    val iconOffset get() = if (labels) AppIconView.labelSpace(context, cellHeight.roundToInt()) / 2f else 0f

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val previewRadius = Ui.dp(context, 18).toFloat()

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
    /** Height (dp) of one icon with its highlight, for the dock's wrapped height and drop preview. */
    var boxDp = AppIconView.BOX

    /** Center-to-center distance of columns. */
    val cellWidth: Float
        get() = if (edgeGap >= 0 && cols > 1) (width - 2f * edgeGap - iconSize) / (cols - 1)
        else (width - paddingLeft - paddingRight) / cols.toFloat()
    val cellHeight get() = (height - paddingTop - paddingBottom) / rows.toFloat()
    /** Where column 0 starts (px); with [edgeGap] it's placed so the first icon is exactly that far in. */
    val originX: Float
        get() = if (edgeGap >= 0) edgeGap + iconSize / 2f - cellWidth / 2f else paddingLeft.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            // Wrap content (the dock): one row of square-ish cells.
            paddingTop + paddingBottom + rows * Ui.dp(context, boxDp)
        }
        setMeasuredDimension(width, height)
        val cw = if (edgeGap >= 0 && cols > 1) (width - 2f * edgeGap - iconSize) / (cols - 1) else (width - paddingLeft - paddingRight) / cols.toFloat()
        val ch = (height - paddingTop - paddingBottom) / rows.toFloat()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec((lp.w * cw).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((lp.h * ch).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = cellRect((child.layoutParams as LayoutParams).let { Rect(it.x, it.y, it.x + it.w, it.y + it.h) })
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    /** Pixel bounds, in this view, of the cells in [cells] (left/top inclusive, right/bottom exclusive). */
    fun cellRect(cells: Rect): Rect = Rect(
        (originX + cells.left * cellWidth).roundToInt(),
        (paddingTop + cells.top * cellHeight).roundToInt(),
        (originX + cells.right * cellWidth).roundToInt(),
        (paddingTop + cells.bottom * cellHeight).roundToInt(),
    )

    override fun dispatchDraw(canvas: Canvas) {
        dropPreview?.let { cells ->
            val r = cellRect(cells)
            if (dropPreviewIsIcon) {
                // Same square as the icon's own highlight, where the icon will sit
                // (raised by half the name's height on pages, where icons have names).
                val half = Ui.dp(context, boxDp) / 2f
                val cx = r.exactCenterX()
                val cy = r.exactCenterY() - iconOffset
                val radius = Ui.dp(context, AppIconView.ICON_RADIUS + AppIconView.INSET).toFloat()
                previewPaint.color = if (dropPreviewGrow) 0x55FFFFFF else 0x33FFFFFF
                canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, radius, radius, previewPaint)
            } else {
                val inset = Ui.dp(context, 4).toFloat()
                canvas.drawRoundRect(RectF(r).apply { inset(inset, inset) }, previewRadius, previewRadius, previewPaint)
            }
        }
        super.dispatchDraw(canvas)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams
    override fun generateDefaultLayoutParams() = LayoutParams(0, 0, 1, 1)
    override fun shouldDelayChildPressedState() = false

    fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, LayoutParams(x, y, w, h))
    }
}
