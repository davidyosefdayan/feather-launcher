package app.featherlauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
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
    /** Size of one home grid cell in dp, for working out how many cells a widget needs. */
    private val cellSizeDp: () -> Pair<Float, Float>,
) {
    /** A widget was bound (and set up, if it needed it) and should be placed on the home screen. */
    var onAdded: ((appWidgetId: Int, spanX: Int, spanY: Int) -> Unit)? = null
    /** One of Feather's own widgets was picked. */
    var onFeatherPicked: ((FeatherKind) -> Unit)? = null
    /** One of Feather's own widgets was held, to be dragged onto a page; the view is its preview. */
    var onFeatherHeld: ((FeatherKind, View) -> Unit)? = null

    private val manager = AppWidgetManager.getInstance(activity)
    private val density = activity.resources.displayMetrics.density
    private val host = object : AppWidgetHost(activity.applicationContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            WidgetView(context)
    }
    private val previews = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

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
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, REQUEST_RECONFIGURE, null) }
    }

    /** Smallest size, in cells, the widget allows when resized. */
    fun minSpan(appWidgetId: Int): Pair<Int, Int> {
        val info = info(appWidgetId) ?: return 1 to 1
        val w = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val h = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return cells(w / density, true) to cells(h / density, false)
    }

    private fun cells(dp: Float, horizontal: Boolean): Int {
        val (cw, ch) = cellSizeDp()
        val size = if (horizontal) cw else ch
        return ceil((dp - 8) / size).toInt().coerceIn(1, if (horizontal) Workspace.COLS else Workspace.ROWS)
    }

    private fun spanX(info: AppWidgetProviderInfo) =
        if (info.targetCellWidth > 0) info.targetCellWidth.coerceAtMost(Workspace.COLS) else cells(info.minWidth / density, true)

    private fun spanY(info: AppWidgetProviderInfo) =
        if (info.targetCellHeight > 0) info.targetCellHeight.coerceAtMost(Workspace.ROWS) else cells(info.minHeight / density, false)

    // region Picker

    /** Opens the widget picker: Feather's own widgets first, then widgets grouped by app, each with its preview. */
    fun pick() {
        val rows = ArrayList<PickerRow>()
        rows += PickerRow.Header(null, "Feather Launcher")
        FeatherKind.entries.forEach { rows += PickerRow.Feather(it) }

        val list = ListView(activity).apply {
            divider = null
            selector = ColorDrawable(0)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(Ui.SPACE_S))
        }
        val adapter = PickerAdapter(rows)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = rows[position]) {
                is PickerRow.Widget -> {
                    overlays.dismiss()
                    bind(row.info)
                }
                is PickerRow.Feather -> {
                    overlays.dismiss()
                    onFeatherPicked?.invoke(row.kind)
                }
                else -> Unit
            }
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            val row = rows[position] as? PickerRow.Feather ?: return@setOnItemLongClickListener false
            val preview = (view.getTag(R.id.widget_preview) as? View) ?: return@setOnItemLongClickListener false
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onFeatherHeld?.invoke(row.kind, preview)
            true
        }
        overlays.showSheet("Widgets", list, scrollable = list, fill = true)
        // Other apps' widgets are listed once their names are read, which can take a moment
        // with many apps; the sheet opens straight away with Feather's own.
        previews.execute {
            val others = otherApps()
            main.post {
                rows += others
                adapter.notifyDataSetChanged()
            }
        }
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
                infos.forEach { rows += PickerRow.Widget(it, it.loadLabel(pm)) }
            }
        return rows
    }

    private sealed class PickerRow {
        class Header(val packageName: String?, val app: String) : PickerRow()
        class Widget(val info: AppWidgetProviderInfo, val name: String) : PickerRow()
        class Feather(val kind: FeatherKind) : PickerRow()
    }

    private inner class PickerAdapter(private val rows: List<PickerRow>) : BaseAdapter() {
        private val loaded = HashMap<AppWidgetProviderInfo, Drawable?>()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is PickerRow.Header -> 0
            is PickerRow.Widget -> 1
            is PickerRow.Feather -> 2
        }
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
                    // Built-ins are listed under the launcher's own icon so every header lines up.
                    (view.getChildAt(0) as ImageView).apply {
                        if (row.packageName == null) setImageResource(R.mipmap.ic_launcher)
                        else setImageBitmap(appIcon(row.packageName))
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
                    if (loaded.containsKey(row.info)) {
                        preview.setImageDrawable(loaded[row.info])
                    } else {
                        preview.setImageDrawable(null)
                        val info = row.info
                        previews.execute {
                            val image = runCatching { info.loadPreviewImage(activity, 0) }.getOrNull()
                                ?: runCatching { info.loadIcon(activity, 0) }.getOrNull()
                            main.post {
                                loaded[info] = image
                                if (preview.tag === info) preview.setImageDrawable(image)
                            }
                        }
                    }
                    view
                }
            }
        }

        private inner class CardParts(val preview: ImageView, val name: TextView, val size: TextView)

        private fun cardParts(view: FrameLayout): CardParts {
            val card = view.getChildAt(0) as LinearLayout
            val top = card.getChildAt(0) as FrameLayout
            val bottom = card.getChildAt(1) as LinearLayout
            return CardParts(top.getChildAt(0) as ImageView, bottom.getChildAt(0) as TextView, bottom.getChildAt(1) as TextView)
        }

        /** Feather's widgets, live, made once each while the list is open. */
        private val featherCards = HashMap<FeatherKind, View>()

        /**
         * One of Feather's widgets as it will look: the real widget, live, on the wallpaper's own
         * tone (the clock's white type needs something dark under it), with what it's for.
         */
        private fun featherCard(kind: FeatherKind, p: Palette): View {
            val preview = object : FrameLayout(activity) {
                // A picture of the widget, not the widget itself: a tap picks it from the list.
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = true
            }.apply {
                // Nothing in it takes the keypad highlight either; the list skips holding a row that has such things.
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                background = Ui.rounded(activity, p.drawer, Ui.RADIUS_M)
                clipToOutline = true
                setPadding(dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S))
                // The size it takes on the home screen, so it shows just what it will there.
                val (cellW, cellH) = cellSizeDp()
                addView(sample(kind), FrameLayout.LayoutParams(
                    if (kind.w < Workspace.COLS) dp((cellW * kind.w).toInt()) else ViewGroup.LayoutParams.MATCH_PARENT,
                    dp((cellH * kind.height(cellH)).toInt()), Gravity.CENTER))
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
                    addView(TextView(activity).apply { style(TextStyle.Caption, p.textTertiary); text = "${kind.w} × ${kind.height(cellSizeDp().second)}" })
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
            val item = HomeItem(PREVIEW_ID - kind.ordinal, 0, 0, 0, kind.w, kind.height(cellSizeDp().second), HomeItem.Type.CLOCK, kind.ref)
            return CardWidget.create(activity, item) ?: ClockWidget(activity, item).apply {
                apply(ClockOptions(ClockOptions.Size.MEDIUM, alignStart = false, showDate = true))
            }
        }

        private fun widgetCard(p: Palette): FrameLayout {
            val top = FrameLayout(activity).apply {
                addView(ImageView(activity).apply {
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

    // endregion

    // region Binding

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
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) return finishAdd()
        try {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQUEST_CONFIGURE, null)
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
        onAdded?.invoke(id, spanX(info), spanY(info))
    }

    private fun cancelPending() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    // endregion

    private fun dp(value: Int) = Ui.dp(activity, value)

    companion object {
        private const val HOST_ID = 1024
        /** Ids for the picker's live previews, well clear of the home screen's. */
        private const val PREVIEW_ID = -1000L
        const val REQUEST_BIND = 11
        const val REQUEST_CONFIGURE = 12
        const val REQUEST_RECONFIGURE = 13
    }
}
