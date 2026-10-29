package app.featherlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The launcher's settings, as a full page. Quick, in-context options stay in sheets on the home screen. */
class SettingsActivity : Activity() {
    internal lateinit var prefs: Prefs
    internal lateinit var repo: AppRepository
    /** Apps the pickers on these pages offer: hidden apps stay out of sight here too. */
    internal val visibleApps get() = repo.apps.filter { it.key !in prefs.hidden }
    internal lateinit var palette: Palette
    internal lateinit var root: FrameLayout
    internal lateinit var overlays: Overlays
    internal lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var insets = Rect()

    /** Each feature has its own page; the main page links to them. */
    enum class Page(val title: String) {
        MAIN("Settings"), APPEARANCE("Appearance"), APP_LIST("App list"), CALENDAR("Calendar and times"), KEYS("Gestures and keys"),
        LIMITS("App limits"), LIMIT("App limit"), NOTIFICATIONS("Notifications"), HIDDEN("Hidden apps"), SHORTCUT("Shortcut"),
        FILTER("Filter"), NOTIFICATION_HISTORY("Hidden notifications"),
    }

    /** The notification filter open in its editor, and whether it's a new one. */
    internal var filterDraft: NotificationFilter? = null
    internal var filterIsNew = false

    /** The app limit open on its page. */
    internal var limitId: Long? = null

    /** The key shortcut open in the editor. */
    internal var draft: ShortcutDraft? = null
    /** Set while a sheet waits for a key to be pressed; gets the key, or two pressed together. */
    internal var keyCapture: ((List<Int>) -> Unit)? = null
        set(value) {
            field = value
            // Android hands Back to back handlers, not as a key; while a key is being chosen, this
            // one takes it so Back can be chosen too (on a phone without a power button, double
            // pressing it can lock the screen).
            if (value != null) onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, captureBack)
            else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(captureBack)
        }
    private val captureBack = android.window.OnBackInvokedCallback { keyCapture?.invoke(listOf(KeyEvent.KEYCODE_BACK)) }
    /** Keys down during a capture, in the order they were pressed. */
    private val captured = ArrayList<Int>()
    private var onContactPicked: ((number: String, name: String?) -> Unit)? = null

    /** Opens the phone's contact picker for a phone number. */
    internal fun pickContact(onPicked: (number: String, name: String?) -> Unit) {
        onContactPicked = onPicked
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_CONTACT)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        build()
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            if (requestCode == REQUEST_BACKUP) return saveBackup(uri)
            if (requestCode == REQUEST_RESTORE) return confirmRestore(uri)
        }
        if (requestCode != REQUEST_CONTACT || resultCode != RESULT_OK) return
        val (number, name) = KeySettings.readPicked(this, data) ?: return
        onContactPicked?.invoke(number, name)
        onContactPicked = null
    }

    private var current = Page.MAIN
    private var openedDirectly = false

    /** Scroll positions of the pages behind the open one. */
    private val scrolls = HashMap<Page, Int>()

    internal fun open(next: Page) {
        scrolls[current] = scroll.scrollY
        // Going back up returns to where that page was scrolled; a page opened afresh starts at the top.
        if (next == Page.MAIN) scrolls.keys.retainAll(setOf(Page.MAIN))
        if (next == Page.SHORTCUT || next == Page.FILTER || next == Page.NOTIFICATION_HISTORY) scrolls.remove(next)
        current = next
        build(scrolls[next] ?: 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        prefs = Prefs(this)
        repo = Launcher.repo(this)
        // Opened straight to one page (from the clock's menu, say); Back then closes Settings.
        intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name }?.let { current = it; openedDirectly = true } }
        root = FrameLayout(this)
        overlays = Overlays(root) { palette }
        setContentView(root)

        // Newer Android sends Back here instead of as a key press.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { if (!overlays.dismiss()) back() }
        root.setOnApplyWindowInsetsListener { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsets.Type.systemBars())
            val next = Rect(bars.left, bars.top, bars.right, bars.bottom)
            overlays.imeBottom = windowInsets.getInsets(WindowInsets.Type.ime()).bottom
            // Only a change in the system bars needs a redraw; the keyboard opening must not
            // rebuild the page, or it would close the sheet being typed into.
            if (next != insets) {
                insets = next
                overlays.insets = insets
                build()
            }
            windowInsets
        }
        // The page is first drawn once the system bars' insets arrive, just before the first frame;
        // building it here too would only be thrown away.
        root.post { if (!::content.isInitialized) build() }
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) root.post { updatesSheet() }
    }

    /** The phone went light or dark (perhaps switched from here): redrawn in place, staying on this page. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::content.isInitialized) build()
    }

    override fun onStop() {
        super.onStop()
        // Unlocking changes to app limits lasts until Settings is left.
        AppLimits.get(this).lockAgain()
    }

    private var resumed = false

    override fun onResume() {
        super.onResume()
        // Default home app may have been changed in system settings. Not on opening, when it was just built.
        if (resumed && ::content.isInitialized) build()
        resumed = true
        if (installWhenAllowed) {
            installWhenAllowed = false
            if (packageManager.canRequestPackageInstalls()) root.post { updatesSheet(installNow = true) }
        }
    }

    /** Draws the whole page from the current settings; called again after every change. */
    internal fun build(scrollTo: Int? = null) {
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        val p = palette
        val scrollY = scrollTo ?: if (::scroll.isInitialized) scroll.scrollY else 0
        root.removeAllViews()
        root.setBackgroundColor(p.surface)
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = header(p)
        page.addView(header)
        val tint = Ui.scrollTint(header, p.surface, p.surfaceScrolled)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, insets.bottom + dp(Ui.SPACE_XL))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            setOnScrollChangeListener { _, _, y, _, _ -> tint(y > 0) }
        }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        when (current) {
            Page.MAIN -> main(p)
            Page.APPEARANCE -> appearance(p)
            Page.APP_LIST -> appList(p)
            Page.KEYS -> KeySettings(this).build(p)
            Page.SHORTCUT -> KeySettings(this).buildEditor(p)
            Page.NOTIFICATIONS -> NotificationSettings(this).build(p)
            Page.FILTER -> NotificationSettings(this).buildFilter(p)
            Page.NOTIFICATION_HISTORY -> NotificationSettings(this).buildHistory(p)
            Page.HIDDEN -> hidden(p)
            Page.CALENDAR -> CalendarSettings(this).build(p)
            Page.LIMITS -> LimitSettings(this).build(p)
            Page.LIMIT -> LimitSettings(this).buildLimit(p)
        }
        // Back where it was before the first frame shows, so the rebuilt page doesn't flash at the top.
        scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                if (scrollY > 0) header.setBackgroundColor(p.surfaceScrolled)
                scroll.scrollTo(0, scrollY)
                return true
            }
        })
    }

    /** The back arrow and the page's title. It reaches up behind the status bar, so its tint does too. */
    private fun header(p: Palette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64) + insets.top
        setPadding(dp(Ui.SPACE_S), insets.top, dp(Ui.SPACE_L), 0)
        addView(ImageView(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(p.text)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = Ui.pressable(this@SettingsActivity, Ui.RADIUS_PILL)
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@SettingsActivity).apply {
            text = when (current) {
                Page.SHORTCUT -> if (draft?.original == null) "New shortcut" else "Edit shortcut"
                Page.FILTER -> if (filterIsNew) "New filter" else "Edit filter"
                Page.LIMIT -> limitId?.let { AppLimits.get(this@SettingsActivity).find(it) }?.name ?: current.title
                else -> current.title
            }
            style(TextStyle.Title, p.text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(Ui.SPACE_S)
        })
    }

    private fun back() {
        if (openedDirectly && current != Page.SHORTCUT && current != Page.FILTER) return finish()
        when (current) {
            Page.MAIN -> finish()
            // Leaving the editor without saving drops the changes: asked first, if there are any.
            Page.SHORTCUT -> {
                val leave = {
                    draft = null
                    open(Page.KEYS)
                }
                val d = draft
                if (d?.changed == true) confirmDiscard(if (d.original == null) "This shortcut won't be added." else "Your changes to this shortcut won't be saved.", leave)
                else leave()
            }
            Page.FILTER, Page.NOTIFICATION_HISTORY -> {
                filterDraft = null
                open(Page.NOTIFICATIONS)
            }
            Page.LIMIT -> open(Page.LIMITS)
            else -> open(Page.MAIN)
        }
    }

    /** Asks before leaving an editor with changes in it: [discard] drops them, Keep editing stays. */
    private fun confirmDiscard(message: String, discard: () -> Unit) {
        val p = palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(TextView(this@SettingsActivity).apply {
                text = message
                style(TextStyle.Body, p.textSecondary)
            })
            addView(Components.pillButton(this@SettingsActivity, "Discard", p.danger, p.onAccent) {
                overlays.dismiss()
                discard()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_L) })
            addView(Components.pillButton(this@SettingsActivity, "Keep editing", p.surfaceHigh, p.text) { overlays.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_S) })
        }
        overlays.showSheet("Discard changes?", column)
    }

    /** The main page: one row per area, each showing what it's set to, opening its own page. */
    private fun main(p: Palette) {
        // Only being the home screen is offered here; everything else is asked for on the page
        // of the feature that needs it, when it's turned on.
        if (!Setup.isHome(this)) {
            content.addView(spacer())
            content.addView(Components.group(this, p, listOf(Setup.row(this, p, Setup.HOME))))
        }
        val limitsOn = AppLimits.get(this).limits.filter { it.enabled }
        val hiddenCount = prefs.hidden.size
        val keyCount = KeyShortcuts.get(this).list.count { it.isDigit }
        content.addView(spacer())
        content.addView(Components.group(this, p, listOf(
            pageRow(p, R.drawable.ic_palette, Page.APPEARANCE,
                "${prefs.themeMode.label} · ${if (prefs.accentColor == 0) "Wallpaper colors" else "Custom color"}"),
            pageRow(p, R.drawable.ic_app_list, Page.APP_LIST,
                "${if (prefs.drawerCategories) "Categories" else "All apps"} · ${if (prefs.drawerGrid) "Grid" else "List"}"),
            pageRow(p, R.drawable.ic_calendar, Page.CALENDAR, listOfNotNull(
                prefs.chosenPlace?.name ?: "Current location",
                prefs.secondCalendar.takeIf { it != SecondCalendar.NONE }?.let { "${it.label} date" },
                if (prefs.dailyZmanim) "Zmanim" else "Sunrise and sunset",
            ).joinToString(" · ")),
        )))
        content.addView(spacer())
        content.addView(Components.group(this, p, listOf(
            pageRow(p, R.drawable.ic_keyboard, Page.KEYS,
                "Swipe down for ${if (prefs.swipeDownSearch) "Smart Search" else "notifications"}" +
                    if (keyCount > 0) " · $keyCount number keys" else ""),
            pageRow(p, R.drawable.ic_hourglass, Page.LIMITS,
                if (limitsOn.isEmpty()) "Off" else limitsOn.joinToString(", ") { it.name }),
            pageRow(p, R.drawable.ic_notification, Page.NOTIFICATIONS, NotificationFilters.get(this).filters.count { it.enabled }.let {
                if (it == 0) "No filters" else if (it == 1) "1 filter" else "$it filters"
            }),
            pageRow(p, R.drawable.ic_visibility_off, Page.HIDDEN,
                (if (hiddenCount == 0) "None" else "$hiddenCount apps") + if (prefs.hiddenPin != null) " · PIN on" else ""),
        )))
        system(p)
        content.addView(TextView(this).apply {
            text = "Feather Launcher ${packageManager.getPackageInfo(packageName, 0).versionName}"
            style(TextStyle.Caption, p.textTertiary)
            gravity = Gravity.CENTER
            setPadding(0, dp(Ui.SPACE_XL), 0, 0)
        })
    }

    private fun pageRow(p: Palette, icon: Int, page: Page, summary: String) =
        Components.row(this, p, icon, page.title, subtitle = summary, chevron = true, inset = Ui.SPACE_L) {
            if (page == Page.HIDDEN && prefs.hiddenPin != null) PinPad.ask(this, palette, overlays, prefs.hiddenPin!!) { open(page) }
            else open(page)
        }

    internal fun heading(p: Palette, text: String) = TextView(this).apply {
        this.text = text
        style(TextStyle.Title, p.text)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), 0)
    }

    // Every setting is a row showing its current value; tapping it opens the choices.

    private fun appearance(p: Palette) {
        content.addView(spacer())
        val shape = prefs.iconShape
        val followsPhone = prefs.themeMode == ThemeMode.SYSTEM
        content.addView(Components.group(this, p, listOfNotNull(
            Components.row(this, p, R.drawable.ic_theme, "Theme", prefs.themeMode.label, chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(this, p, overlays, "Theme", ThemeMode.entries.map { it.label to null }, prefs.themeMode.ordinal) {
                    prefs.themeMode = ThemeMode.entries[it]
                    PhoneTheme.sync(this, prefs, force = true)
                    build()
                }
            },
            if (followsPhone) null else Components.toggleRow(this, p, R.drawable.ic_moon, "Use for the whole phone",
                prefs.themeWholePhone && PhoneTheme.allowed(this)) {
                if (it && !PhoneTheme.allowed(this)) {
                    // Can't be on yet: show how to allow it, and the switch goes back off.
                    build()
                    wholePhoneSheet()
                    return@toggleRow
                }
                prefs.themeWholePhone = it
                PhoneTheme.sync(this, prefs, force = true)
            },
            Components.row(this, p, R.drawable.ic_wallpaper, "Wallpaper", chevron = true, inset = Ui.SPACE_L) {
                startActivity(Intent(this, WallpaperActivity::class.java))
            },
            Components.row(this, p, R.drawable.ic_palette, "Accent color",
                if (prefs.accentColor == 0) "From wallpaper" else "Custom", chevron = true, inset = Ui.SPACE_L,
                trailing = Components.dot(this, p.accent)) { accentSheet() },
        )))
        if (prefs.themeMode == ThemeMode.SUN) content.addView(Components.footnote(this, p,
            "Light from sunrise to sunset where you are, or in the city chosen under Calendar and times. Without a location, 7 am to 7 pm."))
        content.addView(heading(p, "Icons"))
        content.addView(spacer())
        content.addView(Components.group(this, p, listOf(
            Components.row(this, p, R.drawable.ic_shape, "Icon shape", shape.label, chevron = true, inset = Ui.SPACE_L,
                trailing = Components.shapeGlyph(this, shape, p.textSecondary)) {
                Components.choiceSheet(this, p, overlays, "Icon shape",
                    IconShape.entries.map { it.label to Components.shapeGlyph(this, it, p.textSecondary) }, shape.ordinal) {
                    prefs.iconShape = IconShape.entries[it]
                    repo.setShape(prefs.iconShape)
                    build()
                }
            },
            Components.toggleRow(this, p, R.drawable.ic_text_fields, "App names on the home screen", prefs.showLabels) {
                prefs.showLabels = it
            },
            Components.toggleRow(this, p, R.drawable.ic_notification, "Notification badges", prefs.notificationBadges) {
                prefs.notificationBadges = it
                // Badges come from notification access; turning them on is the moment to ask for it.
                if (it && !NotificationService.isEnabled(this)) Setup.NOTIFICATIONS.start(this)
            },
        )))
        content.addView(Components.footnote(this, p,
            "A red badge shows how many notifications an app has. Needs notification access."))

        content.addView(heading(p, "Dark mode"))
        content.addView(spacer())
        val look = prefs.iconLook
        content.addView(Components.group(this, p, listOf(
            Components.row(this, p, R.drawable.ic_moon, "App icons", look.label, chevron = true, inset = Ui.SPACE_L,
                trailing = lookSample(look)) {
                Components.choiceSheet(this, p, overlays, "App icons",
                    IconLookSetting.entries.map { lookName(it) to lookSample(it) }, look.ordinal) {
                    prefs.iconLook = IconLookSetting.entries[it]
                    build()
                }
            },
            Components.toggleRow(this, p, R.drawable.ic_wallpaper, "Dim the wallpaper", prefs.dimWallpaper) {
                prefs.dimWallpaper = it
            },
        )))
        content.addView(Components.footnote(this, p,
            "Choose how app icons look and whether to dim the wallpaper. To switch wallpapers with dark mode, choose a light and dark pair under Wallpaper."))
    }

    /**
     * Android keeps switching dark mode for its own apps; Feather can do it once it's been
     * allowed from a computer, one time.
     */
    private fun wholePhoneSheet() {
        val p = palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(TextView(this@SettingsActivity).apply {
                text = "Android only lets Feather switch the whole phone to light or dark once it's allowed to from a computer. " +
                    "Connect the phone with USB debugging on and run this once:"
                style(TextStyle.Body, p.textSecondary)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = PhoneTheme.GRANT
                style(TextStyle.Secondary, p.text)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                background = Ui.rounded(this@SettingsActivity, p.surfaceHigh, Ui.RADIUS_M)
                setPadding(dp(Ui.SPACE_M), dp(Ui.SPACE_M), dp(Ui.SPACE_M), dp(Ui.SPACE_M))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ui.SPACE_M)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Then turn this on again. It stays allowed through updates."
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(0, dp(Ui.SPACE_M), 0, 0)
            })
            addView(Components.pillButton(this@SettingsActivity, "Copy command", p.accent, p.onAccent) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Command", PhoneTheme.GRANT))
                overlays.dismiss()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_L) })
        }
        overlays.showSheet("Use for the whole phone", column)
    }

    private fun lookName(look: IconLookSetting) = when (look) {
        IconLookSetting.AUTO -> "Automatic (dark in dark mode)"
        IconLookSetting.LIGHT -> "Light"
        IconLookSetting.DARK -> "Dark"
        IconLookSetting.TINTED -> "Tinted"
    }

    /** One real app icon drawn in [look], to show what it does. */
    private fun lookSample(look: IconLookSetting): View = ImageView(this).apply {
        val sample = repo.apps.firstOrNull { it.packageName == "com.google.android.dialer" }
            ?: repo.apps.firstOrNull { it.packageName.startsWith("com.google") } ?: repo.apps.firstOrNull()
        val drawable = sample?.let { runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull() } ?: return@apply
        val iconLook = when (look) {
            IconLookSetting.AUTO -> if (DarkMode.isNight(this@SettingsActivity)) IconLook.DARK else IconLook.NORMAL
            IconLookSetting.LIGHT -> IconLook.NORMAL
            IconLookSetting.DARK -> IconLook.DARK
            IconLookSetting.TINTED -> IconLook.TINTED
        }
        setImageBitmap(prefs.iconShape.render(drawable, dp(44), iconLook, DarkMode.tint(this@SettingsActivity, prefs)))
    }

    /** From the wallpaper (automatically), one of the wallpaper's own colors, or any color on the slider. */
    private fun accentSheet() {
        val p = palette
        val wallColors = Palette.wallpaperColors(this)
        val options = listOf("From wallpaper" to Components.dot(this, Palette.from(this, ColorStyle.WALLPAPER, prefs.themeMode).accent)) +
            wallColors.mapIndexed { i, c -> "Wallpaper color ${i + 1}" to Components.dot(this, Palette.from(this, ColorStyle.WALLPAPER, prefs.themeMode, c).accent) }
        val current = when (val c = prefs.accentColor) {
            0 -> 0
            else -> wallColors.indexOf(c).takeIf { it >= 0 }?.plus(1) ?: -1
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Components.sectionLabel(this@SettingsActivity, p, if (current == -1) "Custom color (in use)" else "Custom color"))
            addView(Components.hueSlider(this@SettingsActivity, if (prefs.accentColor != 0) prefs.accentColor else p.accent) { color ->
                prefs.accentColor = color
                overlays.dismiss()
                build()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply {
                setMargins(dp(Ui.SPACE_XL), dp(Ui.SPACE_XS), dp(Ui.SPACE_XL), dp(Ui.SPACE_S))
            })
        }
        Components.choiceSheet(this, p, overlays, "Accent color", options, current, footer) { i ->
            prefs.accentColor = if (i == 0) 0 else wallColors[i - 1]
            build()
        }
    }

    private fun appList(p: Palette) {
        content.addView(spacer())
        val styles = listOf("Categories", "All apps")
        val layouts = listOf("Grid", "List")
        content.addView(Components.group(this, p, listOf(
            Components.row(this, p, R.drawable.ic_list_style, "Style", styles[if (prefs.drawerCategories) 0 else 1],
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(this, p, overlays, "App list style", styles.map { it to null }, if (prefs.drawerCategories) 0 else 1) {
                    prefs.drawerCategories = it == 0
                    build()
                }
            },
            Components.row(this, p, R.drawable.ic_grid, "Show apps as", layouts[if (prefs.drawerGrid) 0 else 1],
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(this, p, overlays, "Show apps as", layouts.map { it to null }, if (prefs.drawerGrid) 0 else 1) {
                    prefs.drawerGrid = it == 0
                    build()
                }
            },
        )))
        content.addView(Components.footnote(this, p, "Hold a category tab or app for options. Drag to reorder, or choose Move to use the arrow keys."))
    }

    /** Hidden apps, listed here so they can be brought back, and the PIN that guards them. */
    private fun hidden(p: Palette) {
        content.addView(spacer())
        content.addView(Components.group(this, p, listOf(
            Components.row(this, p, R.drawable.ic_lock, "PIN", if (prefs.hiddenPin == null) "Off" else "On",
                chevron = true, inset = Ui.SPACE_L) { pinOptions() },
        )))
        content.addView(Components.footnote(this, p, "A PIN is required to open Hidden apps or these settings."))
        val apps = repo.apps.filter { it.key in prefs.hidden }.sortedBy { it.label.lowercase() }
        content.addView(heading(p, "Hidden apps"))
        content.addView(spacer())
        if (apps.isEmpty()) {
            content.addView(Components.footnote(this, p, "No hidden apps. Hold an app in the app list and choose Hide from app list."))
            return
        }
        content.addView(Components.group(this, p, apps.map { e ->
            Components.appRow(this, p, repo.icon(e), e.label, "Show") {
                prefs.setHidden(e.key, false)
                build()
            }.apply { setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0) }
        }))
        content.addView(Components.footnote(this, p, "Hidden apps stay out of the app list and search. Open them from the app list's menu."))
    }

    private fun system(p: Palette) {
        content.addView(spacer())
        val isDefault = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName
        content.addView(Components.group(this, p, listOf(
            Components.row(this, p, R.drawable.ic_home, "Default home app",
                if (isDefault) "This launcher" else "Another app", chevron = true, inset = Ui.SPACE_L) {
                startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
            },
            Components.row(this, p, R.drawable.ic_history, "Backup and restore", chevron = true, inset = Ui.SPACE_L) { backupSheet() },
            Updates.get(this).available.let { release ->
                Components.row(this, p, R.drawable.ic_arrow_downward, "Updates",
                    if (release != null) "${release.version} available" else "Version ${Updates.get(this).currentVersion}",
                    chevron = true, inset = Ui.SPACE_L) { updatesSheet() }
            },
            Components.row(this, p, R.drawable.ic_settings, "Phone settings", chevron = true, inset = Ui.SPACE_L) {
                startSafely(Intent(Settings.ACTION_SETTINGS))
            },
        )))
    }

    /**
     * The newest version and what's new in it, with a button to install it; or, when this is the
     * newest one known, a button to check again.
     */
    /** Sent to Android's "install unknown apps" switch; coming back carries on with the install. */
    private var installWhenAllowed = false

    private fun updatesSheet(installNow: Boolean = false) {
        val p = palette
        val updates = Updates.get(this)
        val release = updates.available
        val status = TextView(this).apply {
            style(TextStyle.Secondary, p.textSecondary)
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
            visibility = View.GONE
        }
        fun say(text: String) {
            status.text = text
            status.visibility = View.VISIBLE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_M))
        }
        if (release == null) {
            column.addView(TextView(this).apply {
                text = "You have version ${updates.currentVersion}."
                style(TextStyle.Body, p.text)
                setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
            })
            column.addView(status)
            column.addView(Components.row(this, p, R.drawable.ic_refresh, "Check for updates", color = p.accent) {
                say("Checking…")
                updates.check { ok ->
                    when {
                        !ok -> say("Couldn't check. Make sure you're online and try again.")
                        updates.available == null -> say("You have the newest version.")
                        else -> {
                            overlays.dismiss()
                            build()
                            updatesSheet()
                        }
                    }
                }
            })
            overlays.showSheet("Updates", column)
            return
        }
        // Long notes scroll in their own space, so all of them can be read and the button below
        // always fits.
        val notesHeight = resources.displayMetrics.heightPixels * 45 / 100
        val notes = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(notesHeight, MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(Ui.SPACE_XL))
            addView(TextView(this@SettingsActivity).apply {
                text = release.notes.ifBlank { "A new version of Feather Launcher." }
                style(TextStyle.Body, p.text)
                setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
            })
        }
        column.addView(notes)
        column.addView(status)
        lateinit var installRow: View
        fun install() {
            if (updates.busy) return
            // Android asks once whether Feather Launcher may install apps.
            if (!packageManager.canRequestPackageInstalls()) {
                installWhenAllowed = true
                startSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                return
            }
            // The progress takes the button's place while it downloads; it comes back to try again.
            installRow.visibility = View.GONE
            say("Downloading…")
            updates.install(release, progress = { say("Downloading… $it%") }, failed = {
                say(it)
                installRow.visibility = View.VISIBLE
            })
        }
        installRow = Components.row(this, p, R.drawable.ic_arrow_downward, "Install ${release.version}", color = p.accent) { install() }
        column.addView(installRow)
        overlays.showSheet("Feather Launcher ${release.version}", column, scrollable = notes)
        if (installNow) install()
    }

    /** Save everything to a file, or bring it back from one (another phone, or after a reset). */
    private fun backupSheet() {
        val p = palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_M))
            addView(Components.row(this@SettingsActivity, p, R.drawable.ic_arrow_downward, "Save a backup",
                subtitle = "Layout, settings and wallpaper pair. Photo Shuffle photos excluded.") {
                overlays.dismiss()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, Backup.fileName()), REQUEST_BACKUP)
            })
            addView(Components.row(this@SettingsActivity, p, R.drawable.ic_arrow_upward, "Restore from a backup",
                subtitle = "Replaces your current setup. Widgets need adding again.") {
                overlays.dismiss()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQUEST_RESTORE)
            })
        }
        overlays.showSheet("Backup and restore", column)
    }

    private fun saveBackup(uri: android.net.Uri) {
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(this).toByteArray()) }
        }.isSuccess
        android.widget.Toast.makeText(this, if (ok) "Backup saved" else "Couldn't save the backup", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Asks before replacing everything, showing when the backup was made. */
    private fun confirmRestore(uri: android.net.Uri) {
        val backup = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        if (backup == null || !Backup.isBackup(backup)) {
            android.widget.Toast.makeText(this, "That isn't a Feather Launcher backup", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val saved = org.json.JSONObject(backup).optLong("saved")
        val p = palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            addView(TextView(this@SettingsActivity).apply {
                text = "Saved " + android.text.format.DateFormat.getMediumDateFormat(this@SettingsActivity).format(java.util.Date(saved)) +
                    ". Your current home screen, categories and settings will be replaced, and the launcher will restart."
                style(TextStyle.Body, p.textSecondary)
                setLineSpacing(0f, 1.2f)
            })
            addView(Components.pillButton(this@SettingsActivity, "Restore", p.accent, p.onAccent) {
                overlays.dismiss()
                Backup.restore(this@SettingsActivity, backup)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_L) })
        }
        overlays.showSheet("Restore this backup?", column)
    }

    internal fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(Ui.SPACE_M))
    }

    /** Turning the PIN on, changing it or turning it off; changing and turning off need the old one. */
    private fun pinOptions() {
        val pin = prefs.hiddenPin
        val setNew = {
            PinPad.create(this, palette, overlays) {
                prefs.hiddenPin = PinPad.hash(it)
                build()
                Toast.makeText(this, "PIN set for hidden apps", Toast.LENGTH_SHORT).show()
            }
        }
        if (pin == null) return setNew()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_M))
            addView(Components.row(this@SettingsActivity, palette, R.drawable.ic_edit, "Change PIN") {
                overlays.dismiss()
                root.postDelayed({ PinPad.ask(this@SettingsActivity, palette, overlays, pin) { root.postDelayed(setNew, 160) } }, 160)
            })
            addView(Components.row(this@SettingsActivity, palette, R.drawable.ic_close, "Turn off PIN", color = palette.danger) {
                overlays.dismiss()
                root.postDelayed({
                    PinPad.ask(this@SettingsActivity, palette, overlays, pin) {
                        prefs.hiddenPin = null
                        build()
                    }
                }, 160)
            })
        }
        overlays.showSheet("PIN for hidden apps", column)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keyCapture?.takeIf { overlays.isShowing && event.keyCode != KeyEvent.KEYCODE_BACK || captured.isNotEmpty() }?.let { capture ->
            // Decided when the first key comes back up, so a second key pressed with it counts too.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode !in captured && captured.size < 2) {
                captured += event.keyCode
            } else if (event.action == KeyEvent.ACTION_UP && event.keyCode in captured) {
                val keys = captured.toList()
                captured.clear()
                capture(keys)
            }
            return true
        }
        PinPad.active?.takeIf { overlays.isShowing }?.let { pad ->
            val k = event.keyCode
            val digit = if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) k - KeyEvent.KEYCODE_0 else null
            val handled = digit != null || k == KeyEvent.KEYCODE_DEL || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            if (handled) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when {
                    digit != null -> pad.type('0' + digit)
                    k == KeyEvent.KEYCODE_DEL -> pad.delete()
                    else -> pad.confirm()
                }
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !overlays.dismiss()) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    internal fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nothing on this phone can open that", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun dp(value: Int) = Ui.dp(this, value)

    companion object {
        private const val REQUEST_CONTACT = 31
        private const val REQUEST_BACKUP = 32
        private const val REQUEST_RESTORE = 33
        const val EXTRA_PAGE = "page"
        /** Opened from the update notification: shows the update. */
        const val EXTRA_UPDATE = "update"
    }
}
