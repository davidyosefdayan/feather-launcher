package app.featherlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Something placed on the home screen or in the dock. Positions and sizes are in grid cells. */
class HomeItem(
    val id: Long,
    var page: Int,
    var x: Int,
    var y: Int,
    var w: Int,
    var h: Int,
    val type: Type,
    /** App component key, app widget id, or empty for built-ins. */
    val ref: String,
    /** A name the user gave it, shown instead of the app's own. */
    var label: String? = null,
    /** For folders: the app keys inside, in order. */
    val apps: MutableList<String> = mutableListOf(),
) {
    enum class Type { APP, WIDGET, CLOCK, FOLDER }

    val inDock get() = page == Workspace.DOCK
    val isWidget get() = type == Type.WIDGET || type == Type.CLOCK
}

/**
 * A page taken off the home screen, kept with everything on it to show again. It sits after
 * the first [after] pages that are shown; hidden pages with the same [after] keep their order.
 */
class HiddenPage(var after: Int, val items: MutableList<HomeItem>)

/**
 * The home screen layout: pages of cells plus the dock row, saved as JSON. [items] and
 * [pageCount] are only the pages that are shown, so everything else works with those alone;
 * [hidden] pages are kept apart.
 */
class Workspace(context: Context) {
    private val file = File(context.filesDir, "workspace.json")

    val items = mutableListOf<HomeItem>()
    var pageCount = 1
        private set
    val hidden = mutableListOf<HiddenPage>()
    /** True when no layout was saved yet, so the caller should set up defaults. */
    var isNew = false
        private set

    /**
     * The highest id handed out so far. Ids are never used twice: the home screen keeps a view
     * per id, and a folder turned back into an app must not get the folder's view.
     */
    private var lastId = 0L

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) {
            isNew = true
            return
        }
        runCatching {
            val json = JSONObject(file.readText())
            pageCount = json.optInt("pages", 1).coerceAtLeast(1)
            lastId = json.optLong("lastId")
            items += read(json.getJSONArray("items"))
            json.optJSONArray("hidden")?.let { pages ->
                for (i in 0 until pages.length()) {
                    val o = pages.getJSONObject(i)
                    hidden += HiddenPage(o.getInt("after").coerceIn(0, pageCount), read(o.getJSONArray("items")).toMutableList())
                }
            }
        }.onFailure { isNew = true }
        hidden.removeAll { it.items.isEmpty() }
        fitToGrid()
    }

    private fun read(array: JSONArray) = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        HomeItem(
            o.getLong("id"), o.getInt("page"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"),
            HomeItem.Type.valueOf(o.getString("type")), o.optString("ref"),
            o.optString("label").ifEmpty { null },
            o.optJSONArray("apps")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
        )
    }

    private fun write(items: List<HomeItem>) = JSONArray().apply {
        items.forEach {
            put(JSONObject().apply {
                put("id", it.id); put("page", it.page); put("x", it.x); put("y", it.y)
                put("w", it.w); put("h", it.h); put("type", it.type.name); put("ref", it.ref)
                it.label?.let { label -> put("label", label) }
                if (it.apps.isNotEmpty()) put("apps", JSONArray(it.apps))
            })
        }
    }

    /**
     * The rows depend on the screen (see [fitRows]), so after the display size changes, say,
     * something can reach past the last row. It moves to the first free spot from its page on.
     */
    private fun fitToGrid() {
        val out = items.filter { !it.inDock && it.y + it.h > ROWS }
        items -= out.toSet()
        for (item in out) {
            item.h = item.h.coerceAtMost(ROWS)
            val (page, spot) = (item.page until pageCount).firstNotNullOfOrNull { p -> findSpot(p, item.w, item.h)?.let { p to it } }
                ?: (addPage() to (0 to 0))
            item.page = page
            item.x = spot.first
            item.y = spot.second
            items += item
        }
        // On a hidden page, to a free spot on the same page, or the bottom row if it's full.
        for (page in hidden) for (item in page.items.filter { it.y + it.h > ROWS }) {
            item.h = item.h.coerceAtMost(ROWS)
            val spot = spotAmong(page.items, COLS, ROWS, item.w, item.h, item)
            item.x = spot?.first ?: item.x
            item.y = spot?.second ?: (ROWS - item.h)
        }
    }

    fun save() {
        val json = JSONObject().put("pages", pageCount).put("lastId", lastId).put("items", write(items))
        if (hidden.isNotEmpty()) json.put("hidden", JSONArray().apply {
            hidden.forEach { put(JSONObject().put("after", it.after).put("items", write(it.items))) }
        })
        val text = json.toString()
        isNew = false
        // Written in the background (in order, so the last save wins), and only when something changed.
        if (text == saved) return
        saved = text
        writer.execute {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    /** What's in the file, as last saved here. */
    private var saved: String? = null

    fun nextId(): Long {
        lastId = maxOf(lastId, items.maxOfOrNull { it.id } ?: 0L, hidden.maxOfOrNull { p -> p.items.maxOf { it.id } } ?: 0L) + 1
        return lastId
    }

    fun add(page: Int, x: Int, y: Int, w: Int, h: Int, type: HomeItem.Type, ref: String): HomeItem =
        HomeItem(nextId(), page, x, y, w, h, type, ref).also { items += it }

    fun on(page: Int) = items.filter { it.page == page }

    fun cols(page: Int) = if (page == DOCK) DOCK_COLS else COLS
    fun rows(page: Int) = if (page == DOCK) 1 else ROWS

    fun isFree(page: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem? = null): Boolean =
        isFreeAmong(on(page), cols(page), rows(page), x, y, w, h, ignore)

    private fun isFreeAmong(others: List<HomeItem>, cols: Int, rows: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem?): Boolean {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false
        return others.none { it !== ignore && x < it.x + it.w && it.x < x + w && y < it.y + it.h && it.y < y + h }
    }

    private fun spotAmong(others: List<HomeItem>, cols: Int, rows: Int, w: Int, h: Int, ignore: HomeItem?): Pair<Int, Int>? {
        for (y in 0..rows - h) for (x in 0..cols - w) if (isFreeAmong(others, cols, rows, x, y, w, h, ignore)) return x to y
        return null
    }

    /** First free spot, reading order. */
    fun findSpot(page: Int, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..rows(page) - h) for (x in 0..cols(page) - w) if (isFree(page, x, y, w, h)) return x to y
        return null
    }

    /** The free spot closest to where the item is being held. */
    fun nearestSpot(page: Int, x: Int, y: Int, w: Int, h: Int, ignore: HomeItem?): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDistance = Int.MAX_VALUE
        for (cy in 0..rows(page) - h) for (cx in 0..cols(page) - w) {
            if (!isFree(page, cx, cy, w, h, ignore)) continue
            val d = abs(cx - x) + abs(cy - y)
            if (d < bestDistance) {
                bestDistance = d
                best = cx to cy
            }
        }
        return best
    }

    fun addPage(): Int = pageCount++

    /**
     * Drops empty pages after the first, moving later pages back, and hidden pages with nothing
     * left on them. Returns true if any shown pages were removed.
     */
    fun removeEmptyPages(): Boolean {
        hidden.removeAll { it.items.isEmpty() }
        var removed = false
        var page = pageCount - 1
        while (page > 0) {
            if (on(page).isEmpty()) {
                items.filter { it.page > page }.forEach { it.page-- }
                hidden.filter { it.after > page }.forEach { it.after-- }
                pageCount--
                removed = true
            }
            page--
        }
        return removed
    }

    /** One page in the order of all of them, shown or hidden, with what's on it. */
    class Page(val items: List<HomeItem>, val hidden: Boolean)

    /** Every page in order, the hidden ones in their places. */
    fun allPages(): List<Page> {
        val out = ArrayList<Page>()
        for (shown in 0..pageCount) {
            hidden.filter { it.after == shown }.forEach { out += Page(it.items, true) }
            if (shown < pageCount) out += Page(on(shown), false)
        }
        return out
    }

    /**
     * Lays the pages out again as [pages] has them: the shown ones numbered in order, the
     * hidden ones put away in their places. The items themselves are the same objects.
     */
    fun arrange(pages: List<Page>) {
        val dock = on(DOCK)
        items.clear()
        items += dock
        hidden.clear()
        var shown = 0
        for (page in pages) {
            if (page.hidden) {
                hidden += HiddenPage(shown, page.items.toMutableList())
            } else {
                page.items.forEach { it.page = shown }
                items += page.items
                shown++
            }
        }
        pageCount = shown
    }

    companion object {
        /** Saves the home screen's layout off the main thread. */
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

        const val DOCK = -1
        const val COLS = 4
        /**
         * Rows on each page: as many as keeps the cells about square, so a 2 by 2 widget is a
         * square on every screen, but never so many that an icon and its name don't fit in a
         * cell. Set once at startup.
         */
        var ROWS = 5
            private set

        /** Space above the first row; the page's outline while dragging starts here. */
        const val PAGE_TOP = 6
        /** The screen's width (dp), set by [fitRows]. */
        private var widthDp = 0f

        /**
         * How much smaller the home screen's icons and the dock are on a narrow screen: enough
         * that the dock has as much room between its icons as at its edges, and an icon with its
         * highlight fits its column. 1 where they fit as they are.
         */
        private fun scale(names: Boolean): Float {
            if (widthDp == 0f) return 1f
            val icon = if (names) AppIconView.ICON else AppIconView.LARGE_ICON
            // Pages are inset from the screen's edges as much as the dock is.
            val width = widthDp - 2 * Ui.BAR_MARGIN
            // The dock's icons and the gaps beside them, each as wide as the room above them.
            val dock = width / (DOCK_COLS * icon + (DOCK_COLS + 1) * (Ui.DOCK_HEIGHT - icon) / 2f)
            val page = (width / COLS - 2 * AppIconView.INSET) / icon
            return minOf(1f, dock, page)
        }

        /** The home screen's icons (dp), on pages and in the dock: [AppIconView.ICON], or [AppIconView.LARGE_ICON] with no names. */
        fun icon(names: Boolean) = ((if (names) AppIconView.ICON else AppIconView.LARGE_ICON) * scale(names)).toInt()

        /** The dock's height (dp): [Ui.DOCK_HEIGHT], made as much smaller as the icons. */
        fun dockHeight(names: Boolean) = kotlin.math.round(Ui.DOCK_HEIGHT * scale(names)).toInt()

        fun fitRows(activity: android.app.Activity) {
            val window = activity.windowManager.currentWindowMetrics
            val bars = window.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
            val density = activity.resources.displayMetrics.density
            widthDp = window.bounds.width() / density
            // The page's share of the screen (see activity_home.xml): all but the system bars, the
            // page's top margin and [PAGE_TOP], the page dots, and the dock with its bottom margin.
            // Counted with names, so turning them off doesn't change the rows.
            val pageDp = (window.bounds.height() - bars.top - bars.bottom) / density - 8 - PAGE_TOP - 20 - dockHeight(true) - 16
            // The least height of a cell: an icon with its highlight, its name, and a little room.
            val minCell = icon(true) + 2 * AppIconView.INSET + AppIconView.LABEL_HEIGHT + 4
            val cellDp = (widthDp - 2 * Ui.BAR_MARGIN) / COLS
            ROWS = kotlin.math.round(pageDp / cellDp).toInt().coerceAtMost((pageDp / minCell).toInt()).coerceIn(3, 7)
        }
        const val DOCK_COLS = 4
    }
}
