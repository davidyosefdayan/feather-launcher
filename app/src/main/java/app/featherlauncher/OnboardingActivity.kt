package app.featherlauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The first run: a welcome, the one thing worth asking up front (being the home screen), how
 * to arrange apps, and how to get around. Everything else is asked for later, when the feature
 * that needs it is first used.
 */
class OnboardingActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private var insets = Rect()
    private var step = 0

    private val steps: List<() -> View> = listOf(::welcome, ::homeScreen, ::arrange, ::getAround, ::done)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        prefs = Prefs(this)
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        root.setOnApplyWindowInsetsListener { _, w ->
            val bars = w.getInsets(WindowInsets.Type.systemBars())
            val next = Rect(bars.left, bars.top, bars.right, bars.bottom)
            if (next != insets) {
                insets = next
                show(step, animate = false)
            }
            w
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        show(0, animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Back from choosing the home app: move on once it's done.
        if (step == 1 && isHome()) next()
    }

    private fun back() {
        if (step > 0) show(step - 1, animate = true, forward = false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun next() {
        var to = step + 1
        // Already the home screen: nothing to ask.
        if (to == 1 && isHome()) to++
        if (to >= steps.size) return finishOnboarding()
        show(to, animate = true)
    }

    private fun finishOnboarding() {
        prefs.onboarded = true
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun show(index: Int, animate: Boolean, forward: Boolean = true) {
        step = index
        val page = steps[index]()
        val old = (0 until root.childCount).map(root::getChildAt)
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!animate) {
            old.forEach(root::removeView)
        } else {
            val shift = dp(40f) * if (forward) 1 else -1
            page.alpha = 0f
            page.translationX = shift
            page.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
            old.forEach { v ->
                v.animate().alpha(0f).translationX(-shift).setDuration(180).withEndAction { root.removeView(v) }.start()
            }
        }
        page.post { page.findViewWithTag<View>(PRIMARY)?.requestFocus() }
    }

    // region Pages

    private fun welcome() = page(
        art = appIcon(),
        title = "Welcome to Feather Launcher",
        body = "A light, fast home screen that keeps your phone calm and simple.",
        primary = "Get started" to ::next,
    )

    private fun homeScreen() = page(
        art = symbol(R.drawable.ic_home),
        title = "Make it your home screen",
        body = "So the Home button brings you here. You can switch back to your old one any time in your phone's settings.",
        primary = "Set as home screen" to {
            runCatching {
                @Suppress("DEPRECATION")
                startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), 0)
            }.onFailure { next() }
        },
        secondary = "Not now" to ::next,
        dots = true,
    )

    private fun arrange(): View {
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cards = HashMap<Boolean, LinearLayout>()
        // Marked in place, not rebuilt, so the keypad highlight stays on the card just picked.
        fun refresh() = cards.forEach { (cats, card) -> markChoice(card, prefs.drawerCategories == cats) }
        listOf(true to "Categories", false to "All apps").forEach { (cats, label) ->
            val card = choiceCard(label, if (cats) "Grouped, with tabs" else "One list, A to Z", categories = cats) {
                prefs.drawerCategories = cats
                refresh()
            }
            cards[cats] = card
            choices.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(Ui.SPACE_S), 0, dp(Ui.SPACE_S), 0)
            })
        }
        refresh()
        return page(
            art = choices,
            title = "Arrange your apps",
            body = (if (hasKeypad()) "Hold Down on the home screen to see them." else "Swipe up from the home screen to see them.") +
                " You can change this later in Settings.",
            primary = "Continue" to ::next,
            artFirst = false,
            dots = true,
        )
    }

    private fun getAround(): View {
        val keypad = hasKeypad()
        val tips = if (keypad) listOf(
            Triple(R.drawable.ic_arrow_downward, "Hold Down", "All your apps"),
            Triple(R.drawable.ic_arrow_upward, "Hold Up", "Search apps, contacts and more"),
            Triple(R.drawable.ic_dialpad, "Type a phone number", "Press Call to dial. Hold a number key to run its shortcut."),
            Triple(R.drawable.ic_keyboard, "Menu", "Options for whatever is highlighted"),
        ) else listOf(
            Triple(R.drawable.ic_arrow_upward, "Swipe up", "All your apps"),
            Triple(R.drawable.ic_arrow_downward, "Swipe down", "Search apps, contacts and more"),
            Triple(R.drawable.ic_swipe, "Hold an app", "Options, or drag it to move it"),
            Triple(R.drawable.ic_add, "Hold empty space", "Widgets, wallpaper and settings"),
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@OnboardingActivity, palette.surfaceHigh, Components.GROUP_RADIUS)
            setPadding(0, dp(Ui.SPACE_S), 0, dp(Ui.SPACE_S))
            tips.forEach { (icon, what, does) -> addView(tipRow(icon, what, does)) }
        }
        return page(
            art = list,
            title = "Getting around",
            body = null,
            primary = "Continue" to ::next,
            artFirst = false,
            dots = true,
        )
    }

    private fun done() = page(
        art = symbol(R.drawable.ic_check),
        title = "You're all set",
        body = "Open Settings to set app limits, filter notifications and customize Feather Launcher.",
        primary = "Start" to ::finishOnboarding,
        dots = true,
    )

    // endregion

    // region Pieces

    /**
     * One step: the picture, a title and a line of text centered, with the button (and an
     * optional quieter second choice) at the bottom, and the step dots above them.
     */
    private fun page(
        art: View,
        title: String,
        body: String?,
        primary: Pair<String, () -> Unit>,
        secondary: Pair<String, () -> Unit>? = null,
        artFirst: Boolean = true,
        dots: Boolean = false,
    ): View {
        val p = palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), insets.top + dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), insets.bottom + dp(Ui.SPACE_L))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            val heading = TextView(context).apply {
                text = title
                style(TextStyle.Title, p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
            }
            val text = body?.let {
                TextView(context).apply {
                    this.text = it
                    style(TextStyle.Body, p.textSecondary)
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.25f)
                }
            }
            if (artFirst) {
                addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(Ui.SPACE_XL + Ui.SPACE_S)
                })
                addView(heading)
                text?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Ui.SPACE_M) }) }
            } else {
                addView(heading)
                text?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Ui.SPACE_M) }) }
                addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Ui.SPACE_XL)
                })
            }
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            if (dots) addView(stepDots(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { bottomMargin = dp(Ui.SPACE_M) })
            addView(Components.pillButton(context, primary.first, p.accent, p.onAccent) { primary.second() }.apply {
                tag = PRIMARY
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            if (secondary != null) {
                addView(TextView(context).apply {
                    this.text = secondary.first
                    gravity = Gravity.CENTER
                    style(TextStyle.Label, p.textSecondary)
                    background = Ui.pressable(context, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { secondary.second() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_XS) })
            }
        }
    }

    /** Where in the steps after the welcome this is. */
    private fun stepDots() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        for (i in 1 until steps.size) {
            val current = i == step
            addView(View(context).apply {
                background = Ui.rounded(context, if (current) palette.accent else palette.surfaceHigh, Ui.RADIUS_PILL)
            }, LinearLayout.LayoutParams(dp(if (current) 20 else 8), dp(8)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }

    /** The app's own icon, large: the feather on its blue tile. */
    private fun appIcon(): View = ImageView(this).apply {
        setImageDrawable(getDrawable(R.mipmap.ic_launcher))
        layoutParams = LinearLayout.LayoutParams(dp(112), dp(112))
        clipToOutline = true
        background = Ui.rounded(this@OnboardingActivity, 0, 30)
    }.let { icon ->
        FrameLayout(this).apply { addView(icon, FrameLayout.LayoutParams(dp(112), dp(112))) }
    }

    /** A line icon on a soft round tile in the accent color. */
    private fun symbol(icon: Int): View = FrameLayout(this).apply {
        background = Ui.rounded(context, palette.accentSurface, Ui.RADIUS_PILL)
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(palette.accent)
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
    }.let { tile -> FrameLayout(this).apply { addView(tile, FrameLayout.LayoutParams(dp(96), dp(96))) } }

    /**
     * A tappable choice with a small picture of what it means: tabs at the bottom, or one long
     * list. Which one is chosen shows as in the rest of the app (see [markChoice]); the only
     * outline is the keypad highlight, as everywhere else.
     */
    private fun choiceCard(label: String, detail: String, categories: Boolean, onPick: () -> Unit) =
        LinearLayout(this).apply {
            val p = palette
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_L))
            foreground = Ui.pressable(context, Components.GROUP_RADIUS)
            isFocusable = true
            addView(miniList(categories), LinearLayout.LayoutParams(dp(84), dp(120)))
            addView(TextView(context).apply {
                text = label
                style(TextStyle.Label, p.text)
                gravity = Gravity.CENTER
                compoundDrawablePadding = dp(Ui.SPACE_XS)
                setPadding(0, dp(Ui.SPACE_M), 0, 0)
            })
            addView(TextView(context).apply {
                text = detail
                style(TextStyle.Secondary, p.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(Ui.SPACE_XS), 0, 0)
            })
            setOnClickListener { onPick() }
        }

    /** The chosen card takes the accent's soft tone, with a check by its name, like a chosen tile in Settings. */
    private fun markChoice(card: LinearLayout, selected: Boolean) {
        val p = palette
        card.background = Ui.rounded(this, if (selected) p.accentSurface else p.surfaceHigh, Components.GROUP_RADIUS)
        card.isSelected = selected
        (card.getChildAt(1) as TextView).apply {
            setTextColor(if (selected) p.accent else p.text)
            val check = if (selected) getDrawable(R.drawable.ic_check)!!.mutate().apply {
                setTint(p.accent)
                setBounds(0, 0, dp(18), dp(18))
            } else null
            setCompoundDrawablesRelative(check, null, null, null)
        }
    }

    /** A tiny phone screen: rows of dots for apps, and a tab bar for the categories layout. */
    private fun miniList(categories: Boolean): View = LinearLayout(this).apply {
        val p = palette
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(8))
        background = Ui.rounded(context, p.surface, 14)
        val rows = if (categories) 2 else 5
        repeat(rows) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(3) {
                    addView(View(context).apply { background = Ui.rounded(context, p.accent, 5) },
                        LinearLayout.LayoutParams(0, dp(14), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        if (categories) addView(View(context).apply { background = Ui.rounded(context, p.textTertiary, Ui.RADIUS_PILL) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
    }

    private fun tipRow(icon: Int, what: String, does: String) = LinearLayout(this).apply {
        val p = palette
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_M), dp(Ui.SPACE_L), dp(Ui.SPACE_M))
        addView(FrameLayout(context).apply {
            background = Ui.rounded(context, p.accentSurface, Ui.RADIUS_PILL)
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.accent)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = what
                style(TextStyle.Label, p.text)
            })
            addView(TextView(context).apply {
                text = does
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
    }

    /** A phone with a number keypad, where getting around works with keys rather than swipes. */
    private fun hasKeypad() = resources.configuration.let {
        it.keyboard == Configuration.KEYBOARD_12KEY || it.navigation == Configuration.NAVIGATION_DPAD
    }

    private fun isHome() = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    private fun dp(value: Int) = Ui.dp(this, value)
    private fun dp(value: Float) = value * resources.displayMetrics.density

    // endregion

    companion object {
        private const val PRIMARY = "primary"

        /** Opens the welcome the first time the launcher runs. */
        fun showIfNeeded(context: Context): Boolean {
            if (Prefs(context).onboarded) return false
            context.startActivity(Intent(context, OnboardingActivity::class.java))
            return true
        }
    }
}
