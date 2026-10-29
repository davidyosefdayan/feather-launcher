package app.featherlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Telephony
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The home screen: pages of apps and widgets plus the dock, all on grids,
 * with long-press-and-drag to rearrange and handles to resize widgets.
 */
class HomeScreen(
    private val activity: Activity,
    private val root: FrameLayout,
    val pages: PagedView,
    private val dots: PageDots,
    private val dock: CellLayout,
    private val repo: AppRepository,
    private val prefs: Prefs,
    private val widgets: Widgets,
    private val overlays: Overlays,
    private val palette: () -> Palette,
    private val host: Host,
) {
    /** What the home screen needs from the activity around it. */
    interface Host {
        fun launch(entry: AppEntry, from: View?)
        fun startSafely(intent: Intent)
        fun showAppInfo(entry: AppEntry)
        fun closeDrawer(animate: Boolean)
        /** An app dragged from the app list is taken back there, still being dragged. */
        fun returnToDrawer(entry: AppEntry, x: Float, y: Float)
        /** Cancels the touch gesture currently going to the views, once a drag takes it over. */
        fun cancelViewTouches()
    }

    val workspace = Workspace(activity)
    private val shortcuts = Shortcuts(activity)
    private val views = HashMap<Long, View>()
    private val drag = Drag()
    private var resize: ResizeFrame? = null

    init {
        dock.cols = Workspace.DOCK_COLS
        dock.rows = 1
        pages.onPageChanged = {
            dots.current = it
            // The widget being resized stays on its page; the frame around it goes too.
            finishResize()
            inside = null
        }
        if (workspace.isNew) setUpDefaults()
        bindAll()
    }

    // region Layout

    private fun setUpDefaults() {
        workspace.add(0, 0, 0, Workspace.COLS, 2, HomeItem.Type.CLOCK, "")
        // Carry over the dock and widgets from before the home screen had a grid.
        prefs.dock.forEachIndexed { slot, key ->
            if (key != null) workspace.add(Workspace.DOCK, slot, 0, 1, 1, HomeItem.Type.APP, key)
        }
        prefs.widgets.forEach { spec ->
            val (x, y) = workspace.findSpot(0, Workspace.COLS, 2) ?: return@forEach
            workspace.add(0, x, y, Workspace.COLS, 2, HomeItem.Type.WIDGET, spec.id.toString())
        }
        workspace.save()
    }

    /** On a fresh install the dock starts with phone, messages, email and camera. */
    private fun fillDefaultDock() {
        if (workspace.on(Workspace.DOCK).isNotEmpty() || repo.apps.isEmpty()) return
        val pm = activity.packageManager
        fun packageFor(intent: Intent) =
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        listOf(
            packageFor(Intent(Intent.ACTION_DIAL)),
            Telephony.Sms.getDefaultSmsPackage(activity),
            packageFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)),
            packageFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        ).forEachIndexed { slot, pkg ->
            val entry = repo.apps.firstOrNull { it.packageName == pkg } ?: return@forEachIndexed
            workspace.add(Workspace.DOCK, slot, 0, 1, 1, HomeItem.Type.APP, entry.key)
        }
        workspace.save()
    }

    fun onAppsChanged() {
        fillDefaultDock()
        // Drop apps that were uninstalled (only once the app list is known).
        if (repo.apps.isNotEmpty()) {
            workspace.items.filter { it.type == HomeItem.Type.FOLDER }.forEach { folder ->
                folder.apps.filter { repo.find(it) == null }.forEach { removeFromFolder(folder, it) }
            }
            val gone = workspace.items.filter { it.type == HomeItem.Type.APP && repo.find(it.ref) == null }
            if (gone.isNotEmpty()) {
                workspace.items.removeAll(gone)
                gone.forEach { views.remove(it.id)?.let { v -> (v.parent as? ViewGroup)?.removeView(v) } }
                workspace.removeEmptyPages()
                workspace.save()
            }
        }
        bindAll()
    }

    /** Puts every item's view in its cell, creating views as needed and reusing the rest. */
    fun bindAll() {
        while (pages.pageCount < workspace.pageCount) pages.addView(newPage())
        while (pages.pageCount > workspace.pageCount) pages.removeViewAt(pages.pageCount - 1)
        dots.count = workspace.pageCount
        if (pages.currentPage >= workspace.pageCount) pages.snapTo(workspace.pageCount - 1, animate = false)
        dots.current = pages.currentPage

        val alive = workspace.items.map { it.id }.toSet()
        views.keys.filter { it !in alive }.forEach { id -> views.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) } }

        val limits = AppLimits.get(activity)
        val newApps = NewApps.get(activity)
        val now = System.currentTimeMillis()
        for (item in workspace.items) {
            val view = views[item.id] ?: createView(item)?.also { views[item.id] = it } ?: continue
            (view as? ClockWidget)?.refresh()
            (view as? CardWidget)?.refresh()
            if (view is AppIconView) {
                val showLabel = !item.inDock && prefs.showLabels
                if (item.type == HomeItem.Type.FOLDER) {
                    // As on iOS, a folder's badge adds up its apps' (locked ones don't count).
                    val badge = item.apps.mapNotNull(repo::find).distinctBy { it.packageName }
                        .filter { !limits.isLocked(it.packageName, now) }.sumOf { prefs.badgeCount(it.packageName) }
                    view.bind(folderIcon(item), item.label ?: FOLDER_NAME, showLabel, badge = badge,
                        iconDp = if (item.inDock) AppIconView.DOCK_ICON else AppIconView.ICON)
                } else {
                    val entry = repo.find(item.ref)
                    view.bind(entry?.let(repo::icon), item.label ?: entry?.label.orEmpty(), showLabel,
                        locked = entry != null && limits.isLocked(entry.packageName, now),
                        isNew = entry != null && newApps.isNew(entry),
                        badge = entry?.let { prefs.badgeCount(it.packageName) } ?: 0,
                        iconDp = if (item.inDock) AppIconView.DOCK_ICON else AppIconView.ICON)
                }
            }
            val target = containerFor(item.page)
            val lp = view.layoutParams as? CellLayout.LayoutParams
            if (view.parent !== target || lp == null || lp.x != item.x || lp.y != item.y || lp.w != item.w || lp.h != item.h) {
                target.place(view, item.x, item.y, item.w, item.h)
            }
        }
        val offset = if (prefs.showLabels) dp(AppIconView.LABEL_HEIGHT) / 2f else 0f
        for (i in 0 until pages.pageCount) pages.page(i).iconOffset = offset
        pages.updateFocusability()
    }

    private fun newPage() = CellLayout(activity).apply {
        cols = Workspace.COLS
        rows = Workspace.ROWS
        // The cells fill the page's outline while dragging: in line with the dock at the sides.
        setPadding(dp(Ui.BAR_MARGIN), dp(Workspace.PAGE_TOP), dp(Ui.BAR_MARGIN), 0)
    }

    private fun containerFor(page: Int): CellLayout = if (page == Workspace.DOCK) dock else pages.page(page)

    private fun createView(item: HomeItem): View? = when (item.type) {
        HomeItem.Type.APP -> AppIconView(activity, item).apply {
            setOnClickListener { v -> repo.find(item.ref)?.let { host.launch(it, (v as AppIconView).iconView) } }
            setOnLongClickListener { onItemLongPress(item, it); true }
        }
        HomeItem.Type.FOLDER -> AppIconView(activity, item).apply {
            setOnClickListener { openFolder(item) }
            setOnLongClickListener { onItemLongPress(item, it); true }
        }
        // Feather's widgets on cards; the clock and At a glance below.
        HomeItem.Type.CLOCK -> CardWidget.create(activity, item)?.apply {
            when (this) {
                is AgendaWidget -> {
                    onEvent = { e -> host.startSafely(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))) }
                    onCalendar = {
                        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        ContentUris.appendId(uri, System.currentTimeMillis())
                        host.startSafely(Intent(Intent.ACTION_VIEW, uri.build()))
                    }
                    onAllow = {
                        activity.startActivity(Intent(activity, SettingsActivity::class.java)
                            .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.Page.CALENDAR.name))
                    }
                }
                is ZmanimWidget -> onOpen = { place -> ZmanimSheet.show(activity, palette(), overlays, place) }
            }
            setOnLongClickListener { onItemLongPress(item, it); true }
        } ?: ClockWidget(activity, item).apply {
            apply(prefs.clockOptions(item.id))
            foreground = Ui.focusRing(activity, Ui.RADIUS_L, 0xFFFFFFFF.toInt())
            setOnClickListener { host.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            dateView.setOnClickListener {
                val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                ContentUris.appendId(uri, System.currentTimeMillis())
                host.startSafely(Intent(Intent.ACTION_VIEW, uri.build()))
            }
            onTap = { glanceItem, place ->
                when (glanceItem.tap) {
                    Glance.Tap.ZMANIM -> place?.let { ZmanimSheet.show(activity, palette(), overlays, it) }
                    Glance.Tap.EVENT -> glanceItem.event?.let { e ->
                        host.startSafely(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)))
                    }
                    Glance.Tap.ALARMS -> host.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                    Glance.Tap.CALLS -> host.startSafely(Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE))
                    // The phone's messages app.
                    Glance.Tap.MESSAGES -> android.provider.Telephony.Sms.getDefaultSmsPackage(activity)
                        ?.let { activity.packageManager.getLaunchIntentForPackage(it) }?.let(host::startSafely)
                }
            }
            setOnLongClickListener { onItemLongPress(item, it); true }
            dateView.setOnLongClickListener { onItemLongPress(item, this); true }
        }
        HomeItem.Type.WIDGET -> widgets.createView(item.ref.toInt())?.apply {
            // Reachable with the keypad like everything else, with the same ring when it is.
            isFocusable = true
            foreground = Ui.focusRing(activity, Ui.RADIUS_L, 0xFFFFFFFF.toInt())
            onLongPress = { onItemLongPress(item, this) }
            // Holding OK on it with the keypad (a touch hold is caught above).
            setOnLongClickListener { if (it.isInTouchMode) false else { onItemLongPress(item, it); true } }
            // OK on it with the keypad opens its app: a widget's own buttons can't be reached with
            // the keypad. (A tap goes to the widget's own buttons as usual.)
            setOnClickListener {
                if (it.isInTouchMode) return@setOnClickListener
                val pkg = widgets.info(item.ref.toInt())?.provider?.packageName ?: return@setOnClickListener
                repo.apps.firstOrNull { app -> app.packageName == pkg }?.let { app -> host.launch(app, it) }
                    ?: activity.packageManager.getLaunchIntentForPackage(pkg)?.let(host::startSafely)
            }
        }
    }

    fun applyTheme() {
        val p = palette()
        // Dock corner = highlight corner + the space around it, so the curves run parallel.
        // Exactly DOCK_GAP from the edges to the icons, left, right, top and bottom.
        // Icons sit in boxes INSET larger than themselves (room for the keypad highlight).
        dock.edgeGap = dp(DOCK_GAP)
        dock.iconSize = dp(AppIconView.DOCK_ICON)
        dock.boxDp = AppIconView.DOCK_ICON + 2 * AppIconView.INSET
        val vertical = dp(DOCK_GAP - AppIconView.INSET)
        dock.setPadding(0, vertical, 0, vertical)
        // Like Smart Launcher: on the wallpaper the dock is the app list's deep tone; the lighter
        // tone is for bars that sit on that deep background (the app list's tab bar).
        dock.background = Ui.rounded(activity, p.dock, Ui.RADIUS_BAR, p.hairline)
        // Feather's widgets take the wallpaper's colors too, so they're made again in the new ones.
        WidgetStyle.palette = palette
        WidgetStyle.context = activity.applicationContext
        views.entries.filter { it.value is CardWidget || it.value is ClockWidget }.map { it.key }.forEach { id ->
            views.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        bindAll()
    }

    /** Size of one page cell in dp, for sizing new widgets. */
    fun cellSizeDp(): Pair<Float, Float> {
        val density = activity.resources.displayMetrics.density
        val page = if (pages.pageCount > 0) pages.page(0) else null
        if (page == null || page.width == 0) return 100f to 84f
        return page.cellWidth / density to page.cellHeight / density
    }

    // endregion

    // region Adding and removing

    /** Puts an app on the first free spot, starting at the visible page, adding a page if all are full. */
    fun addApp(entry: AppEntry) = addItem(1, 1, HomeItem.Type.APP, entry.key)

    fun addWidget(appWidgetId: Int, spanX: Int, spanY: Int) = addItem(spanX, spanY, HomeItem.Type.WIDGET, appWidgetId.toString())

    /** The clock, or At a glance: the same lines without the time, in a shorter space. */
    fun addFeather(kind: FeatherKind) = addItem(kind.w, kind.height(cellSizeDp().second), HomeItem.Type.CLOCK, kind.ref)

    private fun addItem(w: Int, h: Int, type: HomeItem.Type, ref: String) {
        val order = listOf(pages.currentPage) + (0 until workspace.pageCount).filter { it != pages.currentPage }
        var placed: HomeItem? = null
        for (page in order) {
            val spot = workspace.findSpot(page, w, h) ?: continue
            placed = workspace.add(page, spot.first, spot.second, w, h, type, ref)
            break
        }
        if (placed == null) {
            val page = workspace.addPage()
            placed = workspace.add(page, 0, 0, w, h, type, ref)
        }
        workspace.save()
        bindAll()
        pages.snapTo(placed.page)
        views[placed.id]?.apply {
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(260)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    fun remove(item: HomeItem) {
        workspace.items.remove(item)
        if (item.type == HomeItem.Type.WIDGET) widgets.delete(item.ref.toInt())
        views.remove(item.id)?.let { v ->
            v.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(160).withEndAction {
                (v.parent as? ViewGroup)?.removeView(v)
            }.start()
        }
        workspace.removeEmptyPages()
        workspace.save()
        bindAll()
    }

    // endregion

    // region Menus

    private fun onItemLongPress(item: HomeItem, view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Held with the keypad: its menu, where Move and Resize work with the arrows.
        if (!view.isInTouchMode) return showItemMenu(item, view)
        drag.arm(DragSource(item, null, view))
        // Widgets go straight to resizing (with their actions next to the frame); apps get their menu.
        if (item.isWidget) startResize(item) else showItemMenu(item, view)
    }

    /** Opens the item's menu (also used by the keypad Menu key). */
    fun showItemMenu(item: HomeItem, view: View) {
        val anchor = rectInRoot(view)
        when (item.type) {
            HomeItem.Type.APP -> {
                val entry = repo.find(item.ref) ?: return
                overlays.showMenu(
                    item.label ?: entry.label,
                    listOf(
                        shortcuts.menuItems(entry),
                        listOf(
                            MenuItem("Move", R.drawable.ic_move) { startKeyMove(item) },
                            MenuItem("Put in folder", R.drawable.ic_folder) { putInFolder(item, entry) },
                            MenuItem("Rename", R.drawable.ic_edit) { rename(item, entry.label) },
                            MenuItem("Remove", R.drawable.ic_close, danger = true) { remove(item) },
                        ),
                    ),
                    anchor, repo.icon(entry),
                ) { host.showAppInfo(entry) }
            }
            HomeItem.Type.FOLDER -> overlays.showMenu(item.label ?: FOLDER_NAME, listOf(
                MenuItem("Move", R.drawable.ic_move) { startKeyMove(item) },
                MenuItem("Rename", R.drawable.ic_edit) { rename(item, FOLDER_NAME) },
                MenuItem("Remove", R.drawable.ic_close, danger = true) { remove(item) },
            ), anchor, folderIcon(item))
            HomeItem.Type.CLOCK -> overlays.showMenu(clockName(item), listOfNotNull(
                featherSettings(item),
                MenuItem("Move", R.drawable.ic_move) { startKeyMove(item) },
                MenuItem("Resize", R.drawable.ic_height) { startResize(item) },
                MenuItem("Remove", R.drawable.ic_close, danger = true) { remove(item) },
            ), anchor)
            HomeItem.Type.WIDGET -> {
                val info = widgets.info(item.ref.toInt())
                val label = info?.loadLabel(activity.packageManager) ?: "Widget"
                val items = mutableListOf(MenuItem("Move", R.drawable.ic_move) { startKeyMove(item) })
                if (info != null && info.resizeMode != 0) items += MenuItem("Resize", R.drawable.ic_height) { startResize(item) }
                items += MenuItem("Remove", R.drawable.ic_close, danger = true) { remove(item) }
                overlays.showMenu(label, items, anchor, info?.provider?.packageName?.let { pkg ->
                    repo.apps.firstOrNull { it.packageName == pkg }?.let(repo::icon)
                })
            }
        }
    }

    /** Finds the home item behind a focused view, for the keypad Menu key. */
    fun itemFor(view: View?): Pair<HomeItem, View>? {
        var v = view
        while (v != null && v !== root) {
            val entry = views.entries.firstOrNull { it.value === v }
            if (entry != null) return workspace.items.first { it.id == entry.key } to v
            v = v.parent as? View
        }
        return null
    }

    /** A sheet with the name in a field; saving an empty name goes back to the app's own. */
    private fun rename(item: HomeItem, defaultName: String) {
        val p = palette()
        val field = android.widget.EditText(activity).apply {
            setText(item.label ?: defaultName)
            selectAll()
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            style(TextStyle.Body, p.text)
            setHintTextColor(p.textTertiary)
            hint = defaultName
            background = Ui.rounded(activity, p.surfaceHigh, Ui.RADIUS_M)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
        }
        fun save() {
            val name = field.text.toString().trim()
            item.label = name.takeIf { it.isNotEmpty() && it != defaultName }
            workspace.save()
            bindAll()
            overlays.dismiss()
        }
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                if (item.type == HomeItem.Type.APP) {
                    addView(pillButton("Use app's name", p.surfaceHigh, p.text) {
                        field.setText(defaultName)
                        save()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(Ui.SPACE_S) })
                }
                addView(pillButton("Save", p.accent, p.onAccent) { save() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ui.SPACE_M)
            })
        }
        overlays.showSheet("Rename", column)
        field.requestFocus()
        field.post {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // region Folders

    /** The folder's icon: a tile in the icon shape with up to four of its apps inside. */
    private fun folderIcon(item: HomeItem): Bitmap {
        val size = dp(64)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPath(prefs.iconShape.path(size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette().folderFill })
        val pad = size * 0.17f
        val gap = size * 0.07f
        val mini = (size - 2 * pad - gap) / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        item.apps.take(4).forEachIndexed { i, key ->
            val icon = repo.find(key)?.let(repo::icon) ?: return@forEachIndexed
            val x = pad + (i % 2) * (mini + gap)
            val y = pad + (i / 2) * (mini + gap)
            canvas.drawBitmap(icon, null, RectF(x, y, x + mini, y + mini), paint)
        }
        return bitmap
    }

    /** Puts [key] into [target]: into it if it's a folder, or makes a new folder from two apps. */
    private fun addToFolder(target: HomeItem, key: String): HomeItem {
        if (target.type == HomeItem.Type.FOLDER) {
            if (key !in target.apps) target.apps += key
            return target
        }
        workspace.items.remove(target)
        return workspace.add(target.page, target.x, target.y, 1, 1, HomeItem.Type.FOLDER, "").also {
            it.apps += listOf(target.ref, key)
        }
    }

    /**
     * Puts the app [item] in one of the home screen's folders, or in a new folder of its own in
     * its place (named straight away; more apps go in the same way). With the keypad this is how
     * folders are made: moving onto an app makes room.
     */
    private fun putInFolder(item: HomeItem, entry: AppEntry) {
        val folders = workspace.items.filter { it.type == HomeItem.Type.FOLDER }
        val options = folders.map { (it.label ?: FOLDER_NAME) to ImageView(activity).apply { setImageBitmap(folderIcon(it)) } } +
            ("New folder" to ImageView(activity).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = android.content.res.ColorStateList.valueOf(palette().text)
            })
        Components.choiceSheet(activity, palette(), overlays, "Put ${item.label ?: entry.label} in", options, -1) { i ->
            workspace.items.remove(item)
            views.remove(item.id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val folder = folders.getOrNull(i)
            if (folder != null) {
                if (item.ref !in folder.apps) folder.apps += item.ref
            } else {
                val made = workspace.add(item.page, item.x, item.y, 1, 1, HomeItem.Type.FOLDER, "").also { it.apps += item.ref }
                rename(made, FOLDER_NAME)
            }
            workspace.removeEmptyPages()
            workspace.save()
            bindAll()
        }
    }

    /** Takes [key] out; a folder left with one app becomes that app, an empty one disappears. */
    private fun removeFromFolder(folder: HomeItem, key: String) {
        folder.apps.remove(key)
        when (folder.apps.size) {
            0 -> workspace.items.remove(folder)
            1 -> {
                workspace.items.remove(folder)
                workspace.add(folder.page, folder.x, folder.y, 1, 1, HomeItem.Type.APP, folder.apps.first())
            }
        }
    }

    private fun openFolder(item: HomeItem) {
        val p = palette()
        val apps = item.apps.mapNotNull(repo::find)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Same color family as the folder's icon: it belongs to the home screen, not the theme.
            // Same family as the dock and app list: the wallpaper's color with the light hairline.
            background = Ui.rounded(activity, p.glass, Ui.RADIUS_BAR, p.hairline)
            setPadding(dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_M))
        }
        // The name, like a category page's title; tap to rename.
        card.addView(TextView(activity).apply {
            text = item.label ?: FOLDER_NAME
            style(TextStyle.Title, p.onWall)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S))
            background = Ui.pressable(activity, Ui.RADIUS_M)
            isFocusable = true
            setOnClickListener { rename(item, FOLDER_NAME) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(Ui.SPACE_S), 0, 0, dp(Ui.SPACE_XS))
        })
        apps.chunked(FOLDER_COLS).forEach { rowApps ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until FOLDER_COLS) {
                val entry = rowApps.getOrNull(i)
                // Space, not View: an empty View stretches to the whole height available.
                row.addView(if (entry == null) android.widget.Space(activity) else folderTile(item, entry, p),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            card.addView(row)
        }
        overlays.showPanel(card, p.searchScrim)
    }

    private fun folderTile(folder: HomeItem, entry: AppEntry, p: Palette): View {
        val icon = ImageView(activity).apply {
            setImageBitmap(repo.icon(entry))
            IconBadges.apply(this, AppLimits.get(activity).isLocked(entry.packageName), NewApps.get(activity).isNew(entry), 54,
                prefs.badgeCount(entry.packageName))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(Ui.SPACE_S), dp(4), dp(Ui.SPACE_S))
            // The notification badge hangs off the icon's corner.
            clipChildren = false
            clipToPadding = false
            background = Ui.pressable(activity, Ui.RADIUS_M)
            isFocusable = true
            addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(TextView(activity).apply {
                text = entry.label
                style(TextStyle.Caption, p.onWall)
                typeface = android.graphics.Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                overlays.dismiss()
                host.launch(entry, icon)
            }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                drag.arm(DragSource(null, entry, icon, folder, from = rectInRoot(icon)))
                overlays.showMenu(entry.label, listOf(
                    MenuItem("Move out of folder", R.drawable.ic_home) {
                        removeFromFolder(folder, entry.key)
                        workspace.save()
                        addApp(entry)
                    },
                ), rectInRoot(icon), repo.icon(entry)) { host.showAppInfo(entry) }
                true
            }
        }
    }

    // endregion

    private fun pillButton(text: String, fill: Int, color: Int, onClick: () -> Unit) = TextView(activity).apply {
        this.text = text
        gravity = Gravity.CENTER
        style(TextStyle.Label, color)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(Ui.SPACE_L + 4), 0, dp(Ui.SPACE_L + 4), 0)
        background = Ui.rounded(activity, fill, Ui.RADIUS_PILL)
        foreground = Ui.pressable(activity, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun clockName(item: HomeItem) = FeatherKind.of(item).label

    /** What a Feather widget's settings are, if it has any. */
    private fun featherSettings(item: HomeItem): MenuItem? = when (FeatherKind.of(item)) {
        FeatherKind.CLOCK -> MenuItem("${clockName(item)} settings", R.drawable.ic_settings) { clockSettings(item) }
        else -> null
    }

    private fun clockSettings(item: HomeItem) {
        val p = palette()
        val options = prefs.clockOptions(item.id)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_L))
        }
        fun update(next: ClockOptions) {
            prefs.setClockOptions(item.id, next)
            (views[item.id] as? ClockWidget)?.apply(next)
            clockSettings(item)
        }
        column.addView(sectionLabel("Size", p))
        column.addView(segmented(p, ClockOptions.Size.entries.map { it.label }, options.size.ordinal) {
            update(options.copy(size = ClockOptions.Size.entries[it]))
        })
        column.addView(sectionLabel("Alignment", p))
        column.addView(segmented(p, listOf("Center", "Left"), if (options.alignStart) 1 else 0) {
            update(options.copy(alignStart = it == 1))
        })
        column.addView(sectionLabel("Date", p))
        column.addView(segmented(p, listOf("Show", "Hide"), if (options.showDate) 0 else 1) {
            update(options.copy(showDate = it == 0))
        })
        column.addView(sectionLabel("Time", p))
        column.addView(segmented(p, listOf("Show", "Hide"), if (options.showTime) 0 else 1) {
            update(options.copy(showTime = it == 0))
        })
        // What the clock shows besides the time is the same for every clock, so it's in Settings.
        column.addView(android.view.View(activity), LinearLayout.LayoutParams(1, dp(Ui.SPACE_M)))
        column.addView(Components.row(activity, p, R.drawable.ic_calendar, "Calendar and times", chevron = true) {
            overlays.dismiss()
            activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.Page.CALENDAR.name))
        })
        overlays.showSheet(clockName(item), column)
    }

    private fun sectionLabel(text: String, p: Palette) = TextView(activity).apply {
        this.text = text
        style(TextStyle.Section, p.textSecondary)
        setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_L), dp(Ui.SPACE_XL), dp(Ui.SPACE_S))
    }

    // endregion

    // region Drag

    /** An item is being moved with the keypad (see [startKeyMove]). */
    val isKeyMoving get() = drag.isKeyMoving

    /** The keys are for moving or resizing on the home screen right now, not their usual jobs. */
    val takesKeys get() = drag.isKeyMoving || resize != null

    /**
     * Picks [item] up to move it with the keypad: the arrows step it a cell at a time, with what's
     * in the way moving aside as it does for a drag; past the side of the page it goes on to the
     * next one, and below the page into the dock. OK puts it down, Back puts it back.
     */
    fun startKeyMove(item: HomeItem) {
        val view = views[item.id] ?: return
        drag.startKeys(item, view)
    }

    /** Puts the item being moved with the keypad back where it was. */
    fun cancelKeyMove() = drag.keyCancel()

    /** A key while moving with the keypad. Returns true: every key is the move's while it lasts. */
    fun keyMove(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        resize?.let { frame ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) frame.step(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) frame.step(1, 0)
                KeyEvent.KEYCODE_DPAD_UP -> if (down) frame.step(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) frame.step(0, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK ->
                    if (!down && !event.isCanceled) finishResize()
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) drag.keyStep(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) drag.keyStep(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) drag.keyStep(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) drag.keyStep(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU ->
                if (!down && !event.isCanceled) drag.keyDrop()
            KeyEvent.KEYCODE_BACK -> if (!down && !event.isCanceled) drag.keyCancel()
        }
        return true
    }

    /** The rest of a touch that ended a keypad move. */
    private var touchEndedMove = false

    /** Called from the activity for every touch event; returns true when a drag consumed it. */
    fun dispatchTouch(ev: MotionEvent): Boolean {
        // Moving with the keypad: a touch puts the item down where it is, and does nothing else.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && drag.isKeyMoving) {
            drag.keyDrop()
            touchEndedMove = true
        }
        if (touchEndedMove) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) touchEndedMove = false
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            drag.downX = ev.x
            drag.downY = ev.y
        }
        // The gesture that long-pressed an item can still turn into a drag.
        if (drag.isArmed || drag.active) return drag.handle(ev)
        resize?.let { return it.handle(ev) }
        return false
    }

    /** Lets a widget held in the widget list be dragged onto a page; [preview] is how it looks there. */
    fun armWidget(kind: FeatherKind, preview: View) = drag.arm(DragSource(null, null, preview, kind = kind))

    /** Lets a long-pressed app in the app list be dragged onto the home screen. */
    fun armFromDrawer(entry: AppEntry, row: View) = drag.arm(DragSource(null, entry, row))

    /** Takes over a drag that started in the app list; it continues from the finger's next move. */
    fun continueDragFromDrawer(entry: AppEntry, row: View) {
        drag.arm(DragSource(null, entry, row))
        drag.downX = -10_000f
        drag.downY = -10_000f
    }

    /** An icon is being (or about to be) dragged, or a widget resized: swipes must leave it alone. */
    val isBusy get() = drag.active || drag.isArmed || resize != null

    /**
     * What is being dragged: an item already on the home screen, an app from the app list, or one
     * of Feather's widgets from the widget list ([kind]), made where it's dropped.
     */
    private class DragSource(val item: HomeItem?, val entry: AppEntry?, val view: View, val folder: HomeItem? = null, val kind: FeatherKind? = null,
        /** Where [view] was when it was picked up, for one that's gone by the time the drag starts (an app in a folder: its menu closes the folder). */
        val from: Rect? = null)

    private inner class Drag {
        var downX = 0f
        var downY = 0f
        var active = false
        private var source: DragSource? = null
        private var dragView: ImageView? = null
        private var grabX = 0f
        private var grabY = 0f
        private var target: Triple<Int, Int, Int>? = null
        private var lastX = 0f
        private var lastY = 0f
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop * 1.5f

        val isArmed get() = source != null

        fun arm(source: DragSource) {
            this.source = source
        }

        /** Where the keypad has moved the item to (page, column, row); null when not moving by keypad. */
        private var keyCell: Triple<Int, Int, Int>? = null
        val isKeyMoving get() = keyCell != null

        fun startKeys(item: HomeItem, view: View) {
            val r = rectInRoot((view as? AppIconView)?.iconView ?: view)
            downX = r.exactCenterX()
            downY = r.exactCenterY()
            val src = DragSource(item, null, view)
            source = src
            val now = android.os.SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, downX, downY, 0)
            start(src, ev)
            ev.recycle()
            keyCell = Triple(item.page, item.x, item.y)
            showKeyCell()
            android.widget.Toast.makeText(activity, "Use arrows to move. OK to place, Back to cancel.", android.widget.Toast.LENGTH_SHORT).show()
        }

        fun keyStep(dx: Int, dy: Int) {
            val before = target
            // A step that would land the item where it already is (it can't go over a widget,
            // so it settles in the nearest free spot) keeps going, so it never gets stuck.
            repeat(Workspace.COLS + Workspace.ROWS) {
                val from = keyCell ?: return
                val next = nextKeyCell(from, dx, dy) ?: return
                keyCell = next
                if (next.first != from.first && from.first != Workspace.DOCK && next.first != Workspace.DOCK) {
                    // A new page: wait for it to slide into place.
                    root.postDelayed({ showKeyCell() }, 300)
                    return
                }
                showKeyCell()
                val now = target
                if (now != null && now != before) {
                    keyCell = now
                    return
                }
            }
        }

        /** The spot one step from [from], or null where it can't go. */
        private fun nextKeyCell(from: Triple<Int, Int, Int>, dx: Int, dy: Int): Triple<Int, Int, Int>? {
            val (page, x, y) = from
            if (page == Workspace.DOCK) return when {
                // Up out of the dock, onto the bottom row of the page.
                dy < 0 -> pages.currentPage.let { Triple(it, x.coerceAtMost(workspace.cols(it) - w), workspace.rows(it) - h) }
                dx != 0 && x + dx in 0 until workspace.cols(page) -> Triple(page, x + dx, 0)
                else -> null
            }
            val cols = workspace.cols(page)
            val rows = workspace.rows(page)
            return when {
                x + dx < 0 || x + dx > cols - w -> {
                    // Past the side: on to the page beyond, at the near edge.
                    flipPage(dx)
                    val now = pages.currentPage
                    if (now == page) null
                    else Triple(now, if (dx > 0) 0 else workspace.cols(now) - w, y.coerceAtMost(workspace.rows(now) - h))
                }
                y + dy < 0 -> null
                // Below the page: into the dock, for an app or folder.
                y + dy > rows - h -> if (w == 1 && h == 1) Triple(Workspace.DOCK, x.coerceAtMost(workspace.cols(Workspace.DOCK) - 1), 0) else null
                else -> Triple(page, x + dx, y + dy)
            }
        }

        /** Makes room at the keypad's spot and glides the item's picture there (or to the nearest free spot). */
        private fun showKeyCell() {
            val cell = keyCell ?: return
            val view = dragView ?: return
            root.removeCallbacks(reorder)
            hoverCell = cell
            applyReorder()
            val (page, x, y) = target ?: cell
            val cells = containerFor(page)
            val origin = rectInRoot(cells)
            val r = cells.cellRect(Rect(x, y, x + w, y + h))
            // An icon sits above its name in the cell; a widget fills its cells.
            val lift = if (source?.item?.isWidget == true) 0f else cells.iconOffset
            // Just picked up, the picture isn't laid out yet: its size is still only in its layout params.
            val vw = view.width.takeIf { it > 0 } ?: view.layoutParams.width
            val vh = view.height.takeIf { it > 0 } ?: view.layoutParams.height
            view.animate().x(origin.left + r.exactCenterX() - vw / 2f).y(origin.top + r.exactCenterY() - lift - vh / 2f)
                .setDuration(150).setInterpolator(DecelerateInterpolator(2f)).start()
        }

        fun keyDrop() {
            if (keyCell == null) return
            keyCell = null
            drop()
            source = null
        }

        fun keyCancel() {
            if (keyCell == null) return
            keyCell = null
            cancel()
            source = null
        }

        /**
         * Puts [item] back where it came from. From another page, the pages slide back to its
         * own first, so it's clear where it went.
         */
        private fun goBack(item: HomeItem) {
            if (item.page == Workspace.DOCK || item.page == pages.currentPage) return settleOn(item)
            pages.snapTo(item.page)
            updateLeftZone()
            root.postDelayed({ settleOn(item) }, 320)
        }

        fun handle(ev: MotionEvent): Boolean {
            val src = source ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!active && hypot(ev.x - downX, ev.y - downY) > slop) start(src, ev)
                    if (active) move(ev.x, ev.y)
                }
                MotionEvent.ACTION_UP -> {
                    val wasActive = active
                    if (active) drop()
                    source = null
                    return wasActive
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (active) cancel()
                    source = null
                }
            }
            return active
        }

        private val w get() = source?.item?.w ?: source?.kind?.w ?: 1
        private val h get() = source?.item?.h ?: source?.kind?.height(cellSizeDp().second) ?: 1

        private fun start(src: DragSource, ev: MotionEvent) {
            active = true
            // A widget's picture is taken from the widget list before it closes, and where an
            // app in a folder is before the folder closes: once it's closing, it's gone or moving.
            val listShot = src.kind?.let { snapshot(src.view) to rectInRoot(src.view) }
            val inFolder = src.from ?: src.folder?.let { rectInRoot(src.view) }
            overlays.dismiss()
            finishResize()
            host.cancelViewTouches()

            val bitmap: Bitmap
            val origin: Rect
            if (src.item != null) {
                val shot = (src.view as? AppIconView)?.iconView ?: src.view
                bitmap = snapshot(shot)
                origin = rectInRoot(shot)
                src.view.visibility = View.INVISIBLE
            } else if (listShot != null) {
                bitmap = listShot.first
                origin = listShot.second
            } else {
                bitmap = repo.icon(src.entry!!) ?: return cancel()
                if (inFolder != null) {
                    origin = inFolder
                } else {
                    val size = dp(AppIconView.ICON)
                    origin = Rect(ev.x.toInt() - size / 2, ev.y.toInt() - size / 2, ev.x.toInt() + size / 2, ev.y.toInt() + size / 2)
                    host.closeDrawer(false)
                }
            }
            // Hold the icon where the finger first pressed it, so it stays under the finger.
            val fromPress = src.item != null || src.folder != null || src.kind != null
            grabX = (if (fromPress) downX else ev.x) - origin.left
            grabY = (if (fromPress) downY else ev.y) - origin.top
            dragView = ImageView(activity).apply {
                setImageBitmap(bitmap)
                elevation = dp(12).toFloat()
                x = ev.x - grabX
                y = ev.y - grabY
                root.addView(this, FrameLayout.LayoutParams(origin.width(), origin.height()))
                animate().scaleX(1.08f).scaleY(1.08f).alpha(0.95f).setDuration(150).start()
            }
            showEdgeZones(fromDrawer = src.entry != null && src.folder == null)
        }

        private fun move(x: Float, y: Float) {
            lastX = x
            lastY = y
            val view = dragView ?: return
            view.x = x - grabX
            view.y = y - grabY

            // Edge targets: hold over one to change page or go back to the app list.
            var onZone = false
            for ((zone, end) in listOf(leftZone to false, rightZone to true)) {
                val over = overSide(zone, x, y, end)
                zone?.hover(over)
                onZone = onZone || over
            }
            bottomZone?.let { zone ->
                val over = overBottom(y)
                zone.hover(over)
                onZone = onZone || over
            }
            if (onZone) {
                clearReorder(commit = false)
                setFolderTarget(null)
                setTarget(null)
                return
            }

            val dockRect = rectInRoot(dock)
            val inDock = dockRect.contains(x.toInt(), y.toInt())
            // The dock lights up with an outline while the item is over it.
            dockOutline?.animate()?.alpha(if (inDock && w == 1 && h == 1) 1f else 0f)?.setDuration(120)?.start()
            val page = when {
                inDock && w == 1 && h == 1 -> Workspace.DOCK
                inDock -> null
                // Beside or below the dock is neither the dock nor the page.
                y >= dockRect.top -> null
                else -> pages.currentPage
            }
            if (page == null) {
                clearReorder(commit = false)
                setTarget(null)
                return
            }
            val cells = containerFor(page)
            val origin = rectInRoot(cells)
            val centerX = view.x + view.width / 2f - origin.left - cells.originX
            val centerY = view.y + view.height / 2f - origin.top - cells.paddingTop
            val cx = ((centerX - w * cells.cellWidth / 2) / cells.cellWidth).roundToInt()
            val cy = ((centerY - h * cells.cellHeight / 2) / cells.cellHeight).roundToInt()
            val col = cx.coerceIn(0, workspace.cols(page) - w)
            val row = cy.coerceIn(0, workspace.rows(page) - h)
            val candidate = if (w == 1 && h == 1) folderCandidate(page, col, row, view) else null
            setFolderTarget(candidate)
            if (candidate != null) {
                clearReorder(commit = false)
                setTarget(null)
            } else {
                // After a short hover, what's in the way slides aside: icons for an app, anything for a widget.
                // Over a free spot there's nothing to wait for: the highlight follows at once.
                val cell = Triple(page, col, row)
                if (cell != hoverCell) {
                    hoverCell = cell
                    root.removeCallbacks(reorder)
                    if (workspace.isFree(page, col, row, w, h, source?.item)) applyReorder()
                    else root.postDelayed(reorder, REORDER_DELAY)
                }
            }
        }

        private var leftZone: DropZone? = null
        private var rightZone: DropZone? = null
        private var bottomZone: DropZone? = null
        private var pageOutline: DropZone? = null
        private var dockOutline: View? = null

        /**
         * While dragging, outlines appear and nothing else moves: one around the page, slivers of
         * the pages beside it at the screen edges, and a sliver of the app list below the dock.
         * The slivers are the targets for changing page or going back to the app list.
         */
        private fun showEdgeZones(fromDrawer: Boolean) {
            val outline = 0xFFFFFFFF.toInt()
            val box = rectInRoot(pages)
            val dockBox = rectInRoot(dock)
            val gap = dp(EDGE_GAP)
            val top = box.top + gap
            // Just the page: the dots under it aren't part of it.
            val height = box.bottom - top
            fun zone(lp: FrameLayout.LayoutParams) = DropZone(activity, 0, outline, Ui.RADIUS_BAR).also { root.addView(it, lp) }

            pageOutline = zone(FrameLayout.LayoutParams(root.width - 2 * dp(Ui.BAR_MARGIN), height, Gravity.TOP or Gravity.START).apply {
                topMargin = top
                leftMargin = dp(Ui.BAR_MARGIN)
            })
            // Slivers: the neighbouring page starts just past the edge of this one.
            val sliver = dp(Ui.BAR_MARGIN) - gap
            fun side(gravity: Int) = zone(FrameLayout.LayoutParams(dp(80), height, gravity or Gravity.TOP).apply {
                topMargin = top
                if (gravity == Gravity.END) rightMargin = sliver - dp(80) else leftMargin = sliver - dp(80)
            })
            val dockBox0 = rectInRoot(dock)
            dockOutline = View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(Ui.RADIUS_BAR).toFloat()
                    setStroke(dp(2), 0xB3FFFFFF.toInt())
                    setColor(0x1AFFFFFF)
                }
                alpha = 0f
                root.addView(this, FrameLayout.LayoutParams(dockBox0.width(), dockBox0.height(), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dockBox0.left
                    topMargin = dockBox0.top
                })
            }
            leftZone = side(Gravity.START).apply { onTrigger = { flipPage(-1) } }
            rightZone = side(Gravity.END).apply { onTrigger = { flipPage(1) } }
            updateLeftZone()
            if (fromDrawer) {
                bottomZone = zone(FrameLayout.LayoutParams(root.width - 2 * dp(Ui.BAR_MARGIN), dp(80), Gravity.TOP or Gravity.START).apply {
                    topMargin = dockBox.bottom + gap
                    leftMargin = dp(Ui.BAR_MARGIN)
                }).apply { onTrigger = ::backToDrawer }
            }
        }

        /** Over a side sliver: the strip at that screen edge. Over the app list's: below the dock. */
        private fun overSide(zone: DropZone?, x: Float, y: Float, end: Boolean): Boolean {
            if (zone == null || zone.visibility != View.VISIBLE) return false
            val edge = dp(EDGE_HIT)
            val box = rectInRoot(pages)
            return y > box.top && y < rectInRoot(dock).top && if (end) x > root.width - edge else x < edge
        }

        private fun overBottom(y: Float) = bottomZone != null && y >= rectInRoot(dock).bottom - dp(2)

        /** There's no page to the left of the first. */
        private fun updateLeftZone() {
            leftZone?.visibility = if (pages.currentPage > 0) View.VISIBLE else View.INVISIBLE
        }

        private fun hideEdgeZones() {
            listOfNotNull(leftZone, rightZone, bottomZone, pageOutline).forEach { it.dismiss() }
            dockOutline?.let { v -> v.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(v) }.start() }
            dockOutline = null
            leftZone = null
            rightZone = null
            bottomZone = null
            pageOutline = null
        }

        private fun backToDrawer() {
            val entry = source?.entry ?: return
            clearReorder(commit = false)
            setFolderTarget(null)
            setTarget(null)
            hideEdgeZones()
            dragView?.let(root::removeView)
            dragView = null
            active = false
            source = null
            host.returnToDrawer(entry, lastX, lastY)
        }

        private var folderTarget: HomeItem? = null

        /**
         * The app or folder at ([col], [row]) if the dragged icon is over the middle of it,
         * meaning a drop would make or fill a folder. Folders can't go inside folders.
         */
        private fun folderCandidate(page: Int, col: Int, row: Int, view: View): HomeItem? {
            if (source?.item?.type == HomeItem.Type.FOLDER) return null
            val occupant = workspace.on(page).firstOrNull {
                it.x == col && it.y == row && it !== source?.item && it !== source?.folder && it !in shifted.keys &&
                    (it.type == HomeItem.Type.APP || it.type == HomeItem.Type.FOLDER)
            } ?: return null
            val cells = containerFor(page)
            val origin = rectInRoot(cells)
            val r = cells.cellRect(Rect(col, row, col + 1, row + 1))
            val cx = origin.left + r.exactCenterX()
            val cy = origin.top + r.exactCenterY() - cells.iconOffset
            val distance = hypot(view.x + view.width / 2f - cx, view.y + view.height / 2f - cy)
            return occupant.takeIf { distance < dp(AppIconView.ICON) * 0.45f }
        }

        private fun setFolderTarget(next: HomeItem?) {
            if (next === folderTarget) return
            folderTarget?.let { old ->
                val cells = containerFor(old.page)
                cells.dropPreviewGrow = false
                cells.dropPreview = null
            }
            folderTarget = next
            next?.let {
                val cells = containerFor(it.page)
                cells.dropPreviewIsIcon = true
                cells.dropPreviewGrow = true
                cells.dropPreview = Rect(it.x, it.y, it.x + 1, it.y + 1)
            }
        }

        private fun dropIntoFolder(src: DragSource, target: HomeItem) {
            setFolderTarget(null)
            clearReorder(commit = false)
            val key = src.item?.ref ?: src.entry!!.key
            src.item?.let { moved ->
                workspace.items.remove(moved)
                views.remove(moved.id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
            src.folder?.let { removeFromFolder(it, key) }
            val folder = addToFolder(target, key)
            workspace.removeEmptyPages()
            workspace.save()
            bindAll()
            val icon = (views[folder.id] as? AppIconView)?.iconView
            val view = dragView ?: return finish()
            if (icon == null) return fadeOut()
            val rect = rectInRoot(icon)
            view.animate().x(rect.left.toFloat()).y(rect.top.toFloat()).scaleX(0.45f).scaleY(0.45f).alpha(0f)
                .setDuration(200).withEndAction { finish() }.start()
        }

        /** Items slid aside to make room, with the cell each was moved to. */
        private var shifted: Map<HomeItem, Pair<Int, Int>> = emptyMap()
        private var hoverCell: Triple<Int, Int, Int>? = null
        private val reorder = Runnable { applyReorder() }

        private fun applyReorder() {
            val (page, x, y) = hoverCell ?: return
            val plan = if (w == 1 && h == 1) planReorder(page, x, y) else planWidgetReorder(page, x, y)
            when (plan) {
                null -> {
                    // No way to make room here (a widget, or a full page): use the closest free spot.
                    setShifted(emptyMap())
                    val spot = workspace.nearestSpot(page, x, y, w, h, source?.item)
                    setTarget(spot?.let { Triple(page, it.first, it.second) })
                }
                else -> {
                    setShifted(plan)
                    setTarget(Triple(page, x, y))
                }
            }
        }

        /**
         * Which icons move where so a dragged app fits at ([cx], [cy]). The icon in the way
         * moves to the next cell in reading order, pushing the next one along, until a free
         * cell is reached; if the page fills up first, the push goes backwards instead.
         * Cells covered by widgets are skipped. Empty map: the cell is free. Null: no room.
         */
        private fun planReorder(page: Int, cx: Int, cy: Int): Map<HomeItem, Pair<Int, Int>>? {
            val dragged = source?.item
            val others = workspace.on(page).filter { it !== dragged }
            fun at(x: Int, y: Int) = others.firstOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }
            val first = at(cx, cy) ?: return emptyMap()
            if (first.w != 1 || first.h != 1) return null
            val cols = workspace.cols(page)
            val cells = (0 until workspace.rows(page)).flatMap { y -> (0 until cols).map { x -> x to y } }
            val start = cy * cols + cx
            for (direction in listOf(1, -1)) {
                val plan = LinkedHashMap<HomeItem, Pair<Int, Int>>()
                var moving = first
                var i = start + direction
                while (i in cells.indices) {
                    val (x, y) = cells[i]
                    val occupant = at(x, y)
                    if (occupant == null) {
                        plan[moving] = x to y
                        return plan
                    }
                    if (occupant.w == 1 && occupant.h == 1) {
                        plan[moving] = x to y
                        moving = occupant
                    }
                    i += direction
                }
            }
            return null
        }

        /**
         * What moves where so a dragged widget fits at ([cx], [cy]). Each thing in the way goes where
         * the dragged widget came from if it fits there (so two widgets swap), else to the nearest
         * free spot. Empty map: the space is free. Null: there isn't room on the page.
         */
        private fun planWidgetReorder(page: Int, cx: Int, cy: Int): Map<HomeItem, Pair<Int, Int>>? {
            val dragged = source?.item
            val cols = workspace.cols(page)
            val rows = workspace.rows(page)
            val others = workspace.on(page).filter { it !== dragged }
            fun overlaps(a: HomeItem, x: Int, y: Int, w: Int, h: Int) = a.x < x + w && x < a.x + a.w && a.y < y + h && y < a.y + a.h
            val inWay = others.filter { overlaps(it, cx, cy, w, h) }
            if (inWay.isEmpty()) return emptyMap()
            // Taken cells: everything staying put, and where the widget is going.
            val taken = Array(rows) { BooleanArray(cols) }
            fun take(x: Int, y: Int, w: Int, h: Int) {
                for (yy in y until y + h) for (xx in x until x + w) if (yy in 0 until rows && xx in 0 until cols) taken[yy][xx] = true
            }
            fun free(x: Int, y: Int, w: Int, h: Int) =
                x >= 0 && y >= 0 && x + w <= cols && y + h <= rows && (y until y + h).all { yy -> (x until x + w).none { xx -> taken[yy][xx] } }
            others.filter { it !in inWay }.forEach { take(it.x, it.y, it.w, it.h) }
            take(cx, cy, w, h)
            val plan = LinkedHashMap<HomeItem, Pair<Int, Int>>()
            for (item in inWay.sortedBy { it.y * cols + it.x }) {
                val swap = dragged?.takeIf { it.page == page }?.let { d ->
                    (d.x + item.x - cx).coerceIn(0, cols - item.w) to (d.y + item.y - cy).coerceIn(0, rows - item.h)
                }
                val spot = swap?.takeIf { free(it.first, it.second, item.w, item.h) }
                    ?: (0..rows - item.h).flatMap { y -> (0..cols - item.w).map { x -> x to y } }
                        .filter { (x, y) -> free(x, y, item.w, item.h) }
                        .minByOrNull { (x, y) -> abs(x - item.x) + abs(y - item.y) * 2 }
                    ?: return null
                plan[item] = spot
                take(spot.first, spot.second, item.w, item.h)
            }
            return plan
        }

        /** Slides icons to their temporary cells (and others back home). Nothing is saved until drop. */
        private fun setShifted(next: Map<HomeItem, Pair<Int, Int>>) {
            (shifted.keys - next.keys).forEach { item ->
                views[item.id]?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(180)?.start()
            }
            next.forEach { (item, cell) ->
                val view = views[item.id] ?: return@forEach
                val cells = containerFor(item.page)
                val from = cells.cellRect(Rect(item.x, item.y, item.x + 1, item.y + 1))
                val to = cells.cellRect(Rect(cell.first, cell.second, cell.first + 1, cell.second + 1))
                view.animate().translationX((to.left - from.left).toFloat()).translationY((to.top - from.top).toFloat())
                    .setDuration(180).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            shifted = next
        }

        private fun clearReorder(commit: Boolean) {
            root.removeCallbacks(reorder)
            hoverCell = null
            if (commit) {
                shifted.forEach { (item, cell) ->
                    item.x = cell.first
                    item.y = cell.second
                    views[item.id]?.apply { animate().cancel(); translationX = 0f; translationY = 0f }
                }
                shifted = emptyMap()
            } else {
                setShifted(emptyMap())
            }
        }

        private fun setTarget(next: Triple<Int, Int, Int>?) {
            if (next == target) return
            target?.let { containerFor(it.first).dropPreview = null }
            target = next
            next?.let {
                val cells = containerFor(it.first)
                cells.dropPreviewIsIcon = source?.item?.isWidget != true
                cells.dropPreview = Rect(it.second, it.third, it.second + w, it.third + h)
            }
        }

        private fun flipPage(direction: Int) {
            leftZone?.restart()
            rightZone?.restart()
            val next = pages.currentPage + direction
            if (next < 0) return
            if (next >= workspace.pageCount) {
                // Past the last page: make a new one, unless this page only holds the dragged item.
                val others = workspace.on(pages.currentPage).filter { it !== source?.item }
                if (others.isEmpty()) return
                workspace.addPage()
                pages.addView(newPage())
                dots.count = workspace.pageCount
            }
            clearReorder(commit = false)
            setTarget(null)
            // The page outline goes with the old page: it fades out as the pages slide, then
            // fades back in around the new one.
            listOfNotNull(pageOutline, leftZone, rightZone).forEach { z ->
                z.animate().cancel()
                z.animate().alpha(0f).setDuration(120).withEndAction {
                    z.animate().alpha(1f).setStartDelay(160).setDuration(160).start()
                }.start()
            }
            pages.snapTo(next)
            updateLeftZone()
            root.postDelayed({ if (active && keyCell == null) move(lastX, lastY) }, 300)
        }

        private fun drop() {
            hideEdgeZones()
            // Released before the hover delay ran: work out the spot now.
            if (hoverCell != null && target?.let { Triple(it.first, it.second, it.third) } != hoverCell) applyReorder()
            val src = source ?: return finish()
            val t = target
            val intoFolder = folderTarget
            if (intoFolder != null) return dropIntoFolder(src, intoFolder)
            if (t == null) {
                clearReorder(commit = false)
                // No room here: put it back where it was.
                if (src.item != null) goBack(src.item) else fadeOut()
                return
            }
            clearReorder(commit = true)
            val item = src.item
                ?: src.kind?.let { workspace.add(t.first, t.second, t.third, it.w, h, HomeItem.Type.CLOCK, it.ref) }
                ?: workspace.add(t.first, t.second, t.third, 1, 1, HomeItem.Type.APP, src.entry!!.key)
            src.folder?.let { removeFromFolder(it, src.entry!!.key) }
            item.page = t.first
            item.x = t.second
            item.y = t.third
            setTarget(null)
            val emptied = workspace.removeEmptyPages()
            workspace.save()
            bindAll()
            if (emptied) pages.snapTo(item.page.coerceAtLeast(0).coerceAtMost(workspace.pageCount - 1), animate = false)
            views[item.id]?.visibility = View.INVISIBLE
            // From the app list or a folder, the picture is the bare icon, without the room around it.
            val bare = src.item == null && src.kind == null
            root.post { settleOn(item, bare) }
        }

        /** Animates the dragged image into the item's cell, then shows the real view. */
        private fun settleOn(item: HomeItem, bare: Boolean = false) {
            val real = views[item.id]
            val view = dragView ?: return finish()
            if (real == null || real.width == 0) return finish()
            val targetView = (real as? AppIconView)?.iconView ?: real
            val rect = rectInRoot(targetView)
            // Lands exactly over the icon it becomes, at its size, so nothing jumps when it's swapped in.
            if (bare) rect.set(rect.left + targetView.paddingLeft, rect.top + targetView.paddingTop,
                rect.right - targetView.paddingRight, rect.bottom - targetView.paddingBottom)
            val w = view.width.takeIf { it > 0 } ?: view.layoutParams.width
            val h = view.height.takeIf { it > 0 } ?: view.layoutParams.height
            view.animate().x(rect.exactCenterX() - w / 2f).y(rect.exactCenterY() - h / 2f)
                .scaleX(rect.width() / w.toFloat()).scaleY(rect.height() / h.toFloat()).alpha(1f)
                .setDuration(200).setInterpolator(DecelerateInterpolator(2f))
                .withEndAction { finish() }.start()
        }

        private fun fadeOut() {
            dragView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { finish() }?.start() ?: finish()
        }

        private fun cancel() {
            hideEdgeZones()
            clearReorder(commit = false)
            setTarget(null)
            source?.item?.let { goBack(it) } ?: fadeOut()
        }

        private fun finish() {
            setFolderTarget(null)
            dragView?.let(root::removeView)
            dragView = null
            setTarget(null)
            source?.item?.let { views[it.id]?.visibility = View.VISIBLE }
            workspace.items.forEach { views[it.id]?.visibility = View.VISIBLE }
            active = false
        }

    }

    // endregion

    // region Resize

    fun startResize(item: HomeItem) {
        finishResize()
        val view = views[item.id] ?: return
        val min = if (item.type == HomeItem.Type.WIDGET) widgets.minSpan(item.ref.toInt()) else 2 to 1
        val frame = ResizeFrame(item, view, min)
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.toolbar = widgetToolbar(item).also {
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        resize = frame
        view.post { frame.placeToolbar() }
        if (!view.isInTouchMode) {
            android.widget.Toast.makeText(activity, "Resize it with the arrows, then press OK", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun finishResize(): Boolean {
        val frame = resize ?: return false
        resize = null
        root.removeView(frame)
        frame.toolbar?.let(root::removeView)
        workspace.save()
        return true
    }

    /** The widget's actions, in a small bar beside the resize frame. */
    private fun widgetToolbar(item: HomeItem): View {
        val p = palette()
        val actions = mutableListOf<MenuItem>()
        when (item.type) {
            HomeItem.Type.CLOCK -> featherSettings(item)?.let { actions += it }
            HomeItem.Type.WIDGET -> if (widgets.canReconfigure(item.ref.toInt())) {
                actions += MenuItem("Settings", R.drawable.ic_settings) { widgets.reconfigure(item.ref.toInt()) }
            }
            HomeItem.Type.APP, HomeItem.Type.FOLDER -> Unit
        }
        actions += MenuItem("Remove", R.drawable.ic_close, danger = true) { remove(item) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            // A pill, as are the buttons inside it.
            background = Ui.rounded(activity, p.surface, Ui.RADIUS_PILL)
            elevation = dp(8).toFloat()
            val pad = dp(TOOLBAR_PADDING)
            setPadding(pad, pad, pad, pad)
            actions.forEachIndexed { i, action ->
                val color = if (action.danger) p.danger else p.text
                addView(TextView(activity).apply {
                    text = action.label
                    gravity = Gravity.CENTER_VERTICAL
                    style(TextStyle.Label, color)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(action.icon, 0, 0, 0)
                    compoundDrawablePadding = dp(8)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
                    setPadding(dp(14), 0, dp(16), 0)
                    background = Ui.rounded(activity, if (action.danger) p.dangerSurface else p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(activity, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener {
                        finishResize()
                        action.action()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                    if (i > 0) marginStart = dp(TOOLBAR_PADDING)
                })
            }
            alpha = 0f
        }
    }

    /** A frame with a handle on each side; drag a handle to change the widget's size in whole cells. */
    @SuppressLint("ViewConstructor")
    private inner class ResizeFrame(
        val item: HomeItem,
        val view: View,
        val min: Pair<Int, Int>,
    ) : View(activity) {
        var toolbar: View? = null
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = 0xFFFFFFFF.toInt()
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private var handle = -1 // 0 left, 1 top, 2 right, 3 bottom
        /** This gesture belongs to the widget or the toolbar, not the frame. */
        private var passThrough = false

        private fun bounds(): RectF {
            val r = rectInRoot(view)
            val inset = dp(2).toFloat()
            return RectF(r).apply { inset(inset, inset) }
        }

        override fun onDraw(canvas: Canvas) {
            val b = bounds()
            // Around a card, its curve runs parallel to the card's, the frame's distance out.
            val radius = when (view) {
                is CardWidget -> dp(WidgetStyle.RADIUS + CardWidget.GAP - 2).toFloat()
                is WidgetView -> (view.corner + view.cornerInset - dp(2)).coerceAtLeast(dp(18).toFloat())
                else -> dp(18).toFloat()
            }
            canvas.drawRoundRect(b, radius, radius, stroke)
            val r = dp(7).toFloat()
            handles(b).forEach { (x, y) -> canvas.drawCircle(x, y, r, knob) }
        }

        private fun handles(b: RectF) = listOf(
            b.left to b.centerY(), b.centerX() to b.top, b.right to b.centerY(), b.centerX() to b.bottom,
        )

        /**
         * Above the widget if there's room on the page, otherwise below, centered on it. A widget
         * that fills the page has it inside along its bottom, so it never covers the dock.
         */
        fun placeToolbar() {
            val bar = toolbar ?: return
            bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val b = bounds()
            val gap = dp(12)
            val above = b.top - gap - bar.measuredHeight
            val below = b.bottom + gap
            val y = when {
                above > rectInRoot(pages).top -> above
                below + bar.measuredHeight < rectInRoot(dock).top - gap -> below
                else -> b.bottom - gap - bar.measuredHeight
            }
            val x = (b.centerX() - bar.measuredWidth / 2f).coerceIn(dp(12).toFloat(), (root.width - bar.measuredWidth - dp(12)).toFloat())
            bar.x = x
            bar.y = y
            bar.animate().alpha(1f).setDuration(140).start()
        }

        fun handle(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    passThrough = false
                    val hit = handles(bounds()).indexOfFirst { (x, y) -> hypot(ev.x - x, ev.y - y) < dp(32) }
                    if (hit >= 0) {
                        handle = hit
                        return true
                    }
                    val onToolbar = toolbar?.let { rectInRoot(it).contains(ev.x.toInt(), ev.y.toInt()) } == true
                    if (onToolbar || bounds().contains(ev.x, ev.y)) {
                        // Toolbar buttons and the widget itself (long-press again to drag) work as usual.
                        passThrough = true
                        return false
                    }
                    // A tap anywhere else finishes resizing.
                    finishResize()
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (handle >= 0) resizeTo(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handle = -1
            }
            return !passThrough
        }

        /** With the keypad: right and down make it bigger by a cell, left and up smaller. */
        fun step(dw: Int, dh: Int) {
            val w = (item.w + dw).coerceIn(min.first, Workspace.COLS - item.x)
            val h = (item.h + dh).coerceIn(min.second, Workspace.ROWS - item.y)
            setSpan(item.x, item.y, w, h)
        }

        private fun resizeTo(px: Float, py: Float) {
            val cells = containerFor(item.page)
            val origin = rectInRoot(cells)
            val gx = ((px - origin.left - cells.originX) / cells.cellWidth).roundToInt()
            val gy = ((py - origin.top - cells.paddingTop) / cells.cellHeight).roundToInt()
            var x = item.x
            var y = item.y
            var w = item.w
            var h = item.h
            when (handle) {
                0 -> { val right = x + w; x = gx.coerceIn(0, right - min.first); w = right - x }
                1 -> { val bottom = y + h; y = gy.coerceIn(0, bottom - min.second); h = bottom - y }
                2 -> w = (gx - x).coerceIn(min.first, Workspace.COLS - x)
                3 -> h = (gy - y).coerceIn(min.second, Workspace.ROWS - y)
            }
            setSpan(x, y, w, h)
        }

        private fun setSpan(x: Int, y: Int, w: Int, h: Int) {
            if (x == item.x && y == item.y && w == item.w && h == item.h) return
            if (!workspace.isFree(item.page, x, y, w, h, item)) return
            item.x = x; item.y = y; item.w = w; item.h = h
            containerFor(item.page).place(view, x, y, w, h)
            view.post {
                invalidate()
                placeToolbar()
            }
        }
    }

    // endregion

    private fun snapshot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Draw without the keypad highlight or press scale.
        // A card widget's background is the card itself; others' is just the keypad highlight.
        if (view is CardWidget) {
            view.draw(Canvas(bitmap))
            return bitmap
        }
        val background = view.background
        // Putting a background back resets the padding to the background's own; the view's stays.
        val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.background = null
        val canvas = Canvas(bitmap)
        // Drawn off screen, the widget isn't cut to its rounded corners; cut it here instead.
        if (view is WidgetView && view.corner > 0) {
            canvas.clipPath(android.graphics.Path().apply {
                addRoundRect(RectF(view.cornerBox), view.corner, view.corner, android.graphics.Path.Direction.CW)
            })
        }
        view.draw(canvas)
        view.background = background
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
        return bitmap
    }

    /**
     * Moves the keypad highlight on the home screen in [direction] (a View.FOCUS_ constant).
     * It goes where Android goes ([nearest]): what's in the same row or column first. With
     * nothing further on the right or left, it moves to the next or previous page. Returns false
     * if there's nothing to focus.
     */
    fun moveFocus(direction: Int): Boolean {
        // Inside a widget the arrows stay in it, among what can be pressed; Back comes out.
        inside?.let { widget ->
            val parts = pressablesIn(widget)
            val from = activity.currentFocus?.takeIf { it in parts }
            if (from == null) {
                inside = null
            } else {
                nearest(from, parts - from, direction)?.requestFocusFromTouch()
                return true
            }
        }
        val here = itemsOn(pages.currentPage) + itemsOn(Workspace.DOCK)
        if (here.isEmpty()) return false
        // Resting on the invisible spot (or nowhere) counts as no highlight yet: start at the top.
        val from = activity.currentFocus?.takeIf { it in here && !it.isInTouchMode && it.isFocused }
        if (from == null) {
            here.minByOrNull { rectInRoot(it).let { r -> r.top * 10_000 + r.left } }?.requestFocusFromTouch()
            return true
        }
        val r = rectInRoot(from)
        val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
        val best = nearest(from, here - from, direction)
        if (best != null) {
            // From touch: the arrows often arrive through the key service while the screen is
            // still in touch mode, where a plain request is refused and nothing would move.
            best.requestFocusFromTouch()
            return true
        }
        if (vertical) return true
        // Off the edge sideways: the next page, landing on its item nearest in height.
        val next = pages.currentPage + if (direction == View.FOCUS_RIGHT) 1 else -1
        if (next !in 0 until workspace.pageCount || from.parent === dock) return true
        pages.snapTo(next)
        itemsOn(next).minByOrNull { v ->
            val y = abs(rectInRoot(v).centerY() - r.centerY())
            val x = if (direction == View.FOCUS_RIGHT) v.left else -v.right
            y * 4 + x
        }?.requestFocusFromTouch()
        return true
    }

    /**
     * What's next from [from] in [direction] among [others], chosen the way Android itself
     * (and so every Android TV) chooses: something directly in line wins; otherwise the
     * closest, with distance along the direction counting far more than distance to the side.
     */
    private fun nearest(from: View, others: List<View>, direction: Int): View? {
        val src = rectInRoot(from)
        var best: Rect? = null
        var bestView: View? = null
        for (view in others) {
            val r = rectInRoot(view)
            if (!isCandidate(src, r, direction)) continue
            if (best == null || isBetter(direction, src, r, best)) {
                best = r
                bestView = view
            }
        }
        return bestView
    }

    // The rules below follow Android's FocusFinder.

    /** Whether [dest] lies in [direction] from [src] at all. */
    private fun isCandidate(src: Rect, dest: Rect, direction: Int) = when (direction) {
        View.FOCUS_LEFT -> (src.right > dest.right || src.left >= dest.right) && src.left > dest.left
        View.FOCUS_RIGHT -> (src.left < dest.left || src.right <= dest.left) && src.right < dest.right
        View.FOCUS_UP -> (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top
        else -> (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom
    }

    /** Whether [a] is a better next stop from [src] than [b]. */
    private fun isBetter(direction: Int, src: Rect, a: Rect, b: Rect): Boolean {
        if (beamBeats(direction, src, a, b)) return true
        if (beamBeats(direction, src, b, a)) return false
        return weighted(direction, src, a) < weighted(direction, src, b)
    }

    /** Whether the two overlap across [direction]: in the same row for left and right, the same column for up and down. */
    private fun inBeam(direction: Int, a: Rect, b: Rect) =
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) b.bottom > a.top && b.top < a.bottom
        else b.right > a.left && b.left < a.right

    /** In line beats out of line, unless the one out of line is much nearer (up and down only). */
    private fun beamBeats(direction: Int, src: Rect, a: Rect, b: Rect): Boolean {
        if (inBeam(direction, src, b) || !inBeam(direction, src, a)) return false
        val bFullyPast = when (direction) {
            View.FOCUS_LEFT -> src.left >= b.right
            View.FOCUS_RIGHT -> src.right <= b.left
            View.FOCUS_UP -> src.top >= b.bottom
            else -> src.bottom <= b.top
        }
        if (!bFullyPast) return true
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) return true
        val toNear = maxOf(0, if (direction == View.FOCUS_UP) src.top - a.bottom else a.top - src.bottom)
        val toFar = maxOf(1, if (direction == View.FOCUS_UP) src.top - b.top else b.bottom - src.bottom)
        return toNear < toFar
    }

    private fun weighted(direction: Int, src: Rect, dest: Rect): Long {
        val major = maxOf(0, when (direction) {
            View.FOCUS_LEFT -> src.left - dest.right
            View.FOCUS_RIGHT -> dest.left - src.right
            View.FOCUS_UP -> src.top - dest.bottom
            else -> dest.top - src.bottom
        }).toLong()
        val minor = (if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) src.centerY() - dest.centerY()
            else src.centerX() - dest.centerX()).toLong()
        return 13 * major * major + minor * minor
    }

    /** The widget the keypad is inside, moving among what can be pressed in it. */
    private var inside: View? = null

    /**
     * OK on a widget with things to press inside goes into it, highlighting the first; OK on
     * one with nothing inside presses it as usual. Returns true when it went in.
     */
    fun enterWidget(view: View?): Boolean {
        if (view == null || inside != null || view.isInTouchMode) return false
        val item = itemFor(view)?.takeIf { it.second === view }?.first ?: return false
        if (!item.isWidget) return false
        val parts = pressablesIn(view).ifEmpty { return false }
        parts.forEach { keyTarget(it, view) }
        view.isPressed = false
        inside = view
        parts.minByOrNull { rectInRoot(it).let { r -> r.top * 10_000 + r.left } }?.requestFocusFromTouch()
        return true
    }

    /** Back inside a widget: out to the widget itself. Returns false when not inside one. */
    fun leaveWidget(): Boolean {
        val widget = inside ?: return false
        inside = null
        // Gone from it already (a touch, say): Back does its usual thing.
        if (activity.currentFocus?.let { it.isInTouchMode || it !in pressablesIn(widget) } != false) return false
        widget.requestFocusFromTouch()
        return true
    }

    /**
     * Puts the keypad highlight on the top left app (or folder) of the page showing, or the
     * dock's first when the page has none, so it's there as soon as the home screen opens.
     * Returns false when there's no app, or the screen is in touch mode (where nothing shows a
     * highlight until a key is pressed).
     */
    fun focusFirstApp(): Boolean {
        val apps = workspace.items.filter { !it.isWidget && it.page == pages.currentPage }.sortedWith(compareBy({ it.y }, { it.x })) +
            workspace.items.filter { !it.isWidget && it.inDock }.sortedBy { it.x }
        val first = apps.firstNotNullOfOrNull { item -> views[item.id]?.takeIf { it.isFocusable && it.visibility == View.VISIBLE } }
        return first?.requestFocus() == true
    }

    /** Flips to the next or previous page, if there is one (holding right or left on the keypad). */
    fun flipPage(delta: Int) {
        val next = pages.currentPage + delta
        if (next in 0 until workspace.pageCount) pages.snapTo(next)
    }

    private fun itemsOn(page: Int): List<View> = workspace.items.filter { it.page == page }
        .mapNotNull { views[it.id] }.filter { it.isFocusable && it.visibility == View.VISIBLE }

    /** What can be pressed inside [widget], each only once (not what's inside a button too). */
    private fun pressablesIn(widget: View): List<View> {
        val found = mutableListOf<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v !== widget && v.isEnabled && v.hasOnClickListeners()) {
                found += v
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(widget)
        return found
    }

    /** Readies something inside [widget] for the keypad: it can take the highlight, shows it, and holding OK or Menu on it is holding the widget. */
    private fun keyTarget(view: View, widget: View) {
        if (view.getTag(R.id.key_target) != null) return
        view.setTag(R.id.key_target, true)
        view.isFocusable = true
        // Feather's own have their highlight (Ui.pressable, a layered drawable, or one of their
        // own); another app's widget's need one.
        if (view.foreground == null && view.background !is android.graphics.drawable.LayerDrawable) {
            view.foreground = Ui.focusRing(activity, Ui.RADIUS_M, 0xFFFFFFFF.toInt())
        }
        if (!view.isLongClickable) view.setOnLongClickListener { if (it.isInTouchMode) false else widget.performLongClick() }
    }

    fun rectInRoot(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun segmented(p: Palette, options: List<String>, selected: Int, onPick: (Int) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            // Buttons have 4dp side margins; this puts their outer edges in line with the headings.
            setPadding(dp(Ui.SPACE_XL - 4), 0, dp(Ui.SPACE_XL - 4), 0)
            options.forEachIndexed { i, label ->
                addView(TextView(activity).apply {
                    text = label
                    gravity = Gravity.CENTER
                    style(TextStyle.Label, if (i == selected) p.onAccent else p.text)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    background = Ui.rounded(activity, if (i == selected) p.accent else p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(activity, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }

    private fun dp(value: Int) = Ui.dp(activity, value)

    companion object {
        /** Space between the dock's edges and its icons, on every side. */
        private const val DOCK_GAP = 15
        /** Thickness of the targets along the edges while dragging. */
        /** Space between the page outline and the dock (and around the slivers). */
        private const val EDGE_GAP = Workspace.PAGE_TOP
        /** How close to a side edge (dp) the finger counts as over that side's sliver. */
        private const val EDGE_HIT = 28
        private const val FOLDER_NAME = "Folder"
        private const val FOLDER_COLS = 4
        private const val TOOLBAR_PADDING = 6
        /** How long a dragged app hovers over a spot before icons there make room. */
        private const val REORDER_DELAY = 180L
    }
}
