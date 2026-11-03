package app.featherlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Hosting other apps' widgets, plus the picker that also offers Feather's own. */
class Widgets(
    private val activity: Activity,
    private val overlays: Overlays,
    private val palette: () -> Palette,
    private val appIcon: (packageName: String) -> Bitmap?,
    /** The home screen's grid, for working out how many cells a widget needs. */
    private val grid: () -> CellLayout.Grid,
) {
    /**
     * A widget was bound (and set up, if it needed it) and should be placed on the home screen:
     * where it was dropped ([at]: page, column, row) if it was dragged there, else anywhere.
     */
    var onAdded: ((appWidgetId: Int, spanX: Int, spanY: Int, at: Triple<Int, Int, Int>?) -> Unit)? = null
    /** One of Feather's own widgets was picked. */
    var onFeatherPicked: ((FeatherKind) -> Unit)? = null
    /** One of Feather's own widgets was held, to be dragged onto a page; the view is its preview. */
    var onFeatherHeld: ((FeatherKind, View) -> Unit)? = null
    /** Another app's widget was held, to be dragged onto a page; the view is its preview, the sizes its cells. */
    var onWidgetHeld: ((AppWidgetProviderInfo, View, Int, Int) -> Unit)? = null

    private val manager = AppWidgetManager.getInstance(activity)
    private val density = activity.resources.displayMetrics.density
    private val host = object : AppWidgetHost(activity.applicationContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            WidgetView(context)
    }
    private val previews = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** Where the widget being added was dropped, if it was dragged onto a page. */
    private var pendingSpot: Triple<Int, Int, Int>? = null

    fun startListening() = host.startListening()
    fun stopListening() = host.stopListening()

    fun createView(appWidgetId: Int): WidgetView? {
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return host.createView(activity.applicationContext, appWidgetId, info) as WidgetView
    }

    fun delete(appWidgetId: Int) = host.deleteAppWidgetId(appWidgetId)

    fun info(appWidgetId: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(appWidgetId)

    /** The widget has a settings screen that can be opened again after it was added. */
    fun canReconfigure(appWidgetId: Int): Boolean {
        val info = info(appWidgetId) ?: return false
        return info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun reconfigure(appWidgetId: Int) {
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, REQUEST_RECONFIGURE, setupOptions()) }
    }

    /**
     * Lets a widget's setup screen open. Android opens it on Feather's behalf, and since Android 14
     * it only lends Feather's right to open screens (it's in front) when Feather says so. Without
     * this the setup screen is quietly never opened, unless a system window happens to be showing,
     * as the "allow widget" dialog is the first time an app's widget is added.
     */
    @Suppress("DEPRECATION") // ALLOWED, the only choice on Android 14 and 15.
    private fun setupOptions(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    /** Smallest size, in cells, the widget allows when resized. */
    fun minSpan(appWidgetId: Int): Pair<Int, Int> {
        val info = info(appWidgetId) ?: return 1 to 1
        val w = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val h = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return cells(w / density, true) to cells(h / density, false)
    }

    /** The cells a widget needs to be [dp] wide or tall: its card spans one icon, plus a cell for each more. */
    private fun cells(dp: Float, horizontal: Boolean): Int {
        val grid = grid()
        val size = if (horizontal) grid.cellWidth else grid.cellHeight
        // Less the 8dp Android pads widgets with, which they don't get here.
        val need = (dp - 8) * density
        return (ceil((need - grid.icon) / size).toInt() + 1).coerceIn(1, if (horizontal) Workspace.COLS else Workspace.ROWS)
    }

    /** A page cell's height in dp, for the rows Feather's widgets take ([FeatherKind.height]). */
    private val cellHeightDp get() = grid().cellHeight / density

    private fun spanX(info: AppWidgetProviderInfo) = targetCells(info).first.let { target ->
        if (target > 0) target.coerceAtMost(Workspace.COLS) else cells(info.minWidth / density, true)
    }

    private fun spanY(info: AppWidgetProviderInfo) = targetCells(info).second.let { target ->
        if (target > 0) target.coerceAtMost(Workspace.ROWS) else cells(info.minHeight / density, false)
    }

    /**
     * The size in cells the widget asks to start at, or 0 when it doesn't say. Android 11 doesn't
     * read that from the widget's file (12 added it), so there the file is read here.
     */
    private fun targetCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) return info.targetCellWidth to info.targetCellHeight
        // Read once per widget (the picker's list reads them in the background first), not each time its row shows.
        return targets.getOrPut(info.provider) { readTargetCells(info) }
    }

    private val targets = java.util.concurrent.ConcurrentHashMap<android.content.ComponentName, Pair<Int, Int>>()

    private fun readTargetCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
        return runCatching {
            val pm = activity.packageManager
            val receiver = pm.getReceiverInfo(info.provider, android.content.pm.PackageManager.GET_META_DATA)
            val xml = receiver.loadXmlMetaData(pm, AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) ?: return 0 to 0
            try {
                while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG || xml.name != "appwidget-provider") continue
                    val android = "http://schemas.android.com/apk/res/android"
                    return xml.getAttributeIntValue(android, "targetCellWidth", 0) to xml.getAttributeIntValue(android, "targetCellHeight", 0)
                }
                0 to 0
            } finally {
                xml.close()
            }
        }.getOrDefault(0 to 0)
    }

    // region Picker

    /**
     * Opens the widget picker: a search field, then Feather's own widgets, then widgets grouped
     * by app, each with its preview. Searching matches a widget's name and its app's.
     */
    fun pick() {
        val p = palette()
        val all = ArrayList<PickerRow>()
        all += PickerRow.Header(null, "Feather Launcher")
        FeatherKind.entries.forEach { all += PickerRow.Feather(it) }
        val adapter = PickerAdapter()

        val list = ListView(activity).apply {
            divider = null
            // The keypad highlight: the same ring as everywhere else, just outside the card
            // (rows are inset SPACE_S at the sides, SPACE_XS above and below). No touch highlight.
            selector = android.graphics.drawable.InsetDrawable(Ui.focusRing(activity, Ui.RADIUS_L + Ui.SPACE_XS),
                dp(Ui.SPACE_S - Ui.SPACE_XS), 0, dp(Ui.SPACE_S - Ui.SPACE_XS), 0)
            setDrawSelectorOnTop(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(Ui.SPACE_S))
        }
        list.adapter = adapter
        val nothing = TextView(activity).apply {
            style(TextStyle.Secondary, p.textSecondary)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_XL))
            visibility = View.GONE
        }
        val search = android.widget.EditText(activity).apply {
            hint = "Search widgets"
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            style(TextStyle.Body, p.text)
            setHintTextColor(p.textTertiary)
            Ui.cursor(this, p.text)
            background = Ui.rounded(activity, p.surfaceHigh, Ui.RADIUS_M)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
        }
        fun show() {
            val query = search.text.toString().trim().lowercase()
            adapter.rows = if (query.isEmpty()) all.toList() else filter(all, query)
            adapter.notifyDataSetChanged()
            nothing.text = "No widgets match \"${search.text.toString().trim()}\""
            nothing.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = show()
        })
        show()

        list.setOnKeyListener { _, code, event -> stepPicker(list, adapter.rows, code, event) }
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = adapter.rows[position]) {
                is PickerRow.Widget -> {
                    overlays.dismiss()
                    add(row.info, null)
                }
                is PickerRow.Feather -> {
                    overlays.dismiss()
                    onFeatherPicked?.invoke(row.kind)
                }
                else -> Unit
            }
        }
        // Held, a widget can be dragged onto a page, ours or another app's.
        list.setOnItemLongClickListener { _, view, position, _ ->
            val preview = (view.getTag(R.id.widget_preview) as? View)?.takeIf { it.width > 0 } ?: return@setOnItemLongClickListener false
            when (val row = adapter.rows[position]) {
                is PickerRow.Feather -> onFeatherHeld?.invoke(row.kind, preview)
                is PickerRow.Widget -> onWidgetHeld?.invoke(row.info, preview, spanX(row.info), spanY(row.info))
                else -> return@setOnItemLongClickListener false
            }
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
            })
            addView(nothing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        overlays.showSheet("Widgets", content, scrollable = list, fill = true)
        // Other apps' widgets are listed once their names are read, which can take a moment
        // with many apps; the sheet opens straight away with Feather's own.
        previews.execute {
            val others = otherApps()
            main.post {
                all += others
                show()
            }
        }
    }

    /**
     * The rows matching [query]: each widget whose name or app's name has it, under its app's
     * name as before; an app's name matching keeps all its widgets.
     */
    private fun filter(rows: List<PickerRow>, query: String): List<PickerRow> {
        val out = ArrayList<PickerRow>()
        var header: PickerRow.Header? = null
        var headerShown = false
        for (row in rows) {
            val name = when (row) {
                is PickerRow.Header -> { header = row; headerShown = false; continue }
                is PickerRow.Widget -> row.name
                is PickerRow.Feather -> row.kind.label
            }
            val app = header?.app.orEmpty()
            if (query !in name.lowercase() && query !in app.lowercase()) continue
            if (!headerShown) header?.let { out += it }
            headerShown = true
            out += row
        }
        return out
    }

    /**
     * Up and down in the picker with the keypad: from widget to widget, never onto an app's
     * name, each brought whole into view. Left to itself, the list scrolls a tall row part of
     * the way first with nothing selected, and OK then does nothing.
     */
    private fun stepPicker(list: ListView, rows: List<PickerRow>, code: Int, event: KeyEvent): Boolean {
        val step = when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -1
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val from = list.selectedItemPosition
        var next = if (from == ListView.INVALID_POSITION) list.firstVisiblePosition else from + step
        while (next in rows.indices && rows[next] is PickerRow.Header) next += step
        if (next !in rows.indices) return true
        val view = list.getChildAt(next - list.firstVisiblePosition)
        val bottom = list.height - list.paddingBottom
        val header = list.getChildAt(next - 1 - list.firstVisiblePosition)?.takeIf { rows[next - 1] is PickerRow.Header }
        val top = when {
            // Whole on screen already: only the highlight moves.
            view != null && view.top >= 0 && view.bottom <= bottom -> view.top
            // Coming from above: its bottom at the bottom of the list.
            view != null && step > 0 -> (bottom - view.height).coerceAtLeast(0)
            // Coming from below: at the top, under its app's name if it's the app's first.
            else -> (header?.height ?: 0).coerceAtMost(bottom - (view?.height ?: 0)).coerceAtLeast(0)
        }
        list.setSelectionFromTop(next, top)
        return true
    }

    /** Every other app's widgets, under the app's name, apps in alphabetical order. */
    private fun otherApps(): List<PickerRow> {
        val pm = activity.packageManager
        val collator = Collator.getInstance()
        val rows = ArrayList<PickerRow>()
        manager.installedProviders
            .groupBy { it.provider.packageName }
            .map { (pkg, infos) ->
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Triple(pkg, app, infos)
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
            .forEach { (pkg, app, infos) ->
                rows += PickerRow.Header(pkg, app)
                infos.forEach {
                    targetCells(it)
                    rows += PickerRow.Widget(it, it.loadLabel(pm))
                }
            }
        return rows
    }

    private sealed class PickerRow {
        class Header(val packageName: String?, val app: String) : PickerRow()
        class Widget(val info: AppWidgetProviderInfo, val name: String) : PickerRow()
        class Feather(val kind: FeatherKind) : PickerRow()
    }

    private inner class PickerAdapter : BaseAdapter() {
        var rows: List<PickerRow> = emptyList()
        /**
         * Previews read so far, as many as fit in [PREVIEWS] bytes; one scrolled far away is read
         * again if it comes back. [none] has the widgets without a picture.
         */
        private val loaded = object : android.util.LruCache<AppWidgetProviderInfo, Drawable>(PREVIEWS) {
            override fun sizeOf(key: AppWidgetProviderInfo, value: Drawable) =
                (value as? android.graphics.drawable.BitmapDrawable)?.bitmap?.allocationByteCount ?: 1024
        }
        private val none = HashSet<AppWidgetProviderInfo>()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is PickerRow.Header -> 0
            is PickerRow.Widget -> 1
            is PickerRow.Feather -> 2
        }
        // Without this, the list ignores isEnabled and the keypad lands on the apps' names too.
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] !is PickerRow.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = palette()
            return when (val row = rows[position]) {
                is PickerRow.Header -> {
                    val view = convertView as? LinearLayout ?: LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_L), dp(Ui.SPACE_XL), dp(Ui.SPACE_S))
                        addView(ImageView(activity), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
                        addView(TextView(activity).apply { style(TextStyle.Label, p.text) })
                    }
                    // Built-ins are listed under the launcher's own icon, in the icon shape like every other app's.
                    (view.getChildAt(0) as ImageView).apply {
                        val icon = appIcon(row.packageName ?: activity.packageName)
                        if (icon != null) setImageBitmap(icon) else setImageResource(R.mipmap.ic_launcher)
                    }
                    (view.getChildAt(1) as TextView).text = row.app
                    view
                }
                is PickerRow.Feather -> featherCards.getOrPut(row.kind) { featherCard(row.kind, p) }
                is PickerRow.Widget -> {
                    val view = convertView as? FrameLayout ?: widgetCard(p)
                    val parts = cardParts(view)
                    parts.name.text = row.name
                    parts.size.text = "${spanX(row.info)} × ${spanY(row.info)}"
                    val preview = parts.preview
                    preview.tag = row.info
                    preview.cells = spanX(row.info) to spanY(row.info)
                    view.setTag(R.id.widget_preview, preview)
                    val known = loaded.get(row.info)
                    if (known != null || row.info in none) {
                        preview.setImageDrawable(known)
                    } else {
                        preview.setImageDrawable(null)
                        val info = row.info
                        val box = parent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
                        previews.execute {
                            val image = (runCatching { info.loadPreviewImage(activity, 0) }.getOrNull()
                                ?: runCatching { info.loadIcon(activity, 0) }.getOrNull())?.let { fit(it, box, dp(132)) }
                            main.post {
                                if (image != null) loaded.put(info, image) else none += info
                                if (preview.tag === info) preview.setImageDrawable(image)
                            }
                        }
                    }
                    view
                }
            }
        }

        /**
         * [image] with no more pixels than it can show in a [w] by [h] box: a preview is often
         * drawn far larger than that. It keeps its size (only its density changes), so it lays
         * out and looks just the same.
         */
        private fun fit(image: Drawable, w: Int, h: Int): Drawable {
            val bitmap = (image as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return image
            val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
            if (scale >= 1f) return image
            val smaller = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            val width = image.intrinsicWidth
            val height = image.intrinsicHeight
            return object : android.graphics.drawable.BitmapDrawable(activity.resources, smaller) {
                override fun getIntrinsicWidth() = width
                override fun getIntrinsicHeight() = height
            }
        }

        private inner class CardParts(val preview: Rounded, val name: TextView, val size: TextView)

        private fun cardParts(view: FrameLayout): CardParts {
            val card = view.getChildAt(0) as LinearLayout
            val top = card.getChildAt(0) as FrameLayout
            val bottom = card.getChildAt(1) as LinearLayout
            return CardParts(top.getChildAt(0) as Rounded, bottom.getChildAt(0) as TextView, bottom.getChildAt(1) as TextView)
        }

        /** Feather's widgets, live, made once each while the list is open. */
        private val featherCards = HashMap<FeatherKind, View>()

        /** One of Feather's widgets as it will look: the real widget, live, with what it's for. */
        private fun featherCard(kind: FeatherKind, p: Palette): View {
            val preview = object : FrameLayout(activity) {
                // A picture of the widget, not the widget itself: a tap picks it from the list.
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = true
            }.apply {
                // Nothing in it takes the keypad highlight either; the list skips holding a row that has such things.
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // The size it takes on the home screen, so it shows just what it will there.
                val (w, h) = grid().widgetSize(kind.w, kind.height(cellHeightDp))
                addView(sample(kind), FrameLayout.LayoutParams(
                    if (kind.w < Workspace.COLS) w else ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.CENTER))
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(activity, p.surfaceHigh, Ui.RADIUS_L)
                foreground = Ui.pressable(activity, Ui.RADIUS_L)
                setPadding(dp(Ui.SPACE_M), dp(Ui.SPACE_M), dp(Ui.SPACE_M), dp(Ui.SPACE_M))
                addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(Ui.SPACE_XS), 0, dp(Ui.SPACE_XS), 0)
                    addView(TextView(activity).apply { style(TextStyle.Body, p.text); text = kind.label; maxLines = 1 },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(activity).apply { style(TextStyle.Caption, p.textTertiary); text = "${kind.w} × ${kind.height(cellHeightDp)}" })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Ui.SPACE_M)
                })
                addView(TextView(activity).apply {
                    style(TextStyle.Secondary, p.textSecondary)
                    text = kind.about
                    setPadding(dp(Ui.SPACE_XS), dp(2), dp(Ui.SPACE_XS), 0)
                })
            }
            return FrameLayout(activity).apply {
                setPadding(dp(Ui.SPACE_S), dp(Ui.SPACE_XS), dp(Ui.SPACE_S), dp(Ui.SPACE_XS))
                addView(card)
                // What a drag picks up: the widget as it looks.
                setTag(R.id.widget_preview, preview.getChildAt(0))
            }
        }

        /** The widget itself. */
        private fun sample(kind: FeatherKind): View {
            val item = HomeItem(PREVIEW_ID - kind.ordinal, 0, 0, 0, kind.w, kind.height(cellHeightDp), HomeItem.Type.CLOCK, kind.ref)
            return CardWidget.create(activity, item)
                ?: if (kind == FeatherKind.ANALOG) AnalogClockWidget(activity, item).apply { setSolid(true) }
                else ClockWidget(activity, item).apply { setSolid(true) }
        }

        private fun widgetCard(p: Palette): FrameLayout {
            val top = FrameLayout(activity).apply {
                addView(Rounded().apply {
                    adjustViewBounds = true
                    maxHeight = dp(132)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    minimumHeight = dp(56)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(activity, p.surfaceHigh, Ui.RADIUS_L)
                foreground = Ui.pressable(activity, Ui.RADIUS_L)
                setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_M))
                addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(activity).apply { style(TextStyle.Body, p.text); maxLines = 1 },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(activity).apply { style(TextStyle.Caption, p.textTertiary) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Ui.SPACE_M)
                })
            }
            // Cards inset so their content lines up with the sheet's title and the app names.
            return FrameLayout(activity).apply {
                setPadding(dp(Ui.SPACE_S), dp(Ui.SPACE_XS), dp(Ui.SPACE_S), dp(Ui.SPACE_XS))
                addView(card)
            }
        }
    }

    /**
     * Another app's widget preview, its corners rounded as the widget's will be on the home
     * screen, in proportion to how much smaller it's drawn here.
     */
    private inner class Rounded : ImageView(activity) {
        /** The cells the widget takes, for its size on the home screen. */
        var cells = 1 to 1
        private val path = android.graphics.Path()
        private val drawn = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val image = drawable ?: return super.onDraw(canvas)
            drawn.set(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
            imageMatrix.mapRect(drawn)
            drawn.offset(paddingLeft.toFloat(), paddingTop.toFloat())
            val full = grid().widgetSize(cells.first, cells.second).first - 2 * dp(CardWidget.GAP)
            val radius = dp(WidgetStyle.radius) * (drawn.width() / full.coerceAtLeast(1)).coerceAtMost(1f)
            path.reset()
            path.addRoundRect(drawn, radius, radius, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            super.onDraw(canvas)
            canvas.restore()
        }
    }

    // endregion

    // region Binding

    /** Adds another app's widget: bound, set up if it needs it, then placed ([at] where it was dropped). */
    fun add(info: AppWidgetProviderInfo, at: Triple<Int, Int, Int>?) {
        pendingSpot = at
        bind(info)
    }


    private fun bind(info: AppWidgetProviderInfo) {
        pendingId = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, null)) {
            configure(info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun configure(info: AppWidgetProviderInfo?) {
        if (info == null) return cancelPending()
        // Android 11 passes this flag along from the widget's file too, though it came in 12.
        @android.annotation.SuppressLint("InlinedApi")
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) return finishAdd()
        try {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQUEST_CONFIGURE, setupOptions())
        } catch (e: Exception) {
            cancelPending()
            Toast.makeText(activity, "This widget can't be set up", Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns true if the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_BIND ->
                if (resultCode == Activity.RESULT_OK) configure(manager.getAppWidgetInfo(pendingId)) else cancelPending()
            REQUEST_CONFIGURE ->
                if (resultCode == Activity.RESULT_OK) finishAdd() else cancelPending()
            REQUEST_RECONFIGURE -> Unit
            else -> return false
        }
        return true
    }

    private fun finishAdd() {
        val info = manager.getAppWidgetInfo(pendingId) ?: return cancelPending()
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        onAdded?.invoke(id, spanX(info), spanY(info), pendingSpot)
        pendingSpot = null
    }

    private fun cancelPending() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingSpot = null
    }

    // endregion

    private fun dp(value: Int) = Ui.dp(activity, value)

    companion object {
        /** How much memory the picker's previews may take, in bytes. */
        private const val PREVIEWS = 8 * 1024 * 1024
        private const val HOST_ID = 1024
        /** Ids for the picker's live previews, well clear of the home screen's. */
        private const val PREVIEW_ID = -1000L
        const val REQUEST_BIND = 11
        const val REQUEST_CONFIGURE = 12
        const val REQUEST_RECONFIGURE = 13
    }
}
