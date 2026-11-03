package app.featherlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The shared pieces sheets and the settings page are built from, so they all look the same. */
object Components {
    /** Heading over a group of controls, in line with the controls' outer edge. */
    fun sectionLabel(ctx: Context, p: Palette, text: String) = TextView(ctx).apply {
        this.text = text
        style(TextStyle.Section, p.textSecondary)
        setPadding(dp(ctx, Ui.SPACE_XL), dp(ctx, Ui.SPACE_L + 4), dp(ctx, Ui.SPACE_XL), dp(ctx, Ui.SPACE_S))
    }

    /** A row of pill buttons, one selected. */
    fun segmented(ctx: Context, p: Palette, options: List<String>, selected: Int, onPick: (Int) -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Buttons have 4dp side margins; this puts their outer edges in line with the headings.
            setPadding(dp(ctx, Ui.SPACE_XL - 4), 0, dp(ctx, Ui.SPACE_XL - 4), 0)
            options.forEachIndexed { i, label ->
                addView(TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    style(TextStyle.Label, if (i == selected) p.onAccent else p.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    background = Ui.rounded(ctx, if (i == selected) p.accent else p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0) })
            }
        }

    /** A sideways-scrolling row of [tiles]; tile edges line up with the headings. */
    fun tileRow(ctx: Context, tiles: List<View>): View = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, Ui.SPACE_XL), 0, dp(ctx, Ui.SPACE_XL), 0)
            tiles.forEach(::addView)
        })
    }

    /** A selectable tile with a picture on top and a caption under it. */
    fun tile(ctx: Context, p: Palette, label: String, selected: Boolean, picture: View, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, Ui.SPACE_S), dp(ctx, Ui.SPACE_S), dp(ctx, Ui.SPACE_S), dp(ctx, Ui.SPACE_S))
            background = if (selected) Ui.rounded(ctx, p.accentSurface, Ui.RADIUS_M) else null
            foreground = Ui.pressable(ctx, Ui.RADIUS_M)
            isFocusable = true
            addView(picture, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
            addView(TextView(ctx).apply {
                text = label
                style(TextStyle.Caption, if (selected) p.text else p.textSecondary)
                setPadding(0, dp(ctx, Ui.SPACE_S), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 76), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 4) }
            setOnClickListener { onClick() }
        }

    fun swatch(ctx: Context, color: Int): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** Rows grouped in one rounded block, like the menus. */
    fun group(ctx: Context, p: Palette, rows: List<View>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(ctx, p.surfaceHigh, GROUP_RADIUS)
        clipToOutline = true
        rows.forEach { row ->
            // A row's highlight (touch and keypad) sits inside the group with its own rounded
            // corners, the group's minus the gap, instead of being cut square by the group's edge.
            if (row.background is android.graphics.drawable.LayerDrawable) {
                // A background with insets would replace the row's padding, so it's kept.
                val (l, t, r, b) = listOf(row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom)
                row.background = android.graphics.drawable.InsetDrawable(
                    Ui.pressable(ctx, GROUP_RADIUS - ROW_INSET), dp(ctx, ROW_INSET))
                row.setPadding(l, t, r, b)
            }
            addView(row)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(ctx, Ui.SPACE_L), 0, dp(ctx, Ui.SPACE_L), 0)
        }
    }

    /**
     * A tappable row: icon, label, and optionally a [value] and a chevron on the right.
     * [inset] is the side padding: smaller inside a [group], whose own margin adds the rest.
     */
    fun row(
        ctx: Context,
        p: Palette,
        icon: Int,
        label: String,
        value: String? = null,
        color: Int = p.text,
        chevron: Boolean = false,
        inset: Int = Ui.SPACE_XL,
        /** Shown before the value: a color dot or a shape. */
        trailing: View? = null,
        /** A second line under the label, for values too long to sit on the right. */
        subtitle: String? = null,
        onClick: () -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, ROW_HEIGHT)
        setPadding(dp(ctx, inset), 0, dp(ctx, inset - if (chevron) 4 else 0), 0)
        background = Ui.pressable(ctx, 0)
        isFocusable = true
        addView(ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (color == p.text) p.textSecondary else color)
        }, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, Ui.SPACE_M), 0, dp(ctx, Ui.SPACE_M))
            addView(TextView(ctx).apply {
                text = label
                style(TextStyle.Body, color)
            })
            if (subtitle != null) addView(TextView(ctx).apply {
                text = subtitle
                style(TextStyle.Secondary, p.textSecondary)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, Ui.SPACE_XS), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, Ui.SPACE_L) })
        if (trailing != null) {
            addView(trailing, LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply { marginEnd = dp(ctx, Ui.SPACE_S) })
        }
        if (value != null) {
            addView(TextView(ctx).apply {
                text = value
                style(TextStyle.Secondary, p.textSecondary)
                maxLines = 1
                setPadding(dp(ctx, Ui.SPACE_S), 0, 0, 0)
            })
        }
        if (chevron) {
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(p.textTertiary)
            }, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply { marginStart = dp(ctx, 4) })
        }
        setOnClickListener { onClick() }
    }

    /** A row with an on/off switch; tapping anywhere on the row flips it. */
    fun toggleRow(ctx: Context, p: Palette, icon: Int, label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = FeatherSwitch(ctx, p, checked)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, ROW_HEIGHT)
            setPadding(dp(ctx, Ui.SPACE_L), 0, dp(ctx, Ui.SPACE_M), 0)
            background = Ui.pressable(ctx, 0)
            isFocusable = true
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.textSecondary)
            }, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
            addView(TextView(ctx).apply {
                text = label
                style(TextStyle.Body, p.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, Ui.SPACE_L) })
            addView(switch, LinearLayout.LayoutParams(dp(ctx, FeatherSwitch.WIDTH), dp(ctx, FeatherSwitch.HEIGHT)).apply { marginStart = dp(ctx, Ui.SPACE_M) })
            setOnClickListener {
                switch.checked = !switch.checked
                onChange(switch.checked)
            }
        }
    }

    /** An app with an action on the right, for lists like hidden apps. */
    fun appRow(ctx: Context, p: Palette, icon: Bitmap?, label: String, action: String, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, ROW_HEIGHT + 4)
            setPadding(dp(ctx, Ui.SPACE_XL), 0, dp(ctx, Ui.SPACE_XL), 0)
            background = Ui.pressable(ctx, 0)
            isFocusable = true
            addView(ImageView(ctx).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
            addView(TextView(ctx).apply {
                text = label
                style(TextStyle.Body, p.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, Ui.SPACE_L) })
            addView(TextView(ctx).apply {
                text = action
                style(TextStyle.Label, p.accent)
            })
            setOnClickListener { onClick() }
        }

    fun pillButton(ctx: Context, text: String, fill: Int, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        style(TextStyle.Label, color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(ctx, Ui.SPACE_L + 4), 0, dp(ctx, Ui.SPACE_L + 4), 0)
        background = Ui.rounded(ctx, fill, Ui.RADIUS_PILL)
        foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * A sheet listing [options] with a check next to the current one; picking one closes it.
     * Each option can have a small preview on the left (a color dot, a shape).
     */
    fun choiceSheet(
        ctx: Context,
        p: Palette,
        overlays: Overlays,
        title: String,
        options: List<Pair<String, View?>>,
        selected: Int,
        footer: View? = null,
        onPick: (Int) -> Unit,
    ) {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, Ui.SPACE_M))
        }
        options.forEachIndexed { i, (label, preview) ->
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ctx, ROW_HEIGHT)
                setPadding(dp(ctx, Ui.SPACE_XL), 0, dp(ctx, Ui.SPACE_XL), 0)
                background = Ui.pressable(ctx, 0)
                isFocusable = true
                if (preview != null) {
                    addView(preview, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply { marginEnd = dp(ctx, Ui.SPACE_L) })
                }
                addView(TextView(ctx).apply {
                    text = label
                    style(TextStyle.Body, p.text)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(p.accent)
                    visibility = if (i == selected) View.VISIBLE else View.INVISIBLE
                }, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
                setOnClickListener {
                    overlays.dismiss()
                    onPick(i)
                }
            })
        }
        footer?.let(column::addView)
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet(title, scroll, scrollable = scroll)
        // Long lists (times of day) open at the current choice, a little below the top.
        if (selected > 0) scroll.post {
            column.getChildAt(selected)?.let { scroll.scrollTo(0, (it.top - 2 * it.height).coerceAtLeast(0)) }
        }
    }

    /**
     * A sheet of [options] that can each be checked or not; changes apply as they're tapped,
     * and [onClosed] runs when the sheet goes away.
     */
    fun multiChoiceSheet(
        ctx: Context,
        p: Palette,
        overlays: Overlays,
        title: String,
        options: List<Pair<String, View?>>,
        selected: Set<Int>,
        fill: Boolean = false,
        onToggle: (index: Int, checked: Boolean) -> Unit,
        onClosed: () -> Unit,
    ) {
        val checked = selected.toMutableSet()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, Ui.SPACE_M))
        }
        fun addRow(i: Int) {
            val (label, preview) = options[i]
            val box = checkBox(ctx, p, i in checked)
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ctx, ROW_HEIGHT)
                setPadding(dp(ctx, Ui.SPACE_XL), 0, dp(ctx, Ui.SPACE_XL), 0)
                background = Ui.pressable(ctx, 0)
                isFocusable = true
                if (preview != null) {
                    addView(preview, LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30)).apply { marginEnd = dp(ctx, Ui.SPACE_L) })
                }
                addView(TextView(ctx).apply {
                    text = label
                    style(TextStyle.Body, p.text)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(box, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)))
                setOnClickListener {
                    val now = i !in checked
                    if (now) checked += i else checked -= i
                    box.isSelected = now
                    onToggle(i, now)
                }
            })
        }
        // A long list (every app) opens with the rows that show, and the rest follow a few at a
        // time just after, rather than all being made before the sheet can open.
        options.indices.take(ROWS_FIRST).forEach(::addRow)
        fun addMore(from: Int) {
            if (from >= options.size) return
            column.post {
                (from until minOf(from + ROWS_FIRST, options.size)).forEach(::addRow)
                addMore(from + ROWS_FIRST)
            }
        }
        addMore(ROWS_FIRST)
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet(title, scroll, scrollable = scroll, fill = fill, onClosed = onClosed)
    }

    /** A rounded check box: an outline when off, filled with the accent and a check when on. */
    private fun checkBox(ctx: Context, p: Palette, on: Boolean) = ImageView(ctx).apply {
        val off = Ui.rounded(ctx, 0, 6).apply { setStroke(dp(ctx, 2), p.textTertiary) }
        val onBg = Ui.rounded(ctx, p.accent, 6)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), onBg)
            addState(intArrayOf(), off)
        }
        setImageDrawable(android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), ctx.getDrawable(R.drawable.ic_check)!!.mutate().apply { setTint(p.onAccent) })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
        })
        val inset = dp(ctx, 3)
        setPadding(inset, inset, inset, inset)
        isSelected = on
        isDuplicateParentStateEnabled = false
    }

    /**
     * A sheet with hour and minute wheels (and AM/PM on a 12 hour phone) and a Done button.
     * [minutes] and the result are minutes after midnight.
     */
    fun timeSheet(ctx: Context, p: Palette, overlays: Overlays, title: String, minutes: Int, onDone: (Int) -> Unit) {
        val h24 = android.text.format.DateFormat.is24HourFormat(ctx)
        val hour = minutes / 60
        val hours = if (h24) WheelPicker(ctx, (0..23).map { "%02d".format(it) }, hour, true, p.text, p.accent)
            else WheelPicker(ctx, (1..12).map { it.toString() }, (hour + 11) % 12, true, p.text, p.accent)
        val mins = WheelPicker(ctx, (0..59).map { "%02d".format(it) }, minutes % 60, true, p.text, p.accent)
        val half = if (h24) null else WheelPicker(ctx, listOf("AM", "PM"), hour / 12, false, p.text, p.accent)
        val wheels = android.widget.FrameLayout(ctx).apply {
            // The band behind the middle row marks what's picked.
            addView(View(ctx).apply { background = Ui.rounded(ctx, p.surfaceHigh, Ui.RADIUS_M) },
                android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, WheelPicker.ROW_HEIGHT), Gravity.CENTER_VERTICAL))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(hours, LinearLayout.LayoutParams(dp(ctx, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(ctx).apply {
                    text = ":"
                    style(TextStyle.Title, p.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                })
                addView(mins, LinearLayout.LayoutParams(dp(ctx, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
                if (half != null) addView(half, LinearLayout.LayoutParams(dp(ctx, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, Ui.SPACE_XL), 0, dp(ctx, Ui.SPACE_XL), dp(ctx, Ui.SPACE_L))
            addView(wheels)
            addView(pillButton(ctx, "Done", p.accent, p.onAccent) {
                // On a 12 hour wheel, row 0 is "1" and row 11 is "12".
                val hourOfDay = if (half == null) hours.value else (hours.value + 1) % 12 + 12 * half.value
                overlays.dismiss()
                onDone(hourOfDay * 60 + mins.value)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)).apply { topMargin = dp(ctx, Ui.SPACE_L) })
        }
        overlays.showSheet(title, column)
        hours.requestFocus()
    }

    /**
     * The days of the week as a row of round buttons, filled when chosen; tapping flips one.
     * [days] uses [java.util.Calendar] day numbers.
     */
    fun dayPicker(ctx: Context, p: Palette, days: Set<Int>, onChange: (Set<Int>) -> Unit): View {
        val chosen = days.toMutableSet()
        val letters = java.text.DateFormatSymbols.getInstance().weekdays
        val first = java.util.Calendar.getInstance().firstDayOfWeek
        val order = (0 until 7).map { (first - 1 + it) % 7 + 1 }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, Ui.SPACE_M), dp(ctx, Ui.SPACE_M), dp(ctx, Ui.SPACE_M), dp(ctx, Ui.SPACE_M))
            order.forEach { day ->
                val cell = android.widget.FrameLayout(ctx)
                addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                cell.addView(TextView(ctx).apply {
                    text = letters[day].take(1)
                    gravity = Gravity.CENTER
                    contentDescription = letters[day]
                    isFocusable = true
                    fun paint() {
                        val on = day in chosen
                        style(TextStyle.Label, if (on) p.onAccent else p.textSecondary)
                        background = Ui.rounded(ctx, if (on) p.accent else p.surface, Ui.RADIUS_PILL)
                        foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    }
                    paint()
                    setOnClickListener {
                        if (day in chosen) chosen -= day else chosen += day
                        paint()
                        onChange(chosen.toSet())
                    }
                }, android.widget.FrameLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38), Gravity.CENTER))
            }
        }
    }

    /** Small explanatory text under a group, in line with the group's rows. */
    fun footnote(ctx: Context, p: Palette, text: CharSequence) = TextView(ctx).apply {
        this.text = text
        style(TextStyle.Secondary, p.textTertiary)
        setLineSpacing(0f, 1.2f)
        // Group margin plus row inset, so the text starts where the row icons do.
        setPadding(dp(ctx, 2 * Ui.SPACE_L), dp(ctx, Ui.SPACE_S), dp(ctx, 2 * Ui.SPACE_L), 0)
    }

    fun dot(ctx: Context, color: Int): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** The outline of an icon shape, as a plain filled glyph (no app icon inside). */
    fun shapeGlyph(ctx: Context, shape: IconShape, color: Int): View = object : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val size = minOf(width, height).toFloat()
            canvas.save()
            canvas.translate((width - size) / 2, (height - size) / 2)
            canvas.drawPath(shape.path(size), paint)
            canvas.restore()
        }
    }

    /** A bar of every hue; dragging along it picks a color, shown in [preview]. */
    fun hueSlider(ctx: Context, initial: Int, onChange: (Int) -> Unit): View = object : View(ctx) {
        private val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
        private var hue = hsv[0]
        private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(ctx, 3).toFloat()
            color = android.graphics.Color.WHITE
        }
        private fun colorAt(h: Float) = android.graphics.Color.HSVToColor(floatArrayOf(h, 0.6f, 0.9f))

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            bar.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f,
                IntArray(13) { colorAt(it * 30f) }, null, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            val track = dp(ctx, 12).toFloat()
            canvas.drawRoundRect(r, r - track / 2, width - r, r + track / 2, track / 2, track / 2, bar)
            val x = r + (width - 2 * r) * hue / 360f
            knob.color = colorAt(hue)
            canvas.drawCircle(x, r, r - dp(ctx, 3), knob)
            canvas.drawCircle(x, r, r - dp(ctx, 3), ring)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            val r = height / 2f
            hue = ((event.x - r) / (width - 2 * r) * 360f).coerceIn(0f, 359f)
            invalidate()
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) onChange(colorAt(hue))
            return true
        }
    }

    private fun dp(ctx: Context, value: Int) = Ui.dp(ctx, value)

    const val GROUP_RADIUS = 18
    /** Gap between a group's edge and a row's highlight. */
    private const val ROW_INSET = 4
    const val ROW_HEIGHT = 54
    /** How many rows of a long checklist are made before it opens, and how many at a time after. */
    private const val ROWS_FIRST = 20
}
