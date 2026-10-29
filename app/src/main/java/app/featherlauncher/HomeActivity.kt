package app.featherlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.roundToInt

class HomeActivity : Activity(), HomeScreen.Host {
    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var widgets: Widgets
    private lateinit var launcherApps: LauncherApps
    private lateinit var overlays: Overlays
    private lateinit var shortcuts: Shortcuts
    private lateinit var palette: Palette

    private lateinit var home: GestureLayout
    private lateinit var homeScreen: HomeScreen
    private lateinit var drawer: GestureLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: DrawerAdapter
    private lateinit var contacts: Contacts
    /** The panel is in Smart Search mode rather than the app list. */
    private var searching = false
    /** Search sections opened with "Show more". */
    private val expanded = mutableSetOf<String>()
    private lateinit var smartSearch: SmartSearch
    private var mediaResults: List<Found> = emptyList()
    private var mediaFor: String? = null
    private lateinit var searchIcon: ImageView
    private lateinit var searchClear: ImageView
    private lateinit var categories: Categories
    /** Category chosen with the chips; null is All. */
    private var category: String? = null
    private var listAtTop = true
    private var listAtBottom = true

    /** The app list is showing the hidden apps page (reached through the PIN). */
    private var showingHidden = false

    private var drawerOpen = false
    /** Set while the drawer is choosing an app for something (a number key). */
    private var pick: ((AppEntry) -> Unit)? = null
    /** The query was typed on the keypad, so Back deletes a character instead of closing. */
    private var keypadQuery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_home)
        // Newer Android sends Back here instead of as a key press.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }

        prefs = Prefs(this)
        palette = currentPalette()
        Workspace.fitRows(this)
        repo = Launcher.repo(this)
        repo.setLook(DarkMode.iconLook(this, prefs), DarkMode.tint(this, prefs))
        launcherApps = getSystemService(LauncherApps::class.java)
        home = findViewById(R.id.home)
        drawer = findViewById(R.id.drawer)
        search = findViewById(R.id.search)
        list = findViewById(R.id.apps)
        contacts = Contacts(this)
        smartSearch = SmartSearch(this) { startSafely(it) }
        searchIcon = findViewById(R.id.search_icon)
        searchClear = findViewById(R.id.search_clear)
        overlays = Overlays(findViewById(R.id.root)) { palette }
        shortcuts = Shortcuts(this)
        categories = Categories(this)

        setUpInsets()
        setUpHome()
        setUpDrawer()

        widgets = Widgets(this, overlays, { palette }, { pkg ->
            repo.apps.firstOrNull { it.packageName == pkg }?.let(repo::icon)
        }) { homeScreen.cellSizeDp() }
        // The dock's apps were cached on disk last time; decode their icons before the first frame.
        val dockKeys = Workspace(this).items.filter { it.inDock }.map { it.ref }
        repo.start(dockKeys.ifEmpty { prefs.dock.filterNotNull() })
        homeScreen = HomeScreen(
            this, findViewById(R.id.root), findViewById(R.id.pages), findViewById(R.id.dots), findViewById(R.id.dock),
            repo, prefs, widgets, overlays, { palette }, this,
        )
        homeScreen.pages.onEmptyLongPress = { x, y -> homeMenu(Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())) }
        widgets.onAdded = homeScreen::addWidget
        widgets.onFeatherPicked = homeScreen::addFeather
        widgets.onFeatherHeld = homeScreen::armWidget
        repo.onChanged = ::onAppsChanged
        updateResults()
        applyTheme()

        WallpaperManager.getInstance(this).addOnColorsChangedListener({ _, _ ->
            if (prefs.colorStyle == ColorStyle.WALLPAPER) {
                palette = currentPalette()
                applyTheme()
            }
        }, Handler(mainLooper))
    }

    private fun currentPalette() = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Light/dark mode switched while the launcher was running.
        palette = currentPalette()
        applyTheme()
        applyDarkMode()
    }

    /** Icons, the wallpaper dimming and the wallpaper pair, for the current light or dark mode. */
    /** The screen went off; the darkening comes back after the unlock (see [applyDarkMode]). */
    private var dimAfterUnlock = false

    private fun applyDarkMode() {
        repo.setLook(DarkMode.iconLook(this, prefs), DarkMode.tint(this, prefs))
        val dim = findViewById<View>(R.id.wall_dim)
        val target = DarkMode.wallpaperDim(this, prefs)
        dim.animate().cancel()
        if (dimAfterUnlock && target > 0f) {
            // Unlocking zooms this window in from smaller than the screen, while the wallpaper
            // behind it stays put: a darkening already showing would show its edges. It fades
            // in once the zoom is over.
            dim.alpha = 0f
            dim.animate().alpha(target).setStartDelay(450).setDuration(250).start()
        } else {
            dim.alpha = target
        }
        dimAfterUnlock = false
        DarkMode.applyPair(this)
    }

    /** Re-colors everything from [palette]; called on start and whenever the color setting changes. */
    private fun applyTheme() {
        homeScreen.applyTheme()
        // The app list opens over the home screen, so it takes the wallpaper's colors.
        drawer.setBackgroundColor(palette.searchScrim)
        // Like Smart Launcher: the search bar has the dock's tone.
        findViewById<View>(R.id.search_bar).background = Ui.rounded(this, palette.glass, Ui.RADIUS_PILL)
        // Dark status bar icons over a light wallpaper and the light app list that goes with it.
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.wallpaperIsLight) light else 0, light)
        search.setTextColor(palette.onWall)
        search.setHintTextColor(palette.onWallSecondary)
        searchIcon.imageTintList = android.content.res.ColorStateList.valueOf(palette.onWallSecondary)
        searchClear.imageTintList = android.content.res.ColorStateList.valueOf(palette.onWallSecondary)
        searchClear.background = Ui.pressable(this, Ui.RADIUS_PILL, focusColor = palette.onWall)
        // With the keypad, the search bar (not the text box in it) holds the highlight, with the same
        // ring as everything else; see openSearch.
        findViewById<View>(R.id.search_bar).apply {
            isFocusable = true
            foreground = Ui.focusRing(this@HomeActivity, Ui.RADIUS_PILL, palette.onWall)
        }
        findViewById<TextView>(R.id.drawer_title).setTextColor(palette.onWall)
        listOf(R.id.drawer_search, R.id.drawer_more).forEach {
            findViewById<ImageView>(it).apply {
                imageTintList = android.content.res.ColorStateList.valueOf(palette.onWall)
                background = Ui.pressable(this@HomeActivity, Ui.RADIUS_PILL, focusColor = palette.onWall)
            }
        }
        adapter.palette = palette
        updateResults()
    }

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    private fun preloadSearch() {
        contacts.load()
        smartSearch.loadShortcuts({ pkg -> repo.apps.firstOrNull { it.packageName == pkg }?.label }) {
            if (searching && search.text.isNotBlank()) updateResults()
        }
    }

    /**
     * An invisible place for the keypad highlight to rest. Pressing a key (even Home) takes the
     * phone out of touch mode, and Android then highlights the first thing it finds, the widget.
     * Coming home, the highlight rests here instead, and only appears once an arrow is pressed.
     */
    private val focusPark by lazy {
        View(this).apply {
            isFocusable = true
            isFocusedByDefault = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            this@HomeActivity.findViewById<FrameLayout>(R.id.root).addView(this, 0, FrameLayout.LayoutParams(1, 1))
        }
    }

    /** Clears the highlight on the home screen (see [focusPark]). */
    private fun parkFocus() {
        if (drawerOpen || overlays.isShowing) return
        focusPark.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        running = this
        parkFocus()
        if (::smartSearch.isInitialized) preloadSearch()
        // Settings may have changed on the settings page.
        prefs.reload()
        WeatherWallpaper.refresh(this)
        Shuffle.next(this)
        val next = currentPalette()
        // The wallpaper may have changed too, which changes the app list's colors.
        if (next.accent != palette.accent || next.dark != palette.dark || next.drawer != palette.drawer) {
            palette = next
            applyTheme()
        }
        repo.setShape(prefs.iconShape)
        applyDarkMode()
        homeScreen.bindAll()
        updateResults()
        watchLimits()
        NotificationBadges.onChange = badgesChanged
        Updates.get(this).checkIfDue()
        offerSetup()
    }

    /** Notifications come and go while the home screen shows; a burst redraws once. */
    private val redrawBadges = Runnable {
        homeScreen.bindAll()
        updateResults()
    }
    private val badgesChanged = {
        home.removeCallbacks(redrawBadges)
        home.postDelayed(redrawBadges, 150)
        Unit
    }

    /** The first time: the welcome. Permissions are asked for later, where they're needed. */
    private fun offerSetup() {
        if (!overlays.isShowing) OnboardingActivity.showIfNeeded(this)
    }

    private val limits by lazy { AppLimits.get(this) }
    private val limitsChanged = Runnable {
        homeScreen.bindAll()
        updateResults()
        watchLimits()
    }

    /** Redraws app icons when a limit starts or ends while the home screen is showing. */
    private fun watchLimits() {
        home.removeCallbacks(limitsChanged)
        val now = System.currentTimeMillis()
        limits.nextChange(now)?.let { home.postDelayed(limitsChanged, it - now + 500) }
    }

    override fun onPause() {
        super.onPause()
        if (running === this) running = null
    }

    override fun onStop() {
        super.onStop()
        // The screen went off (the phone is locking): see applyDarkMode.
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) {
            dimAfterUnlock = true
            findViewById<View>(R.id.wall_dim).alpha = 0f
        }
        home.removeCallbacks(limitsChanged)
        home.removeCallbacks(redrawBadges)
        if (NotificationBadges.onChange === badgesChanged) NotificationBadges.onChange = null
        widgets.stopListening()
        hideDrawer(animate = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home.
        overlays.dismiss()
        homeScreen.finishResize()
        if (openFromKey(intent.getStringExtra(EXTRA_OPEN))) return
        if (!drawerOpen) homeScreen.pages.snapTo(0)
        hideDrawer()
        parkFocus()
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    private val newApps by lazy { NewApps.get(this) }

    private fun onAppsChanged() {
        newApps.update(repo.apps)
        if (SetupImport.applyPending(this, repo.apps, categories, prefs)) category = null
        homeScreen.onAppsChanged()
        updateResults()
    }

    /** The rest of a touch that ended a keypad move; it does nothing else. */
    private var touchEndedMove = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Touching the screen while moving something with the keypad puts it down where it is.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keyMover?.let { mover ->
            keyMover = null
            mover.done()
            touchEndedMove = true
        }
        if (touchEndedMove) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) touchEndedMove = false
            return true
        }
        if (tabDrag.handle(ev)) return true
        if (appDrag.handle(ev)) return true
        // A drag or resize on the home screen takes over the gesture once it starts.
        if (homeScreen.dispatchTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun cancelViewTouches() {
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // region Layout

    private fun setUpInsets() {
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            home.setPadding(home.paddingLeft, bars.top, home.paddingRight, bars.bottom)
            // The results keep filling the screen while the keyboard slides up over them (no blank
            // gap while it animates in); the list just gets room at its end, so the last results can
            // scroll above the keyboard. A sheet being typed in rises on its own instead.
            val listIme = if (overlays.isShowing) 0 else ime.bottom
            drawer.setPadding(drawer.paddingLeft, bars.top, drawer.paddingRight, bars.bottom)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, dp(12) + max(0, listIme - bars.bottom))
            overlays.insets = Rect(bars.left, bars.top, bars.right, bars.bottom)
            overlays.imeBottom = ime.bottom
            insets
        }
    }

    private fun setUpHome() {
        // The notification shade still opens from the top edge, as always; this is the rest of the screen.
        home.onSwipeDown = { if (!prefs.swipeDownSearch) expandNotifications() }
        // Smart Search follows the finger: the home screen slides down and fades as search fades in.
        // Both panels follow the finger: down for Smart Search, up for the app list.
        home.onPull = { dy ->
            when {
                dy > 0 && prefs.swipeDownSearch -> pullPanel(dy / dp(PANEL_PULL), search = true)
                dy < 0 -> pullPanel(-dy / dp(PANEL_PULL), search = false)
                else -> pullPanel(0f, searching)
            }
        }
        home.onPullEnd = { dy, flung ->
            val down = dy > 0
            when {
                down && !prefs.swipeDownSearch -> if (dy > dp(48)) expandNotifications()
                flung || kotlin.math.abs(dy) > dp(PANEL_PULL) / 2f -> {
                    // A flick that opened it before the halfway tick still gets one.
                    if (panelProgress < 0.5f) drawer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (down) openSearch() else openDrawer()
                }
                else -> settlePanel(open = false)
            }
        }
        home.onLongPress = { x, y -> homeMenu(Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())) }

    }

    // endregion

    // region App list and Smart Search
    //
    // One full-screen panel with two modes. Swiping up opens the app list: category pages
    // with a tab bar at the bottom (or every app on one page, in settings). Swiping down
    // opens Smart Search, which searches contacts and apps.

    private fun setUpDrawer() {
        adapter = DrawerAdapter(
            this,
            onContact = ::openContact,
            onDial = ::call,
            icon = { repo.icon(it) },
            digitFor = { KeyShortcuts.get(this).digitFor(it.key) },
            isLocked = { limits.isLocked(it.packageName) },
            isNew = { newApps.isNew(it) },
            badge = { prefs.badgeCount(it.packageName) },
            onClick = { entry, view -> choose(entry, view) },
            onLongClick = { entry, view ->
                // Only a finger's hold can turn into a drag; held with the keypad, the next touch would pick it up.
                if (pick == null && view.isInTouchMode) appDrag.arm(entry, view)
                appMenu(entry, view)
            },
        )
        adapter.palette = palette
        list.adapter = adapter
        list.itemsCanFocus = true

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                expanded.clear()
                updateResults()
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            adapter.primary?.let { choose(it, null) }
            true
        }
        searchClear.setOnClickListener { search.setText("") }
        findViewById<View>(R.id.drawer_search).setOnClickListener { openSearch() }
        findViewById<View>(R.id.drawer_more).setOnClickListener { appListMenu(it) }

        // Contacts and shortcuts are loaded ahead of time (on start and on return), so search opens
        // complete in one go; later changes only redraw if something actually changed.
        contacts.onLoaded = { if (searching) updateResults() }
        preloadSearch()

        // Closing follows the finger too: down on the app list, up on Smart Search.
        drawer.onPull = { dy ->
            val closing = if (searching) -dy else dy
            if (drawerOpen) {
                stopSlide()
                applyPanelProgress(1f - (closing / dp(PANEL_PULL)).coerceIn(0f, 1f), home = true)
            }
        }
        drawer.onPullEnd = { dy, flung ->
            val closing = if (searching) -dy else dy
            if (flung || closing > dp(PANEL_PULL) / 2f) hideDrawer() else settlePanel(open = true)
        }
        home.canSteal = { !homeScreen.isBusy }
        drawer.canSteal = { !homeScreen.isBusy && !overlays.isShowing && !tabDrag.armed && !appDrag.armed }
        // The category page follows a sideways drag; let go past a third (or flick) to change.
        drawer.onSlide = { dx -> if (!searching && !showingHidden && prefs.drawerCategories) slideCategory(dx) }
        drawer.onSlideEnd = { dx, flung -> if (!searching && !showingHidden && prefs.drawerCategories) endSlide(dx, flung) }
        // Closing needs the list to be at its edge when the finger goes down, so a swipe that
        // scrolls back to the top stops there; it takes a new swipe to close.
        drawer.onDown = {
            listAtTop = !list.canScrollVertically(-1)
            listAtBottom = !list.canScrollVertically(1)
        }
        drawer.canDrag = { dy -> if (searching) dy < 0 && listAtBottom else dy > 0 && listAtTop }
    }

    /** Swipe up: the app list. */
    private fun openDrawer() {
        searching = false
        pick = null
        keypadQuery = false
        search.setText("")
        if (category == null) category = visibleCategories().firstOrNull()?.id
        showPanel(fromTop = false)
        updateResults()
    }

    /**
     * Swipe down (or the search button, or a number key): Smart Search.
     * With [onPick], it's choosing an app for something instead of opening it.
     */
    private fun openSearch(
        query: String = "",
        fromKeypad: Boolean = false,
        hint: String = "Search apps, contacts and more",
        onPick: ((AppEntry) -> Unit)? = null,
    ) {
        searching = true
        pick = onPick
        keypadQuery = fromKeypad
        expanded.clear()
        search.hint = hint
        search.setText(query)
        search.setSelection(query.length)
        showPanel(fromTop = !drawerOpen)
        updateResults()
        if (fromKeypad) {
            // Opened from the keypad: the search bar has the highlight; the number keys type into it
            // (with T9) and Down goes into the results. No on-screen keyboard.
            // The bar, not the text box: a focused text box sends every key to the on-screen
            // keyboard app first, which then pops up. The launcher types the digits in itself.
            search.post { findViewById<View>(R.id.search_bar).requestFocusFromTouch() }
        } else {
            search.requestFocus()
            search.post { getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    /** Shows search part-way, [progress] 0 (home) to 1 (search), while a downward pull is under way. */
    /**
     * Shows a panel part-way while the finger drags: [progress] 0 (home) to 1 (open).
     * [search] is Smart Search (pulled down); otherwise the app list (pulled up).
     */
    private fun pullPanel(progress: Float, search: Boolean) {
        val f = progress.coerceIn(0f, 1f)
        if (searching != search || drawer.visibility != View.VISIBLE) {
            searching = search
            pick = null
            this.search.setText("")
            this.search.hint = "Search apps, contacts and more"
            if (!search && category == null) category = visibleCategories().firstOrNull()?.id
            setPanelLook()
            updateResults()
            panelAnimator?.cancel()
            drawer.animate().cancel()
            drawer.visibility = View.VISIBLE
        }
        applyPanelProgress(f)
    }

    private var panelProgress = 0f
    private var panelAnimator: android.animation.ValueAnimator? = null

    /**
     * Opening a panel, from 0 (home) to 1 (open), in two steps so they never overlap: first the
     * home screen slides away and fades under the growing tint, then the panel fades in on top.
     * Search comes from above (home moves down); the app list from below (home moves up).
     */
    private fun applyPanelProgress(f: Float, home: Boolean = false) {
        if (home) this.home.visibility = View.VISIBLE
        // A light tick when a drag passes the point where letting go opens (or closes) the panel.
        if ((panelProgress < 0.5f) != (f < 0.5f) && panelAnimator?.isRunning != true) {
            drawer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        panelProgress = f
        val first = (f / 0.5f).coerceIn(0f, 1f)
        val second = ((f - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val direction = if (searching) 1 else -1
        this.home.translationY = direction * first * dp(64)
        this.home.alpha = 1f - first
        drawer.alpha = 1f
        drawer.translationY = 0f
        drawer.background?.alpha = (first * 255).toInt()
        for (i in 0 until drawer.childCount) {
            drawer.getChildAt(i).apply {
                alpha = second
                translationY = -direction * (1f - second) * dp(24)
            }
        }
    }

    /** Finishes a pull or an open/close: animates to fully open, or back to the home screen. */
    private fun settlePanel(open: Boolean) {
        panelAnimator?.cancel()
        val target = if (open) 1f else 0f
        panelAnimator = android.animation.ValueAnimator.ofFloat(panelProgress, target).apply {
            // Fast start, long gentle landing (Material's emphasized decelerate curve).
            duration = (380 * kotlin.math.abs(target - panelProgress)).toLong().coerceIn(140, 380)
            interpolator = PANEL_EASING
            addUpdateListener { applyPanelProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                // cancel() also calls onAnimationEnd; a cancelled open must not hide the home screen.
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled || panelAnimator !== animation) return
                    if (open) {
                        home.visibility = View.INVISIBLE
                    } else {
                        drawer.visibility = View.GONE
                        resetPanelProgress()
                    }
                }
            })
            start()
        }
    }

    /** Puts everything back to its resting state. */
    private fun resetPanelProgress() {
        panelProgress = 0f
        home.visibility = View.VISIBLE
        home.translationY = 0f
        home.alpha = 1f
        drawer.background?.alpha = 255
        for (i in 0 until drawer.childCount) drawer.getChildAt(i).apply { alpha = 1f; translationY = 0f }
    }

    private fun setPanelLook() {
        findViewById<View>(R.id.search_bar).visibility = if (searching) View.VISIBLE else View.GONE
        findViewById<View>(R.id.drawer_header).visibility = if (searching) View.GONE else View.VISIBLE
        // Both panels sit on the same see-through wallpaper tint.
        drawer.setBackgroundColor(palette.searchScrim)
    }

    private fun showPanel(fromTop: Boolean) {
        setPanelLook()
        home.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        if (!drawerOpen && panelProgress == 0f) applyPanelProgress(0f)
        drawerOpen = true
        settlePanel(open = true)
    }

    override fun returnToDrawer(entry: AppEntry, x: Float, y: Float) {
        category = categories.of(entry)
        openDrawer()
        appDrag.resume(entry, x, y)
    }

    override fun closeDrawer(animate: Boolean) {
        hideDrawer(animate)
    }

    private fun hideDrawer(animate: Boolean = true) {
        // The All apps view opens on every app again next time.
        chipCategory = null
        if (!drawerOpen) return
        drawerOpen = false
        showingHidden = false
        pick = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        home.visibility = View.VISIBLE
        drawer.animate().cancel()
        stopSlide()
        if (animate) {
            // Reverse of opening: the panel fades away, then the home screen comes back as the tint lifts.
            settlePanel(open = false)
            // Search stays marked open until it has finished leaving, so it leaves the way it came
            // (upward); after that, the next swipe or shortcut starts fresh.
            drawer.postDelayed({
                if (!drawerOpen) {
                    searching = false
                    search.setText("")
                }
            }, 320)
            // Closing takes the highlight with it; it doesn't jump to something on the home screen.
            parkFocus()
            return
        }
        panelAnimator?.cancel()
        resetPanelProgress()
        drawer.visibility = View.GONE
        searching = false
        search.setText("")
        parkFocus()
    }

    private fun visibleCategories(): List<Categories.Item> {
        val used = repo.apps.filter { it.key !in prefs.hidden }.map { categories.of(it) }.toSet()
        // Categories you made always show; automatic ones only when they have apps.
        return categories.list.filter { it.custom || it.id in used }
    }

    /** The category next to the current one in direction [by], if any. */
    private fun neighbor(by: Int): Categories.Item? {
        val shown = visibleCategories()
        return shown.getOrNull(shown.indexOfFirst { it.id == category } + by)
    }

    private fun slideCategory(dx: Float) {
        list.animate().cancel()
        // Moves with the finger; past the first or last category it resists.
        val hasNext = neighbor(if (dx < 0) 1 else -1) != null
        list.translationX = if (hasNext) dx * 0.6f else dx * 0.2f
        list.alpha = 1f - (kotlin.math.abs(list.translationX) / list.width).coerceIn(0f, 0.6f)
    }

    private fun endSlide(dx: Float, flung: Boolean) {
        val by = if (dx < 0) 1 else -1
        val next = neighbor(by)
        if (next == null || (!flung && kotlin.math.abs(dx) < list.width / 3f)) {
            list.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(PANEL_EASING).start()
            return
        }
        slideTo(next.id, -by * list.width * 0.35f)
    }

    /** The category a slide is on its way to, until it gets there. */
    private var slidingTo: String? = null

    /** Slides the list out toward [out], switches to [next], and slides it back in from the other side. */
    private fun slideTo(next: String, out: Float, then: () -> Unit = {}) {
        slidingTo = next
        list.animate().translationX(out).alpha(0f).setDuration(110).setInterpolator(DecelerateInterpolator()).withEndAction {
            slidingTo = null
            category = next
            updateResults()
            list.translationX = -out
            list.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(PANEL_EASING).start()
            then()
        }.start()
    }

    /**
     * Ends a category slide at once, landing on where it was going. The panel closing fades the
     * list itself, and a slide still running would keep bringing it back over the home screen.
     */
    private fun stopSlide() {
        list.animate().cancel()
        list.translationX = 0f
        slidingTo?.let {
            slidingTo = null
            category = it
            updateResults()
        }
    }

    private fun addApps(rows: MutableList<DrawerAdapter.Row>, apps: List<AppEntry>) {
        if (prefs.drawerGrid) apps.chunked(DrawerAdapter.COLS).forEach { rows += DrawerAdapter.Row.Tiles(it) }
        else apps.forEach { rows += DrawerAdapter.Row.Line(it) }
    }

    /** What the list was last showing; a refresh of the same thing keeps the scroll and highlight. */
    private var shownKey = ""

    private fun updateResults(keepFocus: Boolean = true) {
        if (!::adapter.isInitialized) return
        val key = "$searching|$showingHidden|$category|$chipCategory|${search.text}|${prefs.drawerCategories}"
        // Results arrive in stages (contacts, shortcuts, files); each refresh rebuilds the rows,
        // so the highlighted item is found again afterwards instead of being lost.
        val focused = currentFocus?.takeIf { keepFocus && isInside(it, list) }
        val keep = focused?.let { f ->
            var row: View = f
            while (row.parent !== list) row = row.parent as? View ?: return@let null
            list.getPositionForView(row) to rectOf(f).centerX()
        }
        if (searching) updateSearch() else updateAppList()
        if (key != shownKey) {
            shownKey = key
            list.setSelection(0)
        } else if (keep != null) {
            list.post {
                val position = (keep.first until adapter.count).firstOrNull(::canFocusRow)
                    ?: (keep.first downTo 0).firstOrNull(::canFocusRow) ?: return@post
                focusRow(position, keep.second, true)
            }
        }
    }

    private fun updateAppList() {
        // Nothing is being searched: no letters picked out in app names.
        adapter.query = ""
        val visible = repo.apps.filter { it.key !in prefs.hidden }
        val rows = ArrayList<DrawerAdapter.Row>()
        val title = findViewById<TextView>(R.id.drawer_title)
        val tabsScroll = findViewById<View>(R.id.tabs_scroll)
        if (showingHidden) {
            // Its own page, like a category, but no tab is selected.
            title.text = "Hidden apps"
            val apps = repo.apps.filter { it.key in prefs.hidden }
            if (apps.isEmpty()) rows += DrawerAdapter.Row.Title("No hidden apps. Hold an app in the app list and choose Hide from app list.")
            addApps(rows, apps)
            tabsScroll.visibility = if (prefs.drawerCategories) View.VISIBLE else View.GONE
            if (prefs.drawerCategories) renderTabs(visibleCategories())
        } else if (prefs.drawerCategories) {
            val shown = visibleCategories()
            val current = shown.firstOrNull { it.id == category } ?: shown.firstOrNull()
            category = current?.id
            title.text = current?.name ?: "Apps"
            val apps = current?.let { c -> categories.sorted(c.id, visible.filter { categories.of(it) == c.id }) }.orEmpty()
            if (apps.isEmpty()) {
                rows += DrawerAdapter.Row.Title("No apps here yet. Hold an app in the app list, open its Category menu, and choose this category.")
            }
            addApps(rows, apps)
            tabsScroll.visibility = View.VISIBLE
            renderTabs(shown)
        } else {
            val shown = visibleCategories()
            val chosen = shown.firstOrNull { it.id == chipCategory }
            chipCategory = chosen?.id
            title.text = chosen?.name ?: "All apps"
            addApps(rows, if (chosen == null) visible else categories.sorted(chosen.id, visible.filter { categories.of(it) == chosen.id }))
            tabsScroll.visibility = View.GONE
            renderChips(shown)
        }
        chipsScroll.visibility = if (!prefs.drawerCategories && !showingHidden) View.VISIBLE else View.GONE
        adapter.set(rows, null)
    }

    /** In the All apps view, the category picked from the chips; null shows every app. */
    private var chipCategory: String? = null
    private val chipsScroll by lazy {
        findViewById<View>(R.id.chips_scroll).apply {
            // Out past the app list's side padding, so the row scrolls from screen edge to screen edge.
            val inset = (parent as View).paddingLeft
            (layoutParams as ViewGroup.MarginLayoutParams).apply {
                leftMargin = -inset
                rightMargin = -inset
            }
            requestLayout()
        }
    }

    /** "All" and then every category, as pills; the chosen one is filled. */
    private fun renderChips(shown: List<Categories.Item>) {
        val chips = findViewById<LinearLayout>(R.id.chips)
        chips.removeAllViews()
        val options = listOf<Categories.Item?>(null) + shown
        options.forEach { c ->
            val selected = c?.id == chipCategory
            val fg = if (selected) palette.drawer or 0xFF000000.toInt() else palette.onWall
            chips.addView(LinearLayout(this).apply {
                tag = c?.id
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(if (c == null) 16 else 12), 0, dp(16), 0)
                background = Ui.rounded(this@HomeActivity, if (selected) palette.onWall else palette.glass, Ui.RADIUS_PILL)
                // On the filled (light) chip the ring takes the text's dark color so it still shows.
                foreground = Ui.pressable(this@HomeActivity, Ui.RADIUS_PILL, focusColor = fg)
                isFocusable = true
                if (c != null) addView(ImageView(this@HomeActivity).apply {
                    setImageResource(if (selected) c.filledIconRes else c.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(fg)
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(Ui.SPACE_S) })
                addView(TextView(this@HomeActivity).apply {
                    text = c?.name ?: "All"
                    style(TextStyle.Label, fg)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                setOnClickListener {
                    chipCategory = c?.id
                    updateResults()
                    list.setSelection(0)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
    }

    /**
     * Smart Search: contacts, apps, app shortcuts, phone settings and files, each its own section
     * with a few results and "Show more". Empty, it shows favorite contacts and frequent apps.
     */
    private fun updateSearch() {
        findViewById<View>(R.id.tabs_scroll).visibility = View.GONE
        chipsScroll.visibility = View.GONE
        val raw = search.text.toString().trim()
        val rows = ArrayList<DrawerAdapter.Row>()
        val pickingApp = pick != null
        var sections = 0

        fun section(key: String, title: String, total: Int, shown: Int, add: () -> Unit) {
            if (total == 0) return
            if (sections > 0) rows += DrawerAdapter.Row.Divider
            sections++
            val more = total > shown && key !in expanded
            rows += DrawerAdapter.Row.Title(title, if (more) "Show more" else null) {
                expanded += key
                updateResults()
            }
            add()
        }
        fun <T> limit(key: String, list: List<T>, n: Int) = if (key in expanded) list else list.take(n)

        // Numbers typed in search can be called straight away, like on a dial pad.
        if (!pickingApp && raw.matches(DIALABLE)) {
            rows += DrawerAdapter.Row.Dial(raw)
            sections++
        }

        if (!pickingApp) {
            if (contacts.allowed) {
                val found = if (raw.isEmpty()) contacts.favorites else contacts.search(raw)
                section("contacts", if (raw.isEmpty()) "Favorites" else "Contacts", found.size, CONTACTS_SHOWN) {
                    limit("contacts", found, CONTACTS_SHOWN).forEach { rows += DrawerAdapter.Row.Person(it) }
                }
            } else if (raw.isEmpty()) {
                if (sections > 0) rows += DrawerAdapter.Row.Divider
                sections++
                rows += DrawerAdapter.Row.Title("Contacts")
                rows += DrawerAdapter.Row.Notice("Allow contact search", "Allow") {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
                }
            }
        }

        val apps = if (raw.isEmpty()) {
            repo.apps.filter { it.key !in prefs.hidden }
                .sortedByDescending { prefs.launchCount(it.key) }
                .let { if (pickingApp) it.sortedBy { e -> e.label.lowercase() } else it }
        } else {
            // Hidden apps are only on the hidden apps page, never in search.
            val t9 = Search.isT9(raw)
            val query = if (t9) raw else Search.normalizeText(raw)
            repo.apps
                .filter { it.key !in prefs.hidden }
                .mapNotNull { e -> Search.score(query, if (t9) e.digitWords else e.words)?.let { e to it } }
                .sortedWith(compareBy<Pair<AppEntry, Int>> { it.second }.thenByDescending { prefs.launchCount(it.first.key) })
                .map { it.first }
        }
        val appsShown = if (pickingApp) apps.size else DrawerAdapter.COLS * (if (raw.isEmpty()) 2 else 1)
        section("apps", if (raw.isEmpty() && !pickingApp) "Frequent apps" else "Apps", apps.size, appsShown) {
            addApps(rows, limit("apps", apps, appsShown))
        }

        if (raw.isNotEmpty() && !pickingApp) {
            val hiddenPackages = prefs.hidden.mapNotNull { repo.find(it)?.packageName }.toSet()
            val shortcutsFound = smartSearch.shortcuts(raw, hiddenPackages)
            section("shortcuts", "Shortcuts", shortcutsFound.size, RESULTS_SHOWN) {
                limit("shortcuts", shortcutsFound, RESULTS_SHOWN).forEach { rows += DrawerAdapter.Row.Result(it) }
            }
            val settingsFound = smartSearch.settings(raw)
            section("settings", "Settings", settingsFound.size, RESULTS_SHOWN) {
                limit("settings", settingsFound, RESULTS_SHOWN).forEach { rows += DrawerAdapter.Row.Result(it) }
            }
            val media = mediaResults.takeIf { mediaFor == raw }.orEmpty()
            section("media", "Files", media.size, RESULTS_SHOWN) {
                limit("media", media, RESULTS_SHOWN).forEach { rows += DrawerAdapter.Row.Result(it) }
            }
            if (!smartSearch.mediaAllowed && raw.length >= 2) {
                if (sections > 0) rows += DrawerAdapter.Row.Divider
                sections++
                rows += DrawerAdapter.Row.Title("Files")
                rows += DrawerAdapter.Row.Notice("Allow photo, video and music search", "Allow") {
                    requestPermissions(smartSearch.mediaPermissions, REQUEST_MEDIA)
                }
            }
            if (mediaFor != raw) {
                smartSearch.media(raw, MEDIA_LIMIT) { found ->
                    mediaFor = raw
                    mediaResults = found
                    if (searching && search.text.toString().trim() == raw) updateSearch()
                }
            }
        }

        if (raw.isNotEmpty() && sections == 0) rows += DrawerAdapter.Row.Title("Nothing matches \"$raw\"")
        adapter.query = raw
        adapter.set(rows, if (raw.isNotEmpty()) apps.firstOrNull() else null)
    }

    /** The category icons along the bottom; long-press one to edit it. */
    private fun renderTabs(shown: List<Categories.Item>) {
        val tabs = findViewById<LinearLayout>(R.id.tabs)
        // Same proportions as the dock: its corner is the same share of its height.
        tabs.background = Ui.rounded(this, palette.glass, Ui.RADIUS_BAR * TAB_BAR_HEIGHT / Ui.DOCK_HEIGHT, palette.hairline)
        tabs.removeAllViews()
        // Tabs share the bar's width; with more than fit, they keep a minimum size and the bar scrolls.
        val fits = shown.size <= MAX_FITTING_TABS
        // Categories holding an app that's new and not opened yet get a dot, like the app itself.
        val withNew = repo.apps.filter { newApps.isNew(it) && it.key !in prefs.hidden }.map { categories.of(it) }.toSet()
        shown.forEach { c ->
            val selected = c.id == category && !showingHidden
            tabs.addView(FrameLayout(this).apply {
                isFocusable = true
                tag = c.id
                contentDescription = c.name
                foreground = Ui.pressable(this@HomeActivity, Ui.RADIUS_L, focusColor = palette.onWall)
                addView(ImageView(this@HomeActivity).apply {
                    setImageResource(if (selected) c.filledIconRes else c.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (selected) palette.onWall else palette.onWallSecondary)
                }, FrameLayout.LayoutParams(dp(24), dp(24), android.view.Gravity.CENTER))
                if (c.id in withNew) addView(View(this@HomeActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFF2F4F3.toInt())
                    }
                    // On the icon's top corner.
                    translationX = dp(11).toFloat()
                    translationY = -dp(11).toFloat()
                }, FrameLayout.LayoutParams(dp(8), dp(8), android.view.Gravity.CENTER))
                setOnClickListener {
                    category = c.id
                    showingHidden = false
                    updateResults()
                }
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (it.isInTouchMode) tabDrag.arm(c.id, it)
                    categoryMenu(c, it)
                    true
                }
            }, if (fits) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Reordering categories: after a long press on a tab, moving the finger closes the menu
     * and drags the tab along the bar; the others make room as it passes them.
     */
    private val tabDrag = object {
        var armed = false
        private var id: String? = null
        private var active = false
        private var downX = 0f
        private var grab = 0f
        private val slop by lazy { android.view.ViewConfiguration.get(this@HomeActivity).scaledTouchSlop }

        fun arm(categoryId: String, tab: View) {
            armed = true
            id = categoryId
            downX = lastTouchX
            grab = lastTouchX - rectOf(tab).centerX()
        }

        /** Returns true while it owns the gesture. */
        fun handle(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                lastTouchX = ev.rawX
                lastTouchY = ev.rawY
            }
            if (!armed) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!active && kotlin.math.abs(ev.rawX - downX) > slop) {
                        active = true
                        overlays.dismiss()
                        cancelViewTouches()
                    }
                    if (active) follow(ev.rawX)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasActive = active
                    armed = false
                    active = false
                    if (wasActive) {
                        updateResults()
                    }
                    return wasActive
                }
            }
            return active
        }

        private fun follow(x: Float) {
            val tabs = findViewById<LinearLayout>(R.id.tabs)
            val shown = visibleCategories()
            val from = shown.indexOfFirst { it.id == id }
            if (from < 0 || tabs.childCount == 0) return
            val bar = rectOf(tabs)
            val tabWidth = bar.width().toFloat() / tabs.childCount
            val to = ((x - grab - bar.left) / tabWidth).toInt().coerceIn(0, shown.size - 1)
            if (to != from) {
                val order = shown.map { it.id }.toMutableList()
                order.add(to, order.removeAt(from))
                categories.reorder(order)
                renderTabs(visibleCategories())
            }
            val view = tabs.getChildAt(to) ?: return
            // Offset from where the tab sits in the bar (worked out from its slot, since the
            // bar may not have laid out again yet after a reorder).
            view.translationX = x - grab - (bar.left + tabWidth * (to + 0.5f))
            view.scaleX = 1.15f
            view.scaleY = 1.15f
        }
    }
    private var lastTouchX = 0f

    /**
     * Dragging an app in the app list. After a long press, moving the finger closes the menu and
     * lifts the icon: the other apps in the category make room as it moves (that order is kept),
     * holding it over a category tab moves it into that category, and the target at the top
     * hands it over to the home screen to be placed there.
     */
    private val appDrag = object {
        var armed = false
        private var entry: AppEntry? = null
        private var tile: View? = null
        private var active = false
        private var downX = 0f
        private var downY = 0f
        private var floating: ImageView? = null
        private var zone: DropZone? = null
        private var hoverTab: String? = null
        private val slop by lazy { android.view.ViewConfiguration.get(this@HomeActivity).scaledTouchSlop }
        private val root by lazy { findViewById<FrameLayout>(R.id.root) }
        private val switchTab = Runnable { moveToCategory() }

        fun arm(app: AppEntry, view: View) {
            armed = true
            entry = app
            tile = view
            downX = lastTouchX
            downY = lastTouchY
        }

        fun handle(ev: MotionEvent): Boolean {
            if (!armed) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (!active && kotlin.math.hypot(ev.rawX - downX, ev.rawY - downY) > slop) start()
                    if (active) move(ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasActive = active
                    if (active) finish()
                    armed = false
                    return wasActive
                }
            }
            return active
        }

        /** Picks the drag back up after the home screen handed the app back. */
        fun resume(app: AppEntry, x: Float, y: Float) {
            armed = true
            entry = app
            tile = null
            start()
            move(x, y)
        }

        private fun start() {
            active = true
            overlays.dismiss()
            cancelViewTouches()
            val app = entry ?: return
            val size = dp(54)
            floating = ImageView(this@HomeActivity).apply {
                setImageBitmap(repo.icon(app))
                elevation = dp(12).toFloat()
                scaleX = 1.1f
                scaleY = 1.1f
                root.addView(this, FrameLayout.LayoutParams(size, size))
            }
            adapter.dragging = app
            adapter.notifyDataSetChanged()
            // Slots are measured once the list has redrawn with the dragged tile left empty.
            list.post { if (active && order == null) captureSlots() }
            showZone()
        }

        private fun move(x: Float, y: Float) {
            val view = floating ?: return
            view.x = x - view.width / 2f
            view.y = y - view.height / 2f

            // Over the top target: hold to hand the app to the home screen.
            val overZone = overTop(y)
            zone?.hover(overZone)
            if (overZone) return

            // Over a category tab: after a short hold, move the app there.
            val tabs = findViewById<LinearLayout>(R.id.tabs)
            val tabsBox = rectOf(findViewById(R.id.tabs_scroll))
            var overTab: String? = null
            if (!searching && prefs.drawerCategories && tabsBox.contains(x.toInt(), y.toInt())) {
                val shown = visibleCategories()
                for (i in 0 until tabs.childCount) {
                    if (rectOf(tabs.getChildAt(i)).contains(x.toInt(), y.toInt())) overTab = shown.getOrNull(i)?.id
                }
            }
            if (overTab != hoverTab) {
                hoverTab = overTab
                root.removeCallbacks(switchTab)
                if (overTab != null && overTab != category) root.postDelayed(switchTab, 350)
            }
            if (overTab != null) return

            // Over the grid: make room where the finger is.
            if (searching || !prefs.drawerCategories) return
            reorderAt(x, y)
        }

        /**
         * Reordering while dragging: the tiles stay where they are (nothing is rebuilt) and slide
         * into their new slots. [order] is the order being built; [slots] are the tiles' places
         * on screen when the drag began, in that order. It's saved when the app is let go.
         */
        private var order: MutableList<String>? = null
        private var slots: List<android.graphics.Rect> = emptyList()
        private var views: Map<String, View> = emptyMap()
        private var home: Map<String, android.graphics.Rect> = emptyMap()

        private fun captureSlots() {
            val app = entry ?: return
            if (list.isLayoutRequested) return
            val apps = currentApps()
            views = tileViews()
            home = views.mapValues { (_, v) -> rectOf(v) }
            val keys = apps.map { it.key }
            // Only apps on screen can be moved among; the order keeps the rest as they were.
            slots = keys.mapNotNull { home[it] }
            order = keys.filter { it in home }.toMutableList().takeIf { app.key in it }
        }

        private fun reorderAt(x: Float, y: Float) {
            val app = entry ?: return
            if (order == null) captureSlots()
            val current = order ?: return
            // The list can redraw its rows mid-drag (new tile views); follow the ones on screen,
            // already in their current slots, so what's seen keeps up with the order.
            if (views.values.any { !it.isAttachedToWindow }) {
                views = tileViews()
                current.forEachIndexed { i, key ->
                    val view = views[key] ?: return@forEachIndexed
                    val start = home[key] ?: return@forEachIndexed
                    val slot = slots.getOrNull(i) ?: return@forEachIndexed
                    view.translationX = (slot.left - start.left).toFloat()
                    view.translationY = (slot.top - start.top).toFloat()
                }
            }
            // Well inside a slot (its middle 60%), so the edge between two never flips back and forth.
            val target = slots.indexOfFirst { r ->
                val dx = r.width() * 0.2f
                val dy = r.height() * 0.2f
                x > r.left + dx && x < r.right - dx && y > r.top + dy && y < r.bottom - dy
            }
            val from = current.indexOf(app.key)
            if (target < 0 || target == from) return
            current.add(target, current.removeAt(from))
            current.forEachIndexed { i, key ->
                val view = views[key] ?: return@forEachIndexed
                val start = home[key] ?: return@forEachIndexed
                val slot = slots.getOrNull(i) ?: return@forEachIndexed
                view.animate().translationX((slot.left - start.left).toFloat()).translationY((slot.top - start.top).toFloat())
                    .setDuration(180).setInterpolator(PANEL_EASING).start()
            }
        }

        /** Saves the order made while dragging, putting apps not on screen back where they were. */
        private fun commitOrder() {
            val made = order ?: return
            val id = category ?: return
            val all = currentApps().map { it.key }
            val onScreen = made.toSet()
            val iterator = made.iterator()
            categories.setOrder(id, all.map { if (it in onScreen) iterator.next() else it })
        }

        private fun resetSlots() {
            views.values.forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
            order = null
            slots = emptyList()
            views = emptyMap()
            home = emptyMap()
        }

        /** Every app tile showing in the list, by app. */
        private fun tileViews(): Map<String, View> {
            val found = HashMap<String, View>()
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i) as? ViewGroup ?: continue
                for (j in 0 until row.childCount) {
                    val tile = row.getChildAt(j)
                    (tile.tag as? AppEntry)?.let { found[it.key] = tile }
                }
                (row.tag as? AppEntry)?.let { found[it.key] = row }
            }
            return found
        }

        /** Where each app tile is on screen. */
        private fun tilePositions(): Map<String, Pair<Int, Int>> = tileViews().mapValues { (_, v) ->
            val r = rectOf(v)
            r.left to r.top
        }

        private fun currentApps(): List<AppEntry> {
            val id = category ?: return emptyList()
            return categories.sorted(id, repo.apps.filter { it.key !in prefs.hidden && categories.of(it) == id })
        }

        private fun moveToCategory() {
            val app = entry ?: return
            val id = hoverTab ?: return
            categories.assign(app, id)
            // Arrives at the end of its new category.
            categories.setOrder(id, currentAppsIn(id).map { it.key }.filter { it != app.key } + app.key)
            category = id
            resetSlots()
            updateResults()
        }

        private fun currentAppsIn(id: String) =
            categories.sorted(id, repo.apps.filter { it.key !in prefs.hidden && categories.of(it) == id })

        private fun toHome() {
            val app = entry ?: return
            val view = tile ?: list
            cleanUp()
            armed = false
            active = false
            // The home screen's own drag takes it from the next move, so the app can be placed anywhere.
            homeScreen.continueDragFromDrawer(app, view)
        }

        private fun finish() {
            commitOrder()
            cleanUp()
            active = false
            updateResults()
        }

        private fun cleanUp() {
            resetSlots()
            root.removeCallbacks(switchTab)
            hoverTab = null
            floating?.let(root::removeView)
            floating = null
            zone?.dismiss()
            zone = null
            adapter.dragging = null
            adapter.notifyDataSetChanged()
        }

        /** The home screen's edge, peeking down at the top of the screen as an outlined sliver. */
        private fun showZone() {
            val box = DropZone(this@HomeActivity, 0, 0xFFFFFFFF.toInt(), Ui.RADIUS_BAR)
            box.onTrigger = ::toHome
            val height = dp(80)
            root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                setMargins(dp(Ui.BAR_MARGIN), overlays.insets.top + dp(6) - height, dp(Ui.BAR_MARGIN), 0)
            })
            zone = box
        }

        /** Over the home screen's sliver: the very top of the screen. */
        private fun overTop(y: Float) = zone != null && y < overlays.insets.top + dp(28)
    }
    private var lastTouchY = 0f

    /** Re-renders the category page without jumping back to the top. */
    private fun updateAppListKeepingScroll() {
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        updateAppList()
        list.setSelectionFromTop(first, top)
    }

    private fun appListMenu(anchor: View) = overlays.showMenu(null, listOf(
        MenuItem("Add category", R.drawable.ic_add) { newCategory(null) },
        MenuItem("Hidden apps", R.drawable.ic_visibility_off) { openHiddenApps() },
        MenuItem("Settings", R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) },
    ), rectOf(anchor))

    private fun categoryMenu(c: Categories.Item, tab: View) {
        val items = listOf(
            MenuItem("Add category", R.drawable.ic_add) { newCategory(null) },
            MenuItem("Move", R.drawable.ic_move) { moveCategoryWithKeys(c) },
            MenuItem("Edit icon", R.drawable.ic_cat_category) { iconSheet(c) },
            MenuItem("Rename", R.drawable.ic_edit) {
                overlays.showInput("Rename category", c.name, "Name") {
                    categories.rename(c.id, it)
                    updateResults()
                }
            },
        )
        val remove = MenuItem("Remove", R.drawable.ic_delete, danger = true) {
            categories.delete(c.id)
            if (category == c.id) category = null
            updateResults()
        }
        overlays.showMenu(c.name, listOf(items, listOf(remove)), rectOf(tab), iconRes = c.iconRes)
    }

    /** A grid of icons to pick from for a category. */
    private fun iconSheet(c: Categories.Item) {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_L))
        }
        Categories.ICONS.entries.chunked(6).forEach { rowIcons ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 6) {
                val entry = rowIcons.getOrNull(i)
                row.addView(if (entry == null) android.widget.Space(this) else ImageView(this).apply {
                    val selected = entry.key == c.icon
                    setImageResource(entry.value)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (selected) palette.onAccent else palette.text)
                    scaleType = ImageView.ScaleType.CENTER
                    background = Ui.rounded(this@HomeActivity, if (selected) palette.accent else palette.surfaceHigh, Ui.RADIUS_M)
                    foreground = Ui.pressable(this@HomeActivity, Ui.RADIUS_M)
                    isFocusable = true
                    setOnClickListener {
                        categories.setIcon(c.id, entry.key)
                        overlays.dismiss()
                        updateResults()
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            grid.addView(row)
        }
        overlays.showSheet("Icon for ${c.name}", grid)
    }

    /** The hidden apps page, behind the PIN if there is one. */
    private fun openHiddenApps() {
        val show = {
            showingHidden = true
            if (!drawerOpen) openDrawer()
            updateResults()
        }
        val pin = prefs.hiddenPin
        if (pin != null) PinPad.ask(this, palette, overlays, pin, show) else show()
    }

    /** Number keys, Back-delete and OK go to the PIN pad while it's open. */
    private fun pinKey(event: KeyEvent): Boolean {
        val pad = PinPad.active ?: return false
        if (!overlays.isShowing) return false
        val digit = digitOf(event.keyCode)
        val handled = digit != null || event.keyCode == KeyEvent.KEYCODE_DEL ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (handled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when {
                digit != null -> pad.type('0' + digit)
                event.keyCode == KeyEvent.KEYCODE_DEL -> pad.delete()
                else -> pad.confirm()
            }
        }
        return handled
    }

    /** Asks for a name and makes a category; with [entry], moves that app into it. */
    private fun newCategory(entry: AppEntry?) {
        overlays.showInput("New category", "", "Name") { name ->
            val created = categories.add(name)
            entry?.let { categories.assign(it, created.id) }
            category = created.id
            updateResults()
        }
    }

    /** Lets you pick which category an app shows under. */
    private fun categorySheet(entry: AppEntry) {
        val current = categories.of(entry)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_M))
        }
        categories.list.forEach { c ->
            column.addView(Components.row(this, palette, c.iconRes, c.name,
                value = if (c.id == current) "Current" else null) {
                categories.assign(entry, c.id)
                overlays.dismiss()
                updateResults()
            })
        }
        column.addView(Components.row(this, palette, R.drawable.ic_add, "New category", color = palette.accent) {
            newCategory(entry)
        })
        overlays.showSheet("Category for ${entry.label}", android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        })
    }

    private fun openContact(contact: Contacts.Contact, action: ContactAction) {
        when (action) {
            ContactAction.CALL -> call(contact.number)
            ContactAction.TEXT -> startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null)))
            ContactAction.EMAIL -> contact.email?.let { startSafely(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", it, null))) }
            ContactAction.OPEN -> startSafely(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, contact.id)))
        }
    }

    /** A number waiting for permission to call it. */
    private var pendingCall: String? = null

    /**
     * Calls [number] right away. The first time, asks for permission to make calls; if that's
     * refused, the number opens in the phone app to call from there.
     */
    private fun call(number: String) {
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSafely(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        } else {
            pendingCall = number
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), REQUEST_CALL)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA) {
            mediaFor = null
            updateResults()
        }
        if (requestCode == REQUEST_CALL) {
            val number = pendingCall ?: return
            pendingCall = null
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            startSafely(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        }
        if (requestCode == REQUEST_CONTACTS) {
            contacts.load()
            updateResults()
        }
    }

    private fun choose(entry: AppEntry, from: View?) {
        val onPick = pick
        if (onPick != null) {
            pick = null
            onPick(entry)
            hideDrawer()
        } else {
            launch(entry, from)
        }
    }

    // endregion

    // region Keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pinKey(event)) return true
        // Moving or resizing with the keypad: the keys are for that until it's done.
        if (homeScreen.takesKeys) return homeScreen.keyMove(event)
        keyMover?.let { return it.handle(event) }
        // A held arrow does something (see holdAction); a press still just moves.
        if (heldArrow(event)) return true
        // OK on the search bar opens the first result, as Enter does in the text box.
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) &&
            currentFocus?.id == R.id.search_bar && drawerOpen && !overlays.isShowing) {
            if (event.action == KeyEvent.ACTION_UP) adapter.primary?.let { choose(it, null) }
            return true
        }
        // The Call key calls the highlighted contact, or the number typed in search.
        if (event.keyCode == KeyEvent.KEYCODE_CALL && drawerOpen && searching && !overlays.isShowing) {
            // On one of a contact's buttons, that contact.
            val contact = generateSequence(currentFocus) { it.parent as? View }.firstNotNullOfOrNull { it.tag as? Contacts.Contact }
            val number = contact?.number ?: search.text.toString().trim().takeIf { it.matches(DIALABLE) }
            if (number != null) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) call(number)
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        // A quick OK on a widget goes into it (holding it still opens its menu).
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && !drawerOpen && !overlays.isShowing &&
            event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout() &&
            homeScreen.enterWidget(currentFocus)) return true
        val digit = digitOf(event.keyCode)
        if (drawerOpen && digit != null && !overlays.isShowing) {
            if (!search.isFocused) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    // Typing a number in the app list jumps to Smart Search.
                    if (!searching) openSearch(digit.toString(), fromKeypad = true) else search.append(digit.toString())
                    keypadQuery = true
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBack() {
        when {
            homeScreen.isKeyMoving -> homeScreen.cancelKeyMove()
            keyMover != null -> keyMover?.undo?.invoke().also { keyMover = null }
            overlays.dismiss() -> Unit
            homeScreen.finishResize() -> Unit
            homeScreen.leaveWidget() -> Unit
            drawerOpen && showingHidden -> {
                showingHidden = false
                updateResults()
            }
            drawerOpen && keypadQuery && search.length() > 0 -> search.text.delete(search.length() - 1, search.length())
            drawerOpen -> hideDrawer()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlays.isShowing) {
            if (keyCode == KeyEvent.KEYCODE_MENU) overlays.dismiss()
            return keyCode == KeyEvent.KEYCODE_MENU || super.onKeyDown(keyCode, event)
        }
        if (drawerOpen) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                val tile = currentFocus
                val entry = tile?.tag as? AppEntry
                if (entry != null) appMenu(entry, tile)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when {
            // Short press searches, long press opens the number's app; decided on key up or long press.
            digitOf(keyCode) != null -> event.startTracking()
            keyCode == KeyEvent.KEYCODE_POUND -> openSearch(fromKeypad = true)
            keyCode == KeyEvent.KEYCODE_MENU -> {
                val focused = homeScreen.itemFor(currentFocus)
                if (focused != null) homeScreen.showItemMenu(focused.first, focused.second)
                else homeMenu(rectOf(currentFocus ?: home))
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val digit = digitOf(keyCode)
        if (drawerOpen || overlays.isShowing || digit == null) return super.onKeyLongPress(keyCode, event)
        holdDigit(digit)
        return true
    }

    /**
     * Whether the accessibility service should time number key presses for this screen.
     * The phone's speed dial takes held numbers before an app sees them, so the service
     * catches them first and hands them over through [pressDigit] and [holdDigit].
     */
    fun atHome(): Boolean = !drawerOpen && !overlays.isShowing && !homeScreen.takesKeys

    /**
     * A key the service held back (to see if it was held) and then let through. Arrow keys move
     * the keypad highlight; anything else goes through the usual key handling.
     */
    fun pressKey(code: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (homeScreen.takesKeys) {
            homeScreen.keyMove(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            homeScreen.keyMove(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            return
        }
        val direction = directionOf(code)
        if (direction != null) {
            homeScreen.moveFocus(direction)
            return
        }
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    /** A key the service held back that turned out to be held: its built-in hold action, if any. */
    fun holdKey(code: Int) {
        if (homeScreen.takesKeys) return pressKey(code)
        holdAction(code)?.invoke()
    }

    /** A quick press of a number on the home screen: search, starting with that number. */
    fun pressDigit(digit: Int) = openSearch(digit.toString(), fromKeypad = true)

    /** A held number: what it's set to do, or, when it isn't set, offer to choose an app for it. */
    fun holdDigit(digit: Int) {
        val keys = KeyShortcuts.get(this)
        val action = keys.digit(digit)?.action
        val app = action?.takeIf { it.type == ActionType.APP }?.value?.let(repo::find)
        when {
            // Opened the usual way, so app limits and recent apps see it.
            app != null -> launch(app, null)
            action != null && action.type != ActionType.APP -> KeyActionRunner.run(this, action, LauncherService.running)
            else -> openSearch(fromKeypad = true, hint = "Choose an app for $digit") {
                keys.setDigit(digit, KeyAction(ActionType.APP, it.key))
                toast("Hold $digit to open ${it.label}")
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val digit = digitOf(keyCode)
        if (!drawerOpen && !overlays.isShowing && digit != null && event.isTracking && !event.isCanceled) {
            openSearch(digit.toString(), fromKeypad = true)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** An arrow whose hold already acted; the rest of that press (repeats, release) is swallowed. */
    private var heldKey = 0
    /** An arrow just pressed, waiting to see whether it's a tap or a hold. */
    private var pendingKey = 0

    /**
     * What holding [code] does right now, or null if it has no hold action. These are built in,
     * not shortcuts, so the whole launcher works from the keypad without the accessibility
     * service: on the home screen, up opens Smart Search, down the app list, and left and right
     * flip pages; in a panel, it goes back the way it came (up out of the app list, down out of
     * search), or to the next or previous category.
     */
    private fun holdAction(code: Int): (() -> Unit)? {
        if (!drawerOpen) return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { { openSearch(fromKeypad = true) } }
            KeyEvent.KEYCODE_DPAD_DOWN -> ::openAppsFromKeypad
            KeyEvent.KEYCODE_DPAD_RIGHT -> { { flipPage(1) } }
            KeyEvent.KEYCODE_DPAD_LEFT -> { { flipPage(-1) } }
            else -> null
        }
        val categoriesShown = !searching && !showingHidden && prefs.drawerCategories
        return when {
            code == KeyEvent.KEYCODE_DPAD_UP && !searching -> ::closeFromHold
            code == KeyEvent.KEYCODE_DPAD_DOWN && searching -> ::closeFromHold
            code == KeyEvent.KEYCODE_DPAD_RIGHT && categoriesShown -> { { stepCategory(1) } }
            code == KeyEvent.KEYCODE_DPAD_LEFT && categoriesShown -> { { stepCategory(-1) } }
            else -> null
        }
    }

    private fun closeFromHold() = hideDrawer()

    /**
     * Arrows on the home screen and in panels: a tap moves when the key is let go; a hold does
     * the hold action if there is one, and otherwise nothing (holding is for actions, tapping for
     * moving). The rest of a hold's key presses are swallowed, even once a panel has opened or
     * closed, so they don't move a highlight there.
     *
     * After a touch, Android takes the first arrow press for itself (to wake the highlight), so a
     * hold is also recognized from its repeats alone.
     */
    private fun heldArrow(event: KeyEvent): Boolean {
        val code = event.keyCode
        val direction = directionOf(code) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        // A new press always starts fresh (a release can go missing).
        if (down && event.repeatCount == 0 && heldKey == code) heldKey = 0
        if (heldKey == code) {
            if (!down) heldKey = 0
            return true
        }
        if (overlays.isShowing) {
            if (pendingKey == code) pendingKey = 0
            return false
        }
        // Typing in the search box: left and right move the cursor in the text.
        if (search.isFocused && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) return false
        if (down) {
            if (event.repeatCount == 0) {
                pendingKey = code
            } else {
                // Held: its action (or nothing), never a move.
                pendingKey = 0
                heldKey = code
                holdAction(code)?.invoke()
            }
            return true
        }
        // A release with no press seen is a tap too: after a touch, Android takes the press itself.
        if (pendingKey == code || pendingKey == 0) {
            pendingKey = 0
            // A tap: the usual move, now that it's clear it wasn't a hold.
            when {
                !drawerOpen -> homeScreen.moveFocus(direction)
                direction == View.FOCUS_UP || direction == View.FOCUS_DOWN -> moveInList(direction == View.FOCUS_DOWN)
                else -> moveSideways(direction)
            }
        }
        return true
    }

    /**
     * Left or right in a panel: only to something in the same row, never up or down. In the
     * search bar, right goes to its clear button and left comes back.
     */
    private fun moveSideways(direction: Int) {
        val from = currentFocus
        // Nothing highlighted yet (the panel was opened by touch): left and right start at the
        // beginning too, as up and down do.
        if (from == null || from === list || from === focusPark) return moveInList(true)
        val bar = findViewById<View>(R.id.search_bar)
        if (from === bar && direction == View.FOCUS_RIGHT) {
            if (searchClear.visibility == View.VISIBLE) searchClear.requestFocusFromTouch()
            return
        }
        if (from === searchClear && direction == View.FOCUS_LEFT) {
            bar.requestFocusFromTouch()
            return
        }
        // A row with its own buttons (a contact): right steps onto them one by one, left comes back.
        // Android's own search can't do this, since it only looks outside the highlighted row.
        val row = (from as? ViewGroup)?.takeIf { it.isFocusable } ?: (from.parent as? ViewGroup)?.takeIf { it.isFocusable }
        if (row != null) {
            val stops = listOf<View>(row) + (0 until row.childCount).map(row::getChildAt).filter { it.isFocusable && it.visibility == View.VISIBLE }
            if (stops.size > 1) {
                stops.getOrNull(stops.indexOf(from) + if (direction == View.FOCUS_RIGHT) 1 else -1)?.requestFocusFromTouch()
                return
            }
        }
        val target = from.focusSearch(direction) ?: return
        val a = rectOf(from)
        val b = rectOf(target)
        if (b.bottom <= a.top || b.top >= a.bottom) return
        target.requestFocusFromTouch()
    }

    /**
     * The next (or previous) category tab, wrapping around, with its first app highlighted.
     * The same slide as swiping between categories: out one side, the next in from the other.
     */
    private fun stepCategory(delta: Int) {
        val shown = visibleCategories()
        if (shown.isEmpty()) return
        val i = shown.indexOfFirst { it.id == category }.coerceAtLeast(0)
        val next = shown[(i + delta + shown.size) % shown.size]
        list.animate().cancel()
        slideTo(next.id, -delta * list.width * 0.35f) {
            list.post { firstListRow()?.let { focusRow(it, 0, true) } }
        }
    }

    private fun directionOf(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        else -> null
    }

    /**
     * A key shortcut asked for Smart Search ([OPEN_SEARCH]) or the app list ([OPEN_APPS]).
     * Works the same every time, whatever was open before. Returns false for anything else.
     */
    fun openFromKey(what: String?): Boolean {
        when (what) {
            OPEN_SEARCH -> {
                overlays.dismiss()
                if (drawerOpen && searching) return true
                if (drawerOpen) hideDrawer(animate = false)
                openSearch(fromKeypad = true)
            }
            OPEN_APPS -> {
                overlays.dismiss()
                if (drawerOpen && !searching) return true
                if (drawerOpen) hideDrawer(animate = false)
                openAppsFromKeypad()
            }
            else -> return false
        }
        return true
    }

    /** The app list, with the highlight on the first app, ready for the arrows. */
    private fun openAppsFromKeypad() {
        openDrawer()
        list.post { firstListRow()?.let { focusRow(it, 0, true) } }
    }

    /** Holding right or left on the home screen flips pages. */
    fun flipPage(delta: Int) = homeScreen.flipPage(delta)

    /**
     * Up and down in the app list: to the item straight above or below in the same column,
     * scrolling as needed. Past the top it goes to the category chips or the search button;
     * past the bottom, to the tab bar.
     */
    private fun moveInList(down: Boolean) {
        val focused = currentFocus
        // The list itself (not a row in it) can hold the highlight; that counts as being above the first row.
        val onListItself = focused === list
        val inChips = focused != null && isInside(focused, chipsScroll)
        val inTabs = focused != null && isInside(focused, findViewById(R.id.tabs_scroll))
        val x = focused?.let { rectOf(it).centerX() } ?: 0
        when {
            // Nothing highlighted yet (the panel was opened by touch): any arrow starts at the beginning.
            focused == null || onListItself || focused === focusPark ->
                if (searching) findViewById<View>(R.id.search_bar).requestFocusFromTouch()
                else firstListRow()?.let { focusRow(it, 0, true) } ?: Unit
            !inChips && !inTabs && !isInside(focused, list) -> when {
                // From the title bar or search: the chips first, if they're showing.
                !down -> Unit
                chipsScroll.visibility == View.VISIBLE && !onListItself -> focusSelectedIn(chipsScroll)
                // From the search box or title bar, down starts at the first result on the left.
                else -> firstListRow()?.let { focusRow(it, 0, true) }
            }
            inChips -> if (down) firstListRow()?.let { focusRow(it, x, true) }
            inTabs -> if (!down) lastVisibleListRow()?.let { focusRow(it, x, false) }
            else -> {
                var row: View = focused
                while (row.parent !== list) row = row.parent as? View ?: return
                val step = if (down) 1 else -1
                var target = list.getPositionForView(row) + step
                while (target in 0 until adapter.count && !canFocusRow(target)) target += step
                when {
                    target in 0 until adapter.count -> focusRow(target, x, down)
                    down -> focusSelectedIn(findViewById(R.id.tabs_scroll))
                    chipsScroll.visibility == View.VISIBLE -> focusSelectedIn(chipsScroll)
                    // In Smart Search, above the results is the search bar.
                    searching -> findViewById<View>(R.id.search_bar).requestFocusFromTouch()
                    else -> findViewById<View>(R.id.drawer_search).requestFocusFromTouch()
                }
            }
        }
    }

    /**
     * Rows the arrows stop on. Section titles aren't: their only button ("Show more") is on the
     * far right, so stopping there would throw the highlight sideways.
     */
    private fun canFocusRow(position: Int) = when (adapter.getItem(position)) {
        is DrawerAdapter.Row.Title, DrawerAdapter.Row.Divider -> false
        else -> true
    }

    private fun firstListRow() = (0 until adapter.count).firstOrNull(::canFocusRow)

    private fun lastVisibleListRow() = (list.lastVisiblePosition downTo list.firstVisiblePosition).firstOrNull(::canFocusRow)

    /** Focuses the item in row [position] nearest to [x], scrolling it fully into view. */
    private fun focusRow(position: Int, x: Int, down: Boolean) {
        val first = list.firstVisiblePosition
        val row = list.getChildAt(position - first)
        if (row == null) {
            // Not on screen yet: bring it in at the edge it's coming from, then focus it.
            list.setSelectionFromTop(position, if (down) list.height - list.paddingBottom - dp(96) else 0)
            list.post { list.getChildAt(position - list.firstVisiblePosition)?.let { pick(it, x) } }
            return
        }
        pick(row, x)
    }

    private fun pick(row: View, x: Int) {
        val targets = ArrayList<View>().also { row.addFocusables(it, View.FOCUS_DOWN) }.ifEmpty { listOf(row) }
        // From touch: leaves touch mode if needed, where a plain request would be refused.
        targets.minByOrNull { kotlin.math.abs(rectOf(it).centerX() - x) }?.requestFocusFromTouch()
        val bottom = list.height - list.paddingBottom
        when {
            row.bottom > bottom -> list.smoothScrollBy(row.bottom - bottom + dp(Ui.SPACE_S), 120)
            row.top < 0 -> list.smoothScrollBy(row.top - dp(Ui.SPACE_S), 120)
        }
    }

    /** The selected chip or tab, or the first one. */
    private fun focusSelectedIn(bar: View) {
        if (bar.visibility != View.VISIBLE) return
        val items = ArrayList<View>().also { bar.addFocusables(it, View.FOCUS_DOWN) }
        val selected = items.firstOrNull { v -> (v.tag as? String) == (if (bar === chipsScroll) chipCategory else category) }
        (selected ?: items.firstOrNull())?.requestFocusFromTouch()
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun digitOf(keyCode: Int): Int? =
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) keyCode - KeyEvent.KEYCODE_0 else null

    // endregion

    // region Menus

    private fun homeMenu(anchor: Rect) = overlays.showMenu(
        null,
        listOf(
            MenuItem("Add widget", R.drawable.ic_widgets) { widgets.pick() },
            MenuItem("Wallpaper", R.drawable.ic_wallpaper) { startActivity(Intent(this, WallpaperActivity::class.java)) },
            MenuItem("Settings", R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) },
        ),
        anchor,
    )

    private fun appMenu(entry: AppEntry, row: View) {
        val hidden = entry.key in prefs.hidden
        overlays.showMenu(
            entry.label,
            listOf(
                shortcuts.menuItems(entry),
                listOf(
                    MenuItem("Add to home", R.drawable.ic_home) {
                        hideDrawer()
                        homeScreen.addApp(entry)
                    },
                    MenuItem("Category: ${categories.find(categories.of(entry))?.name ?: ""}", R.drawable.ic_widgets) { categorySheet(entry) },
                ) + listOfNotNull(
                    // Apps keep an order you choose within their category.
                    MenuItem("Move", R.drawable.ic_move) { moveAppWithKeys(entry) }
                        .takeIf { !searching && !showingHidden && prefs.drawerCategories && category == categories.of(entry) },
                ) + listOf(
                    MenuItem(if (hidden) "Show in app list" else "Hide from app list",
                        if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off) {
                        prefs.setHidden(entry.key, !hidden)
                        updateResults()
                    },
                    MenuItem("Uninstall", R.drawable.ic_delete, danger = true) {
                        startSafely(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null)))
                    },
                ),
            ),
            rectOf(row),
            repo.icon(entry),
        ) { showAppInfo(entry) }
    }

    /**
     * Moving a category tab or an app in the app list with the keypad: the arrows move it,
     * OK keeps it there and Back puts it back.
     */
    private class KeyMover(val step: (dx: Int, dy: Int) -> Unit, val done: () -> Unit, val undo: () -> Unit)

    private var keyMover: KeyMover? = null

    private fun KeyMover.handle(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) step(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) step(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) step(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) step(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> if (!down && !event.isCanceled) {
                keyMover = null
                done()
            }
            KeyEvent.KEYCODE_BACK -> if (!down && !event.isCanceled) {
                keyMover = null
                undo()
            }
        }
        return true
    }

    /** Moves a category's tab along the bar with the left and right arrows. */
    private fun moveCategoryWithKeys(c: Categories.Item) {
        val before = visibleCategories().map { it.id }
        fun lift() {
            val tabs = findViewById<LinearLayout>(R.id.tabs)
            val tab = (0 until tabs.childCount).map(tabs::getChildAt).firstOrNull { it.tag == c.id } ?: return
            tab.requestFocusFromTouch()
            tab.scaleX = 1.15f
            tab.scaleY = 1.15f
        }
        fun show() {
            renderTabs(visibleCategories())
            lift()
        }
        keyMover = KeyMover(
            step = { dx, _ ->
                val order = visibleCategories().map { it.id }.toMutableList()
                val from = order.indexOf(c.id)
                if (dx != 0 && from + dx in order.indices) {
                    order.add(from + dx, order.removeAt(from))
                    categories.reorder(order)
                    show()
                }
            },
            done = { updateResults() },
            undo = {
                categories.reorder(before)
                updateResults()
            },
        )
        lift()
        toast("Use Left or Right to move. OK to place, Back to cancel.")
    }

    /**
     * Moves an app within its category with the arrows: left and right by one, up and down by a
     * row. The apps slide into their new places as it goes (nothing is rebuilt, so the highlight
     * stays on it), and the order is saved on OK.
     */
    private fun moveAppWithKeys(entry: AppEntry) {
        val id = category ?: return
        val apps = categories.sorted(id, repo.apps.filter { it.key !in prefs.hidden && categories.of(it) == id }).map { it.key }
        val across = if (prefs.drawerGrid) DrawerAdapter.COLS else 1
        // Every app tile on screen, and where each sits now: the slots they move between.
        val tiles = HashMap<String, View>()
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) row.getChildAt(j).let { t -> (t.tag as? AppEntry)?.let { tiles[it.key] = t } }
            (row.tag as? AppEntry)?.let { tiles[it.key] = row }
        }
        val home = tiles.mapValues { (_, v) -> rectOf(v) }
        // Only apps on screen move; the ones scrolled out of sight keep their places.
        val order = apps.filter { it in home }.toMutableList()
        val slots = order.map { home.getValue(it) }
        val moving = tiles[entry.key] ?: return
        moving.requestFocusFromTouch()
        // Apps moving up or down a row pass outside their own row; nothing cuts them off on the way.
        val rows = (0 until list.childCount).mapNotNull { list.getChildAt(it) as? ViewGroup }
        fun unclip(on: Boolean) {
            list.clipChildren = !on
            rows.forEach { it.clipChildren = !on }
            // The one moving is drawn over the others.
            moving.translationZ = if (on) dp(4).toFloat() else 0f
            (moving.parent as? View)?.translationZ = if (on) 1f else 0f
        }
        unclip(true)
        fun slide(animate: Boolean) = order.forEachIndexed { i, key ->
            val view = tiles[key] ?: return@forEachIndexed
            val from = home.getValue(key)
            val dx = (slots[i].left - from.left).toFloat()
            val dy = (slots[i].top - from.top).toFloat()
            view.animate().cancel()
            if (animate) view.animate().translationX(dx).translationY(dy).setDuration(180).setInterpolator(PANEL_EASING).start()
            else { view.translationX = dx; view.translationY = dy }
        }
        fun settle() {
            tiles.values.forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
            unclip(false)
        }
        keyMover = KeyMover(
            step = { dx, dy ->
                val from = order.indexOf(entry.key)
                var to = from + dx + dy * across
                // Down onto a last row that's shorter: the end of it.
                if (dy > 0 && to >= order.size && from / across < (order.size - 1) / across) to = order.size - 1
                if (to != from && to in order.indices) {
                    order.add(to, order.removeAt(from))
                    slide(animate = true)
                }
            },
            done = {
                val onScreen = order.iterator()
                categories.setOrder(id, apps.map { if (it in home) onScreen.next() else it })
                // The list is rebuilt in the new order, and the highlight put on the app that moved
                // before that's drawn, so it never shows anywhere else, or nowhere, in between.
                updateResults(keepFocus = false)
                list.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        list.viewTreeObserver.removeOnPreDrawListener(this)
                        settle()
                        for (i in 0 until list.childCount) {
                            val row = list.getChildAt(i) as? ViewGroup ?: continue
                            (0 until row.childCount).map(row::getChildAt).plus(row)
                                .firstOrNull { (it.tag as? AppEntry)?.key == entry.key }?.let {
                                    // Straight on: no highlight fading out of one tile and into another.
                                    val rings = tileViewsIn(list).mapNotNull { t ->
                                        (t.foreground as? android.graphics.drawable.LayerDrawable)?.getDrawable(1) as? android.graphics.drawable.StateListDrawable
                                    }
                                    rings.forEach { r -> r.setEnterFadeDuration(0); r.setExitFadeDuration(0) }
                                    it.requestFocus()
                                    list.jumpDrawablesToCurrentState()
                                    // The touch ripple's own focus tint starts its fade on the first draw; end it there too.
                                    it.postOnAnimation { it.jumpDrawablesToCurrentState() }
                                    list.post { rings.forEach { r -> r.setEnterFadeDuration(90); r.setExitFadeDuration(90) } }
                                    return true
                                }
                        }
                        return true
                    }
                })
            },
            undo = { settle() },
        )
        toast("Use arrows to move. OK to place, Back to cancel.")
    }

    /** Every app tile showing in [list]. */
    private fun tileViewsIn(list: ListView): List<View> = (0 until list.childCount).flatMap { i ->
        val row = list.getChildAt(i) as? ViewGroup ?: return@flatMap emptyList()
        (0 until row.childCount).map(row::getChildAt).filter { it.tag is AppEntry } + listOfNotNull(row.takeIf { it.tag is AppEntry })
    }

    private fun sheetRow(icon: Int, label: String, color: Int = palette.text, onClick: () -> Unit): View =
        Components.row(this, palette, icon, label, color = color, onClick = onClick)

    private fun rectOf(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    // endregion

    // region Actions

    override fun launch(entry: AppEntry, from: View?) {
        // Locked apps go straight to the limit's screen, without showing the app first.
        if (limits.isLocked(entry.packageName)) return startActivity(LimitActivity.intent(this, entry.packageName))
        val bounds = from?.let { v -> Rect().also { v.getGlobalVisibleRect(it) } }
        val options = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try {
            launcherApps.startMainActivity(entry.component, Process.myUserHandle(), bounds, options)
            prefs.recordLaunch(entry.key)
            newApps.opened(entry.packageName)
        } catch (e: Exception) {
            toast("Can't open ${entry.label}")
        }
    }

    override fun showAppInfo(entry: AppEntry) =
        launcherApps.startAppDetailsActivity(entry.component, Process.myUserHandle(), null, null)

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val statusBar = getSystemService("statusbar")
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        }
    }

    override fun startSafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Nothing on this phone can open that")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // endregion

    companion object {
        /** The home screen while it's in front, for the accessibility service to hand keys to. */
        var running: HomeActivity? = null
            private set
        const val EXTRA_OPEN = "open"
        const val OPEN_SEARCH = "search"
        const val OPEN_APPS = "apps"
        private const val REQUEST_CONTACTS = 21
        private const val REQUEST_CALL = 23
        /** A search that's a phone number rather than words. */
        private val DIALABLE = Regex("[0-9*#+]+")
        private const val CONTACTS_SHOWN = 3
        private const val RESULTS_SHOWN = 3
        private const val MEDIA_LIMIT = 20
        private const val REQUEST_MEDIA = 22
        private const val MAX_FITTING_TABS = 9
        private const val TAB_BAR_HEIGHT = 60
        private val PANEL_EASING = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        /** How far to drag (dp) for a panel to be fully shown. */
        private const val PANEL_PULL = 160
    }
}
