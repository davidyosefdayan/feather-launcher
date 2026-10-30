package app.featherlauncher

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One option in a menu. [drawable] is used for app shortcuts, which bring their own colored icon. */
class MenuItem(
    val label: String,
    val icon: Int,
    val danger: Boolean = false,
    val drawable: android.graphics.drawable.Drawable? = null,
    val action: () -> Unit,
)

/**
 * The launcher's two kinds of pop-up UI, drawn inside the activity's own window:
 * a menu that opens next to what was long-pressed, and a bottom sheet for
 * anything bigger. Both dim the screen, close on Back or a tap outside, and
 * keep keypad focus inside themselves.
 */
class Overlays(private val root: FrameLayout, private val palette: () -> Palette) {
    private var current: View? = null
    private var onClosed: (() -> Unit)? = null
    /** What had the keypad highlight before the overlay opened; it gets it back on closing. */
    private var focusBefore: View? = null
    var insets = Rect()

    /** Keyboard height; an open sheet rises above the keyboard. */
    var imeBottom = 0
        set(value) {
            if (field == value) return
            field = value
            val card = current?.findViewWithTag<View>(CARD) ?: return
            if (card.getTag(R.id.sheet) != true) return
            (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = maxOf(insets.bottom, value) + dp(Ui.SPACE_S)
            card.requestLayout()
        }

    val isShowing get() = current != null

    init {
        // A sheet rides up with the keyboard frame by frame, instead of jumping once it's open.
        root.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(
                insets: android.view.WindowInsets,
                running: MutableList<android.view.WindowInsetsAnimation>,
            ): android.view.WindowInsets {
                imeBottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                return insets
            }
        })
    }

    fun dismiss(): Boolean {
        val overlay = current ?: return false
        current = null
        // The keyboard goes with the sheet that was typed into.
        root.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(root.windowToken, 0)
        setUnderlyingFocusable(true)
        // With the keypad, the highlight goes back where it was, instead of vanishing or landing
        // on whatever Android finds first. (An option that moves it, like Move, does so after this.)
        focusBefore?.takeIf { it.isAttachedToWindow && it.isShown && !it.isInTouchMode }?.requestFocus()
        focusBefore = null
        val card = overlay.findViewWithTag<View>(CARD)
        overlay.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(overlay) }.start()
        if (card.tag == CARD && card.getTag(R.id.sheet) == true) {
            card.animate().translationY(card.height.toFloat() / 3).setDuration(120).start()
        }
        onClosed?.invoke()
        onClosed = null
        return true
    }

    /** A menu with a single group of options. */
    @JvmName("showSingleGroupMenu")
    fun showMenu(title: String?, items: List<MenuItem>, anchor: Rect, icon: Bitmap? = null, iconRes: Int = 0, info: (() -> Unit)? = null) =
        showMenu(title, listOf(items), anchor, icon, iconRes, info)

    /**
     * A menu that opens next to [anchor] (root coordinates), preferring above it.
     * Options come in [groups], each drawn as one rounded block. With a [title] the
     * menu gets a header (with [icon] if given) and, if [info] is set, a round info button.
     */
    fun showMenu(
        title: String?,
        groups: List<List<MenuItem>>,
        anchor: Rect,
        icon: Bitmap? = null,
        /** A line icon for the header instead of an app icon (a category's icon, say). */
        iconRes: Int = 0,
        info: (() -> Unit)? = null,
    ) {
        val p = palette()
        val ctx = root.context
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(MENU_PADDING), dp(MENU_PADDING), dp(MENU_PADDING), dp(MENU_PADDING))
        }
        // A menu taller than the screen (a small one, with many options) scrolls instead of
        // losing its last options off the edge; the keypad scrolls it as the highlight moves.
        val card = android.widget.ScrollView(ctx).apply {
            // Outer corner = group corner + padding, so the curves run parallel.
            background = Ui.rounded(ctx, p.surface, MENU_ROW_RADIUS + MENU_PADDING)
            elevation = dp(8).toFloat()
            clipToOutline = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
        if (title != null) column.addView(menuHeader(title, icon, iconRes, info, p))
        // Options sit straight on the menu; a thin line separates groups (and the header).
        groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
            if (i > 0 || title != null) column.addView(divider(p))
            group.forEach { column.addView(menuRow(it, p)) }
        }

        val margin = dp(Ui.SPACE_M)
        val gap = dp(Ui.SPACE_S)
        // Narrower on a screen too small for the usual width (a keypad phone at a large display size).
        val width = minOf(dp(244), root.width - 2 * margin)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height - insets.top - insets.bottom - 2 * margin, View.MeasureSpec.AT_MOST),
        )
        val height = card.measuredHeight
        val x = (anchor.centerX() - width / 2).coerceIn(margin, root.width - width - margin)
        val above = anchor.top - gap - height
        val y = if (above >= insets.top + margin) above else (anchor.bottom + gap).coerceAtMost(root.height - insets.bottom - height - margin)
        card.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y.coerceAtLeast(insets.top + margin)
        }
        card.pivotX = (anchor.centerX() - x).toFloat().coerceIn(0f, width.toFloat())
        card.pivotY = if (y == above) height.toFloat() else 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(DecelerateInterpolator(2f)).start()

        show(card, scrimAlpha = 0x66)
    }

    /** Icon and name, lined up with the options below; optional info button on the right. */
    private fun menuHeader(title: String, icon: Bitmap?, iconRes: Int, info: (() -> Unit)?, p: Palette): View {
        val ctx = root.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_HEIGHT)
            // The info button's right edge lines up with the option blocks below.
            setPadding(dp(MENU_ROW_INSET), 0, if (info != null) 0 else dp(MENU_ROW_INSET), 0)
            if (icon != null || iconRes != 0) {
                addView(ImageView(ctx).apply {
                    if (icon != null) {
                        setImageBitmap(icon)
                    } else {
                        setImageResource(iconRes)
                        imageTintList = android.content.res.ColorStateList.valueOf(p.text)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        val inset = dp((MENU_ICON_BOX - 22) / 2)
                        setPadding(inset, inset, inset, inset)
                    }
                }, LinearLayout.LayoutParams(dp(MENU_ICON_BOX), dp(MENU_ICON_BOX)).apply { marginEnd = dp(Ui.SPACE_M) })
            }
            addView(TextView(ctx).apply {
                text = title
                style(TextStyle.Label, p.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (info != null) {
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(p.textSecondary)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val inset = dp(9)
                    setPadding(inset, inset, inset, inset)
                    background = Ui.rounded(ctx, p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    contentDescription = "App info"
                    setOnClickListener {
                        dismiss()
                        info()
                    }
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            }
        }
    }

    private fun divider(p: Palette) = View(root.context).apply {
        setBackgroundColor(if (p.dark) 0x1AFFFFFF else 0x14000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(MENU_ROW_INSET), dp(MENU_GAP), dp(MENU_ROW_INSET), dp(MENU_GAP))
        }
    }

    private fun menuRow(item: MenuItem, p: Palette): View {
        val ctx = root.context
        val color = if (item.danger) p.danger else p.text
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(MENU_ROW_HEIGHT)
            setPadding(dp(MENU_ROW_INSET), 0, dp(MENU_ROW_INSET), 0)
            // Rounded highlight, inset from the menu edge by the menu's padding.
            background = Ui.pressable(ctx, MENU_ROW_RADIUS)
            isFocusable = true
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                if (item.drawable != null) {
                    // App shortcut icons keep their own colors.
                    setImageDrawable(item.drawable)
                    val inset = dp((MENU_ICON_BOX - 24) / 2)
                    setPadding(inset, inset, inset, inset)
                } else {
                    setImageResource(item.icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(color)
                    val inset = dp((MENU_ICON_BOX - 20) / 2)
                    setPadding(inset, inset, inset, inset)
                }
                layoutParams = LinearLayout.LayoutParams(dp(MENU_ICON_BOX), dp(MENU_ICON_BOX))
            })
            addView(TextView(ctx).apply {
                text = item.label
                style(TextStyle.Body, color)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(Ui.SPACE_M)
                }
            })
            setOnClickListener {
                dismiss()
                item.action()
            }
        }
    }

    /**
     * Shows [content] in a sheet rising from the bottom. [scrollable] is the view that
     * scrolls inside it, so a downward swipe only closes the sheet once it's at the top.
     */
    fun showSheet(
        title: String?,
        content: View,
        scrollable: View? = null,
        fill: Boolean = false,
        onClosed: (() -> Unit)? = null,
    ) {
        val p = palette()
        val ctx = root.context
        val card = GestureLayout(ctx, null).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, p.surface, Ui.RADIUS_XL)
            elevation = dp(16).toFloat()
            clipToOutline = true
            setTag(R.id.sheet, true)
            canDrag = { dy -> dy > 0 && scrollable?.canScrollVertically(-1) != true }
            onSwipeDown = { dismiss() }
        }
        card.addView(View(ctx).apply {
            background = Ui.rounded(ctx, p.textTertiary, Ui.RADIUS_PILL)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        })
        if (title != null) {
            card.addView(TextView(ctx).apply {
                text = title
                style(TextStyle.Title, p.text)
                setPadding(dp(Ui.SPACE_XL), dp(14), dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
            })
        }
        val margin = dp(Ui.SPACE_S)
        val maxHeight = root.height - insets.top - insets.bottom - dp(48)
        // Lists fill the available height; anything else is measured at its real size.
        val height = if (fill) {
            maxHeight
        } else {
            card.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.measure(
                View.MeasureSpec.makeMeasureSpec(root.width - 2 * margin, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
            )
            card.removeView(content)
            card.measuredHeight
        }
        // Content that fits keeps its own height, so a line added later (a status, say) makes the
        // sheet taller instead of pushing what's below out of sight. Taller content scrolls in a fixed height.
        val fits = !fill && height < maxHeight
        card.addView(content, if (fits) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (fits) ViewGroup.LayoutParams.WRAP_CONTENT else height).apply {
            gravity = Gravity.BOTTOM
            setMargins(margin, 0, margin, insets.bottom + margin)
        }
        card.translationY = maxHeight.toFloat() / 2
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()

        this.onClosed = onClosed
        show(card, scrimAlpha = 0x80)
    }

    /** A sheet with one text field and a Save button (or [action]), for naming things or searching. */
    /** Like [showInput], but saving an empty field is allowed (it clears the value). */
    fun showInputAllowingEmpty(title: String, value: String, hint: String, onSave: (String) -> Unit) =
        showInput(title, value, hint, allowEmpty = true, onSave = onSave)

    fun showInput(title: String, value: String, hint: String, allowEmpty: Boolean = false, action: String = "Save", onSave: (String) -> Unit) {
        val p = palette()
        val ctx = root.context
        val field = android.widget.EditText(ctx).apply {
            setText(value)
            selectAll()
            setSingleLine()
            this.hint = hint
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            style(TextStyle.Body, p.text)
            setHintTextColor(p.textTertiary)
            background = Ui.rounded(ctx, p.surfaceHigh, Ui.RADIUS_M)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
        }
        fun save() {
            val text = field.text.toString().trim()
            if (text.isEmpty() && !allowEmpty) return
            dismiss()
            onSave(text)
        }
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.END
                addView(Components.pillButton(ctx, action, p.accent, p.onAccent) { save() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ui.SPACE_M)
            })
        }
        showSheet(title, column)
        field.requestFocus()
        field.post {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** A card in the middle of the screen, for folders. */
    /** [backdrop] is the color behind the card (a dim by default). */
    fun showPanel(card: View, backdrop: Int = 0x80000000.toInt()) {
        val margin = dp(Ui.SPACE_L)
        card.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(margin, insets.top + margin, margin, insets.bottom + margin)
        }
        card.elevation = dp(12).toFloat()
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
        show(card, scrimColor = backdrop)
    }

    private fun show(card: View, scrimAlpha: Int = 0, scrimColor: Int = scrimAlpha shl 24) {
        // One overlay replacing another keeps what had the highlight before the first.
        if (current == null) focusBefore = root.findFocus()
        if (current != null) {
            root.removeView(current)
            current = null
        }
        card.tag = CARD
        card.isClickable = true
        // Clickable views become focusable by default; only the options should take keypad focus.
        card.isFocusable = false
        val overlay = FrameLayout(root.context).apply {
            setBackgroundColor(scrimColor)
            alpha = 0f
            isClickable = true
            isFocusable = false
            setOnClickListener { dismiss() }
            addView(card)
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.animate().alpha(1f).setDuration(140).start()
        current = overlay
        setUnderlyingFocusable(false)
        // With the keypad, start on the first option.
        // (Not the round button beside a title, like app info: the options are wider than they're tall.)
        if (!card.isInTouchMode) card.post {
            val options = ArrayList<View>().also { card.addFocusables(it, View.FOCUS_DOWN) }
            (options.firstOrNull { it.width > it.height * 3 / 2 } ?: options.firstOrNull())?.requestFocus()
        }
    }

    private fun setUnderlyingFocusable(focusable: Boolean) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === current) continue
            (child as? ViewGroup)?.descendantFocusability =
                if (focusable) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun dp(value: Number) = Ui.dp(root.context, value)

    companion object {
        private const val CARD = "overlay_card"

        // Menu geometry, kept together so the pieces stay in proportion.
        private const val MENU_PADDING = 8
        private const val MENU_GAP = 4
        private const val MENU_ROW_RADIUS = 14
        private const val MENU_ROW_HEIGHT = 48
        private const val MENU_ROW_INSET = 12
        private const val MENU_ICON_BOX = 28
    }
}
