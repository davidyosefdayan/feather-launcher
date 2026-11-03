package app.featherlauncher

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Every home screen page at once, as iOS's Edit Pages: the check under a page shows or hides
 * it, holding a page and dragging moves it, and a hidden page can be deleted. A hidden page
 * keeps everything on it, ready to be shown again.
 */
class PagesScreen(
    private val activity: Activity,
    private val home: View,
    private val homeScreen: HomeScreen,
    private val overlays: Overlays,
    private val palette: () -> Palette,
) {
    private lateinit var grid: Grid
    private lateinit var p: Palette

    fun open() {
        val source = homeScreen.pages.page(0)
        // The page with the room above and below it that counts as part of it (see CellLayout.roomAbove).
        val pageWidth = source.width
        val pageHeight = source.height + source.roomAbove + source.roomBelow
        if (pageWidth == 0) return
        p = palette()
        val widthDp = activity.resources.displayMetrics.run { widthPixels / density }
        grid = Grid(if (widthDp >= 360) 3 else 2, pageHeight.toFloat() / pageWidth)
        val focused = homeScreen.pages.currentPage
        cellsWidth = pageWidth
        cellsHeight = source.height
        // The shown pages are drawn from the home screen while it zooms away, then the pages
        // themselves come here: they stay as they are while they're shown, hidden or moved, and
        // go back to the home screen on closing. Hidden ones are laid out on their own.
        val shownCells = homeScreen.shownPages()
        var next = 0
        homeScreen.workspace.allPages().forEach { page ->
            grid.add(Tile(page.hidden).apply {
                cells = if (page.hidden) homeScreen.buildPage(page.items) else shownCells[next++]
                if (page.hidden) holdCells() else mirrorCells()
            })
        }

        val insets = overlays.insets
        // Headed and colored as the app list is.
        val title = TextView(activity).apply {
            text = "Pages"
            textSize = 30f
            typeface = Ui.medium
            includeFontPadding = false
            setTextColor(p.onWall)
        }
        val done = Components.pillButton(activity, "Done", p.glass, p.onWall) { overlays.dismissScreen() }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_S), dp(Ui.SPACE_L), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, insets.bottom + dp(Ui.SPACE_L))
            addView(grid)
        }
        val screen = object : LinearLayout(activity) {
            // Taps on the gaps between pages stop here, not on the home screen; nothing is
            // touched while it's coming or going.
            override fun onInterceptTouchEvent(event: MotionEvent) = animator?.isRunning == true
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent) = true
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.drawer)
            setPadding(0, insets.top, 0, 0)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        this.screen = screen
        overlays.showScreen(screen) { remove ->
            // The pages go back first, drawn here from there while this zooms away.
            val shown = grid.tiles.filter { !it.hidden }
            homeScreen.returnPages(shown.map { it.cells })
            shown.forEach { it.mirrorCells() }
            transition(opening = false) { remove() }
        }
        transition(opening = true) {
            homeScreen.lendPages()
            grid.tiles.filter { !it.hidden }.forEach { it.holdCells() }
        }
        if (!grid.isInTouchMode) grid.post { grid.tiles.filter { !it.hidden }.getOrNull(focused)?.requestFocus() }
    }

    private lateinit var screen: ViewGroup
    private var cellsWidth = 0
    private var cellsHeight = 0

    /**
     * In two halves, as the app list comes: the home screen falls back and fades while the
     * background comes up over the wallpaper, then what's here fades in, settling back from a
     * little bigger, so everything moves the same way (the other way, closing). The app list
     * slides; this zooms.
     */
    private fun transition(opening: Boolean, done: () -> Unit) {
        home.visibility = View.VISIBLE
        home.pivotX = home.width / 2f
        home.pivotY = home.height / 2f
        val backdrop = screen.background
        val content = (0 until screen.childCount).map { screen.getChildAt(it) }
        fun apply(t: Float) {
            val first = (t / 0.5f).coerceIn(0f, 1f)
            val second = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
            home.alpha = 1 - first
            home.scaleX = 1 - 0.08f * first
            home.scaleY = 1 - 0.08f * first
            backdrop.alpha = (255 * first).toInt()
            for (view in content) {
                // All of it zooms about the screen's middle, as one piece (the background stays
                // put). Set as it goes: opening, it's laid out only after the first frame.
                view.pivotX = home.width / 2f - view.left
                view.pivotY = home.height / 2f - view.top
                view.alpha = second
                view.scaleX = 1.06f - 0.06f * second
                view.scaleY = 1.06f - 0.06f * second
            }
        }
        val from = (animator?.animatedValue as? Float) ?: if (opening) 0f else 1f
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(from, if (opening) 1f else 0f).apply {
            duration = TRANSITION
            interpolator = HomeActivity.PANEL_EASING
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    home.scaleX = 1f
                    home.scaleY = 1f
                    home.alpha = if (opening) 0f else 1f
                    if (opening) home.visibility = View.INVISIBLE
                    done()
                }
            })
            apply(from)
            start()
        }
    }

    private var animator: android.animation.ValueAnimator? = null

    /** A page drawn at the size of its picture. */
    private val scalePage = View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
        val page = (v as ViewGroup).getChildAt(0) as? CellLayout ?: return@OnLayoutChangeListener
        val scale = (right - left).toFloat() / page.layoutParams.width.coerceAtLeast(1)
        page.pivotX = 0f
        page.pivotY = 0f
        page.scaleX = scale
        page.scaleY = scale
        page.translationY = page.roomAbove * scale
    }

    /** A page on the home screen, drawn small. */
    private inner class Mirror(private val cells: CellLayout) : View(activity) {
        override fun onDraw(canvas: android.graphics.Canvas) {
            val scale = width.toFloat() / cells.width.coerceAtLeast(1)
            canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(0f, cells.roomAbove.toFloat())
            cells.draw(canvas)
            canvas.restore()
        }
    }

    private fun toggle(tile: Tile) {
        if (!tile.hidden && grid.tiles.count { !it.hidden } == 1) {
            // At least one page stays on the home screen.
            tile.performHapticFeedback(HapticFeedbackConstants.REJECT)
            ObjectAnimator.ofFloat(tile, View.TRANSLATION_X, 0f, px(10f), -px(10f), px(6f), -px(6f), 0f).setDuration(320).start()
            return
        }
        tile.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        homeScreen.setPageHidden(grid.tiles.indexOf(tile), !tile.hidden)
        tile.setHidden(!tile.hidden, animate = true)
    }

    private fun move(tile: Tile, to: Int) {
        val from = grid.tiles.indexOf(tile)
        if (to == from || to !in grid.tiles.indices) return
        grid.place(tile, to)
        homeScreen.movePage(from, to)
    }

    private fun confirmDelete(tile: Tile) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(TextView(activity).apply {
                val position = grid.tiles.indexOf(tile)
                val widgets = homeScreen.workspace.allPages().getOrNull(position)?.items.orEmpty().any { it.isWidget }
                text = if (widgets) "Its apps will stay in the app list. Its widgets will be removed."
                    else "Its apps will stay in the app list."
                style(TextStyle.Body, p.textSecondary)
            })
            addView(Components.pillButton(activity, "Delete", p.danger, p.onAccent) {
                overlays.dismiss()
                val position = grid.tiles.indexOf(tile)
                if (position < 0) return@pillButton
                homeScreen.deletePage(position)
                grid.remove(tile)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_L) })
        }
        overlays.showSheet("Delete page?", column)
    }

    /** With the keypad, held OK or Menu: what can be done to the page besides showing or hiding it. */
    private fun menu(tile: Tile) {
        val position = grid.tiles.indexOf(tile)
        val r = Rect().also { tile.picture.getGlobalVisibleRect(it) }
        val rootOffset = IntArray(2).also { (home.parent as View).getLocationOnScreen(it) }
        r.offset(-rootOffset[0], -rootOffset[1])
        overlays.showMenu(null, listOfNotNull(
            MenuItem("Move left", R.drawable.ic_chevron_left) { move(tile, position - 1) }.takeIf { position > 0 },
            MenuItem("Move right", R.drawable.ic_chevron_right) { move(tile, position + 1) }.takeIf { position < grid.tiles.size - 1 },
            MenuItem("Delete page", R.drawable.ic_delete, danger = true) { confirmDelete(tile) }.takeIf { tile.hidden },
        ), r)
    }

    /**
     * One page: its picture, and a switch under it that's on while the page is on the home
     * screen. A hidden page is faded, with a button on its corner to delete it.
     */
    @SuppressLint("ViewConstructor")
    private inner class Tile(hidden: Boolean) : ViewGroup(activity) {
        var hidden = hidden
            private set
        lateinit var cells: CellLayout

        /** The page itself in the picture. */
        fun holdCells() {
            picture.removeAllViews()
            (cells.parent as? ViewGroup)?.removeView(cells)
            picture.addView(cells, FrameLayout.LayoutParams(cellsWidth, cellsHeight))
        }

        /** The page drawn from where it is on the home screen. */
        fun mirrorCells() {
            picture.removeAllViews()
            picture.addView(Mirror(cells), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val picture = FrameLayout(activity).apply {
            background = Ui.rounded(activity, (p.onWall and 0xFFFFFF) or 0x14000000, Ui.RADIUS_M)
            clipToOutline = true
            addOnLayoutChangeListener(scalePage)
        }
        /** The keypad's ring, just outside the picture. */
        private val ring = View(activity).apply {
            background = Ui.focusRing(activity, Ui.RADIUS_M + RING_GAP, p.onWall)
            isDuplicateParentStateEnabled = true
        }
        private val switch = FeatherSwitch(activity, p, !hidden).apply { onWall = true }
        private val delete = ImageView(activity).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = Ui.pressable(activity, Ui.RADIUS_PILL, Ui.rounded(activity, p.glass, Ui.RADIUS_PILL, p.hairline), p.onWall)
            contentDescription = "Delete page"
            setOnClickListener { confirmDelete(this@Tile) }
        }

        init {
            clipChildren = false
            isFocusable = true
            isClickable = true
            addView(picture)
            addView(ring)
            addView(switch)
            addView(delete)
            setHidden(hidden, animate = false)
            setOnClickListener { toggle(this) }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (isInTouchMode) grid.startDrag(this) else menu(this)
                true
            }
            setOnKeyListener { _, code, event ->
                if (code != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) menu(this)
                true
            }
        }

        fun setHidden(value: Boolean, animate: Boolean) {
            hidden = value
            contentDescription = if (value) "Hidden page" else "Page"
            switch.checked = !value
            val alpha = if (value) 0.4f else 1f
            if (animate) {
                picture.animate().alpha(alpha).setDuration(200).start()
                delete.visibility = View.VISIBLE
                delete.animate().cancel()
                delete.animate().alpha(if (value) 1f else 0f).scaleX(if (value) 1f else 0.6f).scaleY(if (value) 1f else 0.6f)
                    .setDuration(200).setInterpolator(DecelerateInterpolator(2f))
                    .withEndAction { if (!hidden) delete.visibility = View.INVISIBLE }.start()
            } else {
                picture.alpha = alpha
                delete.alpha = if (value) 1f else 0f
                delete.visibility = if (value) View.VISIBLE else View.INVISIBLE
            }
        }

        // Touches on the page go to the tile (to show, hide or drag it), never to what's on the page.
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            val r = Rect()
            delete.getHitRect(r)
            return !(hidden && r.contains(event.x.toInt(), event.y.toInt()))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val gap = dp(RING_GAP)
            val pictureWidth = width - 2 * gap
            val pictureHeight = (pictureWidth * grid.ratio).toInt()
            picture.measure(exactly(pictureWidth), exactly(pictureHeight))
            ring.measure(exactly(width), exactly(pictureHeight + 2 * gap))
            switch.measure(exactly(dp(FeatherSwitch.WIDTH)), exactly(dp(FeatherSwitch.HEIGHT)))
            delete.measure(exactly(dp(DELETE)), exactly(dp(DELETE)))
            setMeasuredDimension(width, pictureHeight + 2 * gap + dp(Ui.SPACE_M) + dp(FeatherSwitch.HEIGHT))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val gap = dp(RING_GAP)
            val width = r - l
            picture.layout(gap, gap, gap + picture.measuredWidth, gap + picture.measuredHeight)
            ring.layout(0, 0, width, ring.measuredHeight)
            val switchTop = ring.measuredHeight + dp(Ui.SPACE_M)
            switch.layout((width - switch.measuredWidth) / 2, switchTop, (width + switch.measuredWidth) / 2, switchTop + switch.measuredHeight)
            // In the picture's top corner, over it.
            val inset = gap + dp(Ui.SPACE_S)
            delete.layout(width - inset - delete.measuredWidth, inset, width - inset, inset + delete.measuredHeight)
        }
    }

    /** The pages in rows, in order. Holding one and dragging moves it; the others slide out of its way. */
    private inner class Grid(val cols: Int, val ratio: Float) : ViewGroup(activity) {
        val tiles = mutableListOf<Tile>()
        private val gapX = dp(Ui.SPACE_L)
        private val gapY = dp(Ui.SPACE_XL)
        private val side = dp(Ui.SPACE_XL)
        private var cellWidth = 0
        private var cellHeight = 0

        private var dragged: Tile? = null
        private var dragFrom = 0
        private var grabX = 0f
        private var grabY = 0f
        private var lastX = 0f
        private var lastY = 0f

        init {
            clipChildren = false
            setPadding(side, dp(Ui.SPACE_S), side, 0)
        }

        fun add(tile: Tile) {
            tiles += tile
            addView(tile)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            cellWidth = (width - paddingLeft - paddingRight - (cols - 1) * gapX) / cols
            tiles.forEach { it.measure(exactly(cellWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
            cellHeight = tiles.firstOrNull()?.measuredHeight ?: 0
            val rows = (tiles.size + cols - 1) / cols
            setMeasuredDimension(width, paddingTop + paddingBottom + rows * cellHeight + (rows - 1).coerceAtLeast(0) * gapY)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = layoutTiles()

        private fun slotLeft(i: Int) = paddingLeft + (i % cols) * (cellWidth + gapX)
        private fun slotTop(i: Int) = paddingTop + (i / cols) * (cellHeight + gapY)

        private fun layoutTiles() {
            tiles.forEachIndexed { i, tile ->
                tile.layout(slotLeft(i), slotTop(i), slotLeft(i) + cellWidth, slotTop(i) + cellHeight)
            }
        }

        /**
         * Puts [tile] at [to], the others sliding into their new places from wherever they are
         * now, as icons do on the home screen.
         */
        fun place(tile: Tile, to: Int) {
            val before = tiles.associateWith { (it.left + it.translationX) to (it.top + it.translationY) }
            tiles.remove(tile)
            tiles.add(to, tile)
            slide(before)
        }

        fun remove(tile: Tile) {
            val before = tiles.associateWith { (it.left + it.translationX) to (it.top + it.translationY) }
            tiles.remove(tile)
            tile.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction { removeView(tile) }.start()
            slide(before)
            requestLayout()
        }

        private fun slide(before: Map<Tile, Pair<Float, Float>>) {
            layoutTiles()
            for (tile in tiles) {
                val (x, y) = before[tile] ?: continue
                tile.translationX = x - tile.left
                tile.translationY = y - tile.top
                if (tile === dragged) continue
                tile.animate().translationX(0f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }

        fun startDrag(tile: Tile) {
            dragged = tile
            dragFrom = tiles.indexOf(tile)
            grabX = lastX - tile.left - tile.translationX
            grabY = lastY - tile.top - tile.translationY
            parent.requestDisallowInterceptTouchEvent(true)
            tile.bringToFront()
            tile.animate().scaleX(1.08f).scaleY(1.08f).translationZ(px(8f)).setDuration(150).start()
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            lastX = event.x
            lastY = event.y
            return dragged != null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val tile = dragged ?: return false
            lastX = event.x
            lastY = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    tile.translationX = event.x - grabX - tile.left
                    tile.translationY = event.y - grabY - tile.top
                    val cx = event.x - grabX + cellWidth / 2f - paddingLeft
                    val cy = event.y - grabY + cellHeight / 2f - paddingTop
                    val col = (cx / (cellWidth + gapX)).toInt().coerceIn(0, cols - 1)
                    val row = (cy / (cellHeight + gapY)).toInt().coerceAtLeast(0)
                    val to = (row * cols + col).coerceIn(0, tiles.size - 1)
                    if (to != tiles.indexOf(tile)) place(tile, to)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragged = null
                    tile.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f)
                        .setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
                    val to = tiles.indexOf(tile)
                    if (to != dragFrom) {
                        homeScreen.movePage(dragFrom, to)
                    }
                }
            }
            return true
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun dp(value: Int) = Ui.dp(activity, value)
    private fun px(value: Float) = Ui.dp(activity, value).toFloat()

    companion object {
        /** Room between a page's picture and the keypad's ring around it (dp). */
        private const val RING_GAP = 4
        private const val DELETE = 32
        /** As long as the app list takes to open. */
        private const val TRANSITION = 380L
    }
}
