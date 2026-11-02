package app.featherlauncher

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** What tapping a contact (or one of its buttons) does. */
enum class ContactAction { OPEN, CALL, TEXT, EMAIL }

/**
 * The app list's rows: section titles, rows of icon tiles (grid), or one app per row (list).
 * Each app tile is focusable on its own, so the keypad moves tile to tile.
 */
class DrawerAdapter(
    private val context: Context,
    private val onContact: (Contacts.Contact, ContactAction) -> Unit = { _, _ -> },
    /** Opens a number to call, setting, shortcut or file. */
    private val onFound: (Found) -> Unit = { it.open() },
    private val icon: (AppEntry) -> Bitmap?,
    private val digitFor: (AppEntry) -> Int?,
    /** Locked by an app limit right now: drawn gray with a lock. */
    private val isLocked: (AppEntry) -> Boolean,
    /** Installed and not opened yet: a dot on the icon. */
    private val isNew: (AppEntry) -> Boolean,
    /** Notifications showing: a red badge with the count. */
    private val badge: (AppEntry) -> Int = { 0 },
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
) : BaseAdapter() {
    sealed class Row {
        /** A section title, with an optional action on the right (like "Show more"). */
        class Title(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
        class Person(val contact: Contacts.Contact) : Row()
        class Notice(val text: String, val action: String, val onAction: () -> Unit) : Row()
        class Tiles(val apps: List<AppEntry>) : Row()
        class Line(val app: AppEntry) : Row()
        /** A number to call, setting, shortcut or file found by Smart Search. */
        class Result(val found: Found) : Row()
        /** The thin line between search sections. */
        object Divider : Row()
    }

    /** What's been typed in search; matching letters in names are picked out. */
    var query = ""

    var palette: Palette? = null
    /** The app being dragged: its tile stays in place but empty, as the gap it will drop into. */
    var dragging: AppEntry? = null
    var rows: List<Row> = emptyList()
        private set
    /** What OK/Enter opens: the Top match, or else the first search result. */
    var primary: (() -> Unit)? = null
        private set

    fun set(rows: List<Row>, primary: (() -> Unit)?) {
        this.rows = rows
        this.primary = primary
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 7
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Title -> 0
        is Row.Tiles -> 1
        is Row.Line -> 2
        is Row.Person -> 3
        is Row.Notice -> 4
        is Row.Result -> 5
        Row.Divider -> 6
    }
    // Rows themselves aren't selectable; the tiles inside them are.
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val p = palette ?: return View(context)
        return when (val row = rows[position]) {
            is Row.Title -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_XS))
                addView(TextView(context).apply {
                    text = row.text
                    style(TextStyle.Section, p.onWallSecondary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (row.action != null) {
                    addView(TextView(context).apply {
                        text = row.action
                        style(TextStyle.Label, p.onWall)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(Ui.SPACE_M), dp(6), dp(Ui.SPACE_M), dp(6))
                        background = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = p.onWall)
                        isFocusable = true
                        setOnClickListener { row.onAction?.invoke() }
                    })
                }
            }
            is Row.Person -> person(row.contact, p)
            is Row.Notice -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S))
                addView(TextView(context).apply {
                    text = row.text
                    style(TextStyle.Secondary, p.onWallSecondary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Components.pillButton(context, row.action, p.accent, p.onAccent) { row.onAction() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
            }
            // App rows are used again as the list scrolls and changes, with new apps in them; only
            // new colors make them anew.
            is Row.Tiles -> {
                val view = (convertView as? LinearLayout)?.takeIf { it.getTag(R.id.drawer_palette) === p }
                    ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setTag(R.id.drawer_palette, p)
                        repeat(COLS) { addView(tile(p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                    }
                for (i in 0 until COLS) bindTile(view.getChildAt(i) as LinearLayout, row.apps.getOrNull(i), p)
                view
            }
            is Row.Line -> {
                val view = (convertView as? LinearLayout)?.takeIf { it.getTag(R.id.drawer_palette) === p }
                    ?: line(p).apply { setTag(R.id.drawer_palette, p) }
                bindLine(view, row.app, p)
                view
            }
            is Row.Result -> result(row.found, p)
            Row.Divider -> View(context).apply {
                // The text's color, faint, so it shows on light and dark wallpapers alike.
                setBackgroundColor((p.onWall and 0xFFFFFF) or 0x33000000)
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            }.let { line ->
                // Inset to line up with the section titles and rows.
                android.widget.FrameLayout(context).apply {
                    setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_L), 0)
                    addView(line, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                }
            }
        }
    }

    /** An app tile, without its app yet ([bindTile]). */
    private fun tile(p: Palette) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        // The notification badge hangs off the icon's corner.
        clipChildren = false
        clipToPadding = false
        foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
        addView(ImageView(context), LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(p.onWall)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Puts [app] in [tile]; with none, the tile is an empty space at the end of the last row. */
    private fun bindTile(tile: LinearLayout, app: AppEntry?, p: Palette) {
        tile.animate().cancel()
        tile.translationX = 0f
        tile.translationY = 0f
        tile.tag = app
        tile.visibility = if (app == null) View.INVISIBLE else View.VISIBLE
        tile.isFocusable = app != null
        if (app == null) {
            tile.setOnClickListener(null)
            tile.setOnLongClickListener(null)
            tile.isClickable = false
            tile.isLongClickable = false
            return
        }
        val image = tile.getChildAt(0) as ImageView
        image.setImageBitmap(icon(app))
        IconBadges.apply(image, isLocked(app), isNew(app), 54, badge(app))
        tile.alpha = if (app.key == dragging?.key) 0f else 1f
        (tile.getChildAt(1) as TextView).text = SmartSearch.highlight(app.label, query, p.onWall)
        tile.setOnClickListener { onClick(app, image) }
        tile.setOnLongClickListener { onLongClick(app, it); true }
    }

    /** An app on its own row (the list look), without its app yet ([bindLine]). */
    private fun line(p: Palette) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        clipChildren = false
        clipToPadding = false
        setPadding(dp(Ui.SPACE_M), 0, dp(Ui.SPACE_L), 0)
        foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
        isFocusable = true
        addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(context).apply {
            style(TextStyle.Body, p.onWall)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
        addView(TextView(context).apply { style(TextStyle.Label, p.onWallSecondary) })
    }

    private fun bindLine(row: LinearLayout, app: AppEntry, p: Palette) {
        row.animate().cancel()
        row.translationX = 0f
        row.translationY = 0f
        row.tag = app
        row.alpha = if (app.key == dragging?.key) 0f else 1f
        val image = row.getChildAt(0) as ImageView
        image.setImageBitmap(icon(app))
        IconBadges.apply(image, isLocked(app), isNew(app), 40, badge(app))
        (row.getChildAt(1) as TextView).text = SmartSearch.highlight(app.label, query, p.onWall)
        val digit = digitFor(app)
        (row.getChildAt(2) as TextView).apply {
            text = digit?.toString()
            visibility = if (digit == null) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { onClick(app, image) }
        row.setOnLongClickListener { onLongClick(app, it); true }
    }

    /** A number to call, setting, shortcut or file: icon, name with matches picked out, and what it is. */
    private fun result(found: Found, p: Palette): View {
        val size = dp(44)
        if (found.drawable == null) found.loadDrawable?.let { found.drawable = it() }
        val icon = ImageView(context).apply {
            when {
                found.bitmap != null -> {
                    setImageBitmap(found.bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                found.drawable != null -> setImageDrawable(found.drawable)
                else -> {
                    setImageResource(found.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
                    scaleType = ImageView.ScaleType.CENTER
                }
            }
            if (found.drawable == null) {
                background = Ui.rounded(context, 0x1FFFFFFF, Ui.RADIUS_M)
                clipToOutline = true
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = SmartSearch.highlight(found.title, query, p.onWall)
                    style(TextStyle.Body, p.onWall)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (found.subtitle != null) {
                    addView(TextView(context).apply {
                        text = found.subtitle
                        style(TextStyle.Secondary, p.onWallSecondary)
                        maxLines = 1
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
            setOnClickListener { onFound(found) }
        }
    }

    /** A contact: picture or initial, name and number, and buttons to email (if they have an address), text and call. */
    private fun person(contact: Contacts.Contact, p: Palette): View {
        val size = dp(44)
        val avatar: View = if (contact.photo != null) {
            ImageView(context).apply {
                photo(this, contact.photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(context, p.folderFill, Ui.RADIUS_M)
                clipToOutline = true
            }
        } else {
            TextView(context).apply {
                text = contact.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
                gravity = Gravity.CENTER
                style(TextStyle.Title, p.onWall)
                background = Ui.rounded(context, 0x1FFFFFFF, Ui.RADIUS_M)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_S), 0)
            minimumHeight = dp(60)
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            // The Call key calls whoever is highlighted.
            tag = contact
            addView(avatar, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = SmartSearch.highlight(contact.name, query, p.onWall)
                    style(TextStyle.Body, p.onWall)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    // Names win in Contacts.search; only pick out the number for a number match.
                    text = if (Search.isT9(query) && query.length >= 3 &&
                        Search.score(query, contact.digitWords) == null) {
                        SmartSearch.highlight(contact.number, query, p.onWall, p.onWallSecondary)
                    } else contact.number
                    style(TextStyle.Secondary, p.onWallSecondary)
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
            // Call stays at the far right, where it's always been; the others line up before it.
            if (contact.email != null) addView(contactButton(R.drawable.ic_cat_mail, "Email ${contact.name}", p) { onContact(contact, ContactAction.EMAIL) })
            addView(contactButton(R.drawable.ic_message, "Text ${contact.name}", p) { onContact(contact, ContactAction.TEXT) })
            addView(contactButton(R.drawable.ic_cat_phone, "Call ${contact.name}", p) { onContact(contact, ContactAction.CALL) })
            setOnClickListener { onContact(contact, ContactAction.OPEN) }
        }
    }

    /**
     * Shows a contact's picture in [view]: straight away once it's been seen, else read in the
     * background, so a row of contacts doesn't hold up typing while their pictures are read.
     */
    private fun photo(view: ImageView, uri: android.net.Uri) {
        view.setTag(R.id.drawer_photo, uri)
        photos.get(uri)?.let { return view.setImageBitmap(it) }
        photoIo.execute {
            val bitmap = runCatching { context.contentResolver.openInputStream(uri)?.use(android.graphics.BitmapFactory::decodeStream) }.getOrNull()
                ?: return@execute
            main.post {
                photos.put(uri, bitmap)
                if (view.getTag(R.id.drawer_photo) == uri) view.setImageBitmap(bitmap)
            }
        }
    }

    private val photos = android.util.LruCache<android.net.Uri, Bitmap>(PHOTOS)
    private val photoIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun contactButton(icon: Int, description: String, p: Palette, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = p.onWall)
        isFocusable = true
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    companion object {
        const val COLS = 4
        /** Contacts' pictures kept in memory (small thumbnails). */
        private const val PHOTOS = 60
    }
}
