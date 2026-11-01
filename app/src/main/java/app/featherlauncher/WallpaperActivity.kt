package app.featherlauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Choosing a wallpaper without leaving the launcher, laid out like iOS's gallery: Weather,
 * your own photo and a light and dark pair on top; then collections of photos picked for
 * Feather (from Unsplash, through Picsum: nature and abstract, no people and nothing
 * religious); then colors. Whatever is picked opens full screen under a mock clock and dock,
 * can be dragged into place, and is set for the home screen, the lock screen or both.
 */
class WallpaperActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var root: FrameLayout
    private lateinit var overlays: Overlays
    private val io = Executors.newFixedThreadPool(3)
    /** Thumbnails load on their own, many at once, so the first rows fill in quickly and nothing waits behind them. */
    private val thumbIo = Executors.newFixedThreadPool(8)
    private val main = Handler(Looper.getMainLooper())
    /** Thumbnails already loaded, up to 16 MB of them. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var insets = Rect()

    private var scroll: android.widget.ScrollView? = null
    /** The preview on top of the page, if one is open. */
    private var preview: Preview? = null
    /** Which half of the light and dark pair is being chosen (null: setting a wallpaper normally). */
    private var pairTarget: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        val prefs = Prefs(this)
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        overlays = Overlays(root) { palette }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        root.setOnApplyWindowInsetsListener { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsets.Type.systemBars())
            val next = Rect(bars.left, bars.top, bars.right, bars.bottom)
            if (next != insets) {
                insets = next
                overlays.insets = insets
                build()
            }
            windowInsets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        build()
    }

    override fun onDestroy() {
        io.shutdownNow()
        thumbIo.shutdownNow()
        super.onDestroy()
    }

    private fun back() {
        when {
            overlays.dismiss() -> Unit
            preview != null -> closePreview()
            else -> finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // region Page

    private fun build() {
        val p = palette
        val scrollY = scroll?.scrollY ?: 0
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = header(p)
        page.addView(header)
        val tint = Ui.scrollTint(header, p.surface, p.surfaceScrolled)
        pairTarget?.let { page.addView(pairBanner(p, it)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Ui.SPACE_S), 0, insets.bottom + dp(Ui.SPACE_XL))
            // Choosing one half of the pair: only pictures, not Weather or another pair.
            if (pairTarget == null) addView(featured())
            addView(sectionTitle("Collections", "Photos from Unsplash, chosen for Feather"))
            COLLECTIONS.forEach { (name, ids) -> addView(collection(name, ids)) }
            addView(sectionTitle("Colors", null))
            addView(colors())
            if (pairTarget == null) addView(Components.group(this@WallpaperActivity, p, listOf(
                Components.row(this@WallpaperActivity, p, R.drawable.ic_open_external, "Other wallpaper apps",
                    subtitle = "Live wallpapers and other pickers", chevron = true, inset = Ui.SPACE_L) {
                    runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
                },
            )).apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(Ui.SPACE_XL) })
        }
        scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            setOnScrollChangeListener { _, _, y, _, _ -> tint(y > 0) }
            post { scrollTo(0, scrollY) }
        }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview?.let { root.addView(it.view) }
    }

    /** The back arrow and the title. It reaches up behind the status bar, so its tint does too. */
    private fun header(p: Palette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64) + insets.top
        setPadding(dp(Ui.SPACE_S), insets.top, dp(Ui.SPACE_L), 0)
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(p.text)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@WallpaperActivity).apply {
            text = "Wallpaper"
            style(TextStyle.Title, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(Ui.SPACE_S)
        })
    }

    /** A tall rounded card, as the gallery's pictures are; its content fills it. */
    private fun card(description: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = Ui.rounded(this@WallpaperActivity, palette.surfaceHigh, Ui.RADIUS_L)
        clipToOutline = true
        foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_L)
        isFocusable = true
        contentDescription = description
        setOnClickListener { onClick() }
    }

    /** A card's name under it, with a quieter line under that. */
    private fun captioned(card: View, title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FEATURED_HEIGHT)))
        addView(TextView(this@WallpaperActivity).apply {
            text = title
            style(TextStyle.Label, palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(2), dp(Ui.SPACE_S), 0, 0)
        })
        addView(TextView(this@WallpaperActivity).apply {
            text = subtitle
            style(TextStyle.Caption, palette.textSecondary)
            typeface = android.graphics.Typeface.DEFAULT
            setPadding(dp(2), dp(2), 0, 0)
        })
    }

    private fun centeredIcon(icon: Int) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(palette.textSecondary)
        scaleType = ImageView.ScaleType.CENTER
    }

    /** Weather, your own photo, and the light and dark pair, side by side on top. */
    private fun featured(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
        addView(featuredRow())
    }

    /** The top cards at full size, a row that scrolls sideways rather than squeezing them. */
    private fun featuredRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val on = Prefs(this@WallpaperActivity).weatherWallpaper
        listOf(
            captioned(weatherCard(), "Weather", if (on) "In use" else "Updates both screens"),
            captioned(card("Your photo") { pickPhoto() }.apply {
                addView(centeredIcon(R.drawable.ic_photo), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, "Your photo", "From the phone"),
            captioned(shuffleCard(), "Photo Shuffle", if (Prefs(this@WallpaperActivity).shuffleOn) "In use" else "Rotates photos automatically"),
            captioned(pairPreview(), "Light & dark", if (DarkMode.hasPair(this@WallpaperActivity)) "In use" else "Switches with dark mode"),
        ).forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(dp(FEATURED_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(Ui.SPACE_M) })
        }
    }

    /**
     * The Weather wallpaper, showing the sky as it is now. It's set for the home and lock
     * screens, and Feather keeps it up to date ([WeatherWallpaper]).
     */
    private fun weatherCard(): View {
        val picture = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Thread {
            val place = ClockInfo(this).place()
            val sky = place?.let { Weather.at(it) {} }?.sky ?: Sky.PARTLY
            val bitmap = WeatherScene.draw(dp(FEATURED_WIDTH), dp(FEATURED_HEIGHT), sky, WeatherWallpaper.timeOfDay(place))
            main.post { picture.setImageBitmap(bitmap) }
        }.start()
        return card("Weather wallpaper") {
            WeatherWallpaper.start(this) { ok ->
                if (ok) {
                    startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } else {
                    Toast.makeText(this, "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    }

    /** Photo Shuffle: a fan of the photos it would use. Opens its sheet. */
    private fun shuffleCard(): View = card("Photo Shuffle") { shuffleSheet() }.apply {
        addView(centeredIcon(R.drawable.ic_shuffle), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** What Photo Shuffle goes through, and how often it changes. */
    private fun shuffleSheet() {
        val p = palette
        val prefs = Prefs(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_L))
            addView(TextView(this@WallpaperActivity).apply {
                text = "Rotate your chosen photos or a collection on both the home and lock screens. Scheduled changes apply when the screen turns on or you return home. This replaces Weather wallpaper and removes any saved light and dark wallpaper pair."
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            })
            val sources = listOf(Components.row(this@WallpaperActivity, p, R.drawable.ic_photo, "Your photos",
                subtitle = "Choose photos for Photo Shuffle", chevron = true, inset = Ui.SPACE_L) {
                overlays.dismiss()
                @Suppress("DEPRECATION")
                startActivityForResult(photoPicker(many = true), REQUEST_SHUFFLE)
            }) + COLLECTIONS.map { (name, ids) ->
                Components.row(this@WallpaperActivity, p, R.drawable.ic_shuffle, name, subtitle = "${ids.size} photos · Internet required",
                    color = if (prefs.shuffleOn && prefs.shuffleCollection == name) p.accent else p.text, inset = Ui.SPACE_L) {
                    overlays.dismiss()
                    Shuffle.start(this@WallpaperActivity, name) { ok -> shuffleStarted(ok) }
                }
            }
            addView(Components.group(this@WallpaperActivity, p, sources))
            addView(Components.group(this@WallpaperActivity, p, listOfNotNull(
                Components.row(this@WallpaperActivity, p, R.drawable.ic_time, "Change frequency", subtitle = prefs.shuffleEvery.label,
                    chevron = true, inset = Ui.SPACE_L) {
                    val options = Shuffle.Every.entries
                    Components.choiceSheet(this@WallpaperActivity, p, overlays, "Change frequency", options.map { it.label to null },
                        options.indexOf(prefs.shuffleEvery)) { prefs.shuffleEvery = options[it] }
                },
                if (prefs.shuffleOn) Components.row(this@WallpaperActivity, p, R.drawable.ic_close, "Turn off Photo Shuffle",
                    color = p.danger, inset = Ui.SPACE_L) {
                    Shuffle.stop(this@WallpaperActivity)
                    overlays.dismiss()
                    build()
                } else null,
            )).apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(Ui.SPACE_M) })
        }
        // Taller than a small screen: it scrolls.
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet("Photo Shuffle", scroll, scrollable = scroll)
    }

    private fun shuffleStarted(ok: Boolean) {
        if (ok) {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } else {
            Toast.makeText(this, "Couldn't start Photo Shuffle. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    /** The pair's two pictures, half and half, or an invitation to choose them. Opens the pair's sheet. */
    private fun pairPreview(): View = card("Light and dark wallpapers") {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(Ui.SPACE_L))
            addView(TextView(this@WallpaperActivity).apply {
                text = "One for light mode and one for dark mode. The wallpaper changes when dark mode turns on or off."
                style(TextStyle.Secondary, palette.textSecondary)
                setPadding(dp(Ui.SPACE_XL), 0, dp(Ui.SPACE_XL), dp(Ui.SPACE_L))
            })
            addView(pairCard())
        }
        overlays.showSheet("Light and dark", column)
    }.apply {
        val halves = listOf(false, true).map { DarkMode.pairFile(this@WallpaperActivity, it) }
        if (halves.none { it.exists() }) {
            addView(centeredIcon(R.drawable.ic_moon), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return@apply
        }
        addView(LinearLayout(this@WallpaperActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            halves.forEach { file ->
                addView(ImageView(this@WallpaperActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (file.exists()) io.execute {
                        val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                        main.post { setImageBitmap(b) }
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The two halves of the pair, side by side: each shows its picture, or asks to choose one. */
    private fun pairCard(): View {
        val p = palette
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            listOf(false, true).forEach { dark ->
                addView(LinearLayout(this@WallpaperActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val file = DarkMode.pairFile(this@WallpaperActivity, dark)
                    addView(FrameLayout(this@WallpaperActivity).apply {
                        background = Ui.rounded(this@WallpaperActivity, p.surfaceHigh, Ui.RADIUS_M)
                        clipToOutline = true
                        foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
                        isFocusable = true
                        contentDescription = if (dark) "Dark mode wallpaper" else "Light mode wallpaper"
                        if (file.exists()) {
                            addView(ImageView(this@WallpaperActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                io.execute {
                                    val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
                                    main.post { setImageBitmap(b) }
                                }
                            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        } else {
                            addView(LinearLayout(this@WallpaperActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                addView(ImageView(this@WallpaperActivity).apply {
                                    setImageResource(R.drawable.ic_add)
                                    imageTintList = ColorStateList.valueOf(p.textSecondary)
                                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                                addView(TextView(this@WallpaperActivity).apply {
                                    text = "Choose"
                                    style(TextStyle.Secondary, p.textSecondary)
                                    gravity = Gravity.CENTER
                                    setPadding(0, dp(Ui.SPACE_XS), 0, 0)
                                })
                            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                        setOnClickListener {
                            overlays.dismiss()
                            pairTarget = dark
                            build()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
                    addView(LinearLayout(this@WallpaperActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, dp(Ui.SPACE_S), 0, 0)
                        addView(ImageView(this@WallpaperActivity).apply {
                            setImageResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sunrise)
                            imageTintList = ColorStateList.valueOf(p.textSecondary)
                        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(Ui.SPACE_XS + 2) })
                        addView(TextView(this@WallpaperActivity).apply {
                            text = if (dark) "Dark mode" else "Light mode"
                            style(TextStyle.Secondary, p.textSecondary)
                        })
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (!dark) marginEnd = dp(Ui.SPACE_S)
                })
            }
        }.let { card ->
            if (!DarkMode.hasPair(this) && !DarkMode.pairFile(this, false).exists() && !DarkMode.pairFile(this, true).exists()) return card
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(card)
                addView(TextView(this@WallpaperActivity).apply {
                    text = if (DarkMode.hasPair(this@WallpaperActivity)) "Remove wallpaper pair" else "Clear selected wallpaper"
                    style(TextStyle.Label, p.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_M), dp(Ui.SPACE_XL), 0)
                    background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener {
                        DarkMode.clearPair(this@WallpaperActivity)
                        overlays.dismiss()
                        build()
                    }
                })
            }
        }
    }

    /** While choosing one half of the pair: which one, and a way to stop. */
    private fun pairBanner(p: Palette, dark: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_XS), 0)
        background = Ui.rounded(this@WallpaperActivity, p.accentSurface, Ui.RADIUS_M)
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(if (dark) R.drawable.ic_moon else R.drawable.ic_sunrise)
            imageTintList = ColorStateList.valueOf(p.text)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(TextView(this@WallpaperActivity).apply {
            text = "Pick the ${if (dark) "dark" else "light"} mode wallpaper"
            style(TextStyle.Label, p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_M) })
        addView(ImageView(this@WallpaperActivity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(p.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL)
            isFocusable = true
            contentDescription = "Stop choosing"
            setOnClickListener {
                pairTarget = null
                build()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
        }
    }

    private fun sectionTitle(title: String, subtitle: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), dp(Ui.SPACE_M))
        addView(TextView(this@WallpaperActivity).apply {
            text = title
            style(TextStyle.Title, palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        if (subtitle != null) addView(TextView(this@WallpaperActivity).apply {
            text = subtitle
            style(TextStyle.Secondary, palette.textSecondary)
            setPadding(0, dp(Ui.SPACE_XS), 0, 0)
        })
    }

    /** One collection: its name, then its photos in a row that scrolls sideways. */
    private fun collection(name: String, ids: List<Int>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@WallpaperActivity).apply {
            text = name
            style(TextStyle.Label, palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(Ui.SPACE_XL), dp(Ui.SPACE_M), dp(Ui.SPACE_XL), dp(Ui.SPACE_S))
        })
        addView(sideways(ids.map { id ->
            ImageView(this@WallpaperActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(this@WallpaperActivity, palette.surfaceHigh, Ui.RADIUS_M)
                clipToOutline = true
                foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
                isFocusable = true
                contentDescription = name
                // Just big enough for the tile, so each is a few kilobytes.
                val url = "https://picsum.photos/id/$id/${dp(TILE_WIDTH)}/${dp(TILE_HEIGHT)}"
                thumbs.get(url)?.let(::setImageBitmap) ?: thumbIo.execute {
                    val bitmap = thumbnail(id, url)
                    main.post {
                        if (bitmap != null) thumbs.put(url, bitmap)
                        setImageBitmap(bitmap)
                    }
                }
                setOnClickListener { openPhoto(id, name) }
            }
        }))
    }

    /** Tiles in a row that scrolls sideways, inset to line up with the page. */
    private fun sideways(tiles: List<View>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
        addView(LinearLayout(this@WallpaperActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEachIndexed { i, tile ->
                addView(tile, LinearLayout.LayoutParams(dp(TILE_WIDTH), dp(TILE_HEIGHT)).apply { if (i > 0) marginStart = dp(Ui.SPACE_S) })
            }
        })
    }

    private fun colors(): View = sideways(COLORS.map { (name, stops) ->
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            background = Ui.rounded(this@WallpaperActivity, palette.surfaceHigh, Ui.RADIUS_M)
            clipToOutline = true
            foreground = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_M)
            isFocusable = true
            contentDescription = name
            setImageBitmap(colorPicture(dp(TILE_WIDTH), dp(TILE_HEIGHT), stops))
            setOnClickListener {
                val (w, h) = targetSize()
                openPreview(colorPicture(w, h, stops), name, null)
            }
        }
    })

    // endregion

    // region Picking a photo from the phone

    private fun pickPhoto() {
        @Suppress("DEPRECATION")
        startActivityForResult(photoPicker(many = false), REQUEST_PHOTO)
    }

    /**
     * The system's photo picker, which needs no permission and shows only what's chosen to the
     * app. Android 11 and 12 get it from a system update; phones without it choose photos with
     * the phone's file picker, which works the same way.
     */
    private fun photoPicker(many: Boolean): Intent =
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").apply {
                if (many) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, many)
        }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SHUFFLE && resultCode == RESULT_OK && data != null) {
            val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            if (uris.isEmpty()) return
            Toast.makeText(this, "Setting up Photo Shuffle…", Toast.LENGTH_SHORT).show()
            Shuffle.startWithPhotos(this, uris) { ok -> shuffleStarted(ok) }
            return
        }
        val uri = data?.data?.takeIf { requestCode == REQUEST_PHOTO && resultCode == RESULT_OK } ?: return
        loadInto("Your photo", null) { decode(uri) }
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        val (w, h) = targetSize()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        // Big enough to cover the screen (and a bit for moving it around), no bigger.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    // endregion

    // region Preview

    private fun openPhoto(id: Int, collection: String) {
        val (w, h) = targetSize()
        // Picsum resizes and crops on its side; a little wider than the screen leaves room to move it.
        val width = (w * 1.3f).toInt()
        loadInto(collection, "Unsplash") { download("https://picsum.photos/id/$id/$width/$h") }
    }

    /** Shows the preview right away with a loading note, then the picture once [load] returns it. */
    private fun loadInto(title: String, source: String?, load: () -> Bitmap?) {
        val p = Preview(title, source)
        preview = p
        root.addView(p.view)
        io.execute {
            val bitmap = load()
            main.post {
                if (preview !== p) return@post
                if (bitmap == null) {
                    closePreview()
                    Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                } else {
                    p.show(bitmap)
                }
            }
        }
    }

    private fun openPreview(bitmap: Bitmap, title: String, source: String?) {
        val p = Preview(title, source)
        preview = p
        root.addView(p.view)
        p.show(bitmap)
    }

    private fun closePreview() {
        val p = preview ?: return
        preview = null
        p.view.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(p.view) }.start()
    }

    /**
     * Full screen: the picture (dragged to reposition), a mock clock and dock so it's clear how
     * the home screen will look, and the button to set it.
     */
    private inner class Preview(title: String, source: String?) {
        private val image = PanImage()
        private val loadingNote = TextView(this@WallpaperActivity).apply {
            text = "Loading…"
            style(TextStyle.Secondary, 0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        private val hint = TextView(this@WallpaperActivity).apply {
            text = "Drag to position the wallpaper"
            style(TextStyle.Label, 0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            background = Ui.rounded(this@WallpaperActivity, 0x66000000, Ui.RADIUS_PILL)
            visibility = View.GONE
        }
        /** Set while choosing one half of the light and dark pair. */
        private val forPair = pairTarget
        private val setButton = Components.pillButton(this@WallpaperActivity,
            when (forPair) {
                true -> "Use for dark mode"
                false -> "Use for light mode"
                null -> "Set wallpaper"
            }, 0xFFFFFFFF.toInt(), 0xFF1B1C1F.toInt()) { if (forPair != null) usePair(forPair) else chooseWhere() }

        val view: View = FrameLayout(this@WallpaperActivity).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(loadingNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // How to place it, in the open middle of the screen; it fades once the picture is moved.
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER))
            // A sample of the home screen on top, so the picture is judged in place.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, insets.top + dp(48), 0, 0)
                addView(TextClock(this@WallpaperActivity).apply {
                    format12Hour = "h:mm"
                    format24Hour = "H:mm"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x66000000)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(TextClock(this@WallpaperActivity).apply {
                    format12Hour = "EEE, MMMM d"
                    format24Hour = "EEE, MMMM d"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x66000000)
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                isClickable = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            // Back, the title and who took it, at the top.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_S), insets.top, dp(Ui.SPACE_L), 0)
                addView(ImageView(this@WallpaperActivity).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = Ui.pressable(this@WallpaperActivity, Ui.RADIUS_PILL, focusColor = 0xFFFFFFFF.toInt())
                    isFocusable = true
                    contentDescription = "Back"
                    setOnClickListener { closePreview() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(this@WallpaperActivity).apply {
                    text = if (source != null) "$title · $source" else title
                    style(TextStyle.Secondary, 0xE6FFFFFF.toInt())
                    setShadowLayer(dp(4).toFloat(), 0f, 0f, 0x88000000.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_S) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            // The dock, the hint and the button at the bottom.
            addView(LinearLayout(this@WallpaperActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), insets.bottom + dp(Ui.SPACE_M))
                addView(View(this@WallpaperActivity).apply {
                    background = Ui.rounded(this@WallpaperActivity, 0x33000000, Ui.RADIUS_BAR, 0x26FFFFFF)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.DOCK_HEIGHT)))
                addView(setButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(Ui.SPACE_M)
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        init {
            setButton.isEnabled = false
            setButton.alpha = 0.5f
        }

        fun show(bitmap: Bitmap) {
            loadingNote.visibility = View.GONE
            image.setPicture(bitmap)
            if (image.canMove) hint.visibility = View.VISIBLE
            image.onMoved = { hint.animate().alpha(0f).setDuration(200).start() }
            setButton.isEnabled = true
            setButton.alpha = 1f
            setButton.requestFocus()
        }

        private fun chooseWhere() {
            // Choosing the home and lock screens one at a time gives each its own picture.
            val options = listOf("Home and lock screen", "Lock screen and blurred home screen", "Home screen only", "Lock screen only")
            Components.choiceSheet(this@WallpaperActivity, palette, overlays, "Set wallpaper for", options.map { it to null }, -1) { i ->
                when (i) {
                    0 -> apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    1 -> apply(WallpaperManager.FLAG_LOCK, blurredHome = true)
                    2 -> apply(WallpaperManager.FLAG_SYSTEM)
                    else -> apply(WallpaperManager.FLAG_LOCK)
                }
            }
        }

        /** Keeps the picture as one half of the pair, and back to the page to choose the other. */
        private fun usePair(dark: Boolean) {
            val cropped = image.visibleArea(targetSize()) ?: return
            setButton.isEnabled = false
            WeatherWallpaper.stop(this@WallpaperActivity)
            Shuffle.stop(this@WallpaperActivity)
            DarkMode.saveToPair(this@WallpaperActivity, cropped, dark) {
                pairTarget = null
                preview = null
                build()
                Toast.makeText(this@WallpaperActivity,
                    if (DarkMode.hasPair(this@WallpaperActivity)) "Light and dark wallpapers set" else "Now pick the ${if (dark) "light" else "dark"} one",
                    Toast.LENGTH_SHORT).show()
            }
        }

        /** Crops what's on screen to the wallpaper's size and sets it, then goes home to show it. */
        /** Sets the picture for [which] screens; with [blurredHome], a soft blur of it on the home screen too, as iOS does. */
        private fun apply(which: Int, blurredHome: Boolean = false) {
            // A wallpaper set on its own replaces the pair (otherwise the next mode switch would
            // undo it) and the Weather wallpaper (which would replace it when the sky changes).
            if (which and WallpaperManager.FLAG_SYSTEM != 0) DarkMode.clearPair(this@WallpaperActivity)
            if (blurredHome) DarkMode.clearPair(this@WallpaperActivity)
            WeatherWallpaper.stop(this@WallpaperActivity)
            Shuffle.stop(this@WallpaperActivity)
            val cropped = image.visibleArea(targetSize()) ?: return
            setButton.isEnabled = false
            (setButton as TextView).text = "Setting…"
            io.execute {
                val ok = runCatching {
                    val manager = WallpaperManager.getInstance(this@WallpaperActivity)
                    manager.setBitmap(cropped, null, true, which)
                    if (blurredHome) manager.setBitmap(blurred(cropped), null, true, WallpaperManager.FLAG_SYSTEM)
                }.isSuccess
                main.post {
                    if (ok) {
                        startActivity(Intent(this@WallpaperActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    } else {
                        (setButton as TextView).text = "Set wallpaper"
                        setButton.isEnabled = true
                        Toast.makeText(this@WallpaperActivity, "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** A picture that fills the screen and can be dragged to choose which part shows. */
    private inner class PanImage : View(this@WallpaperActivity), OwnsDrag {
        private var bitmap: Bitmap? = null
        private val matrix = Matrix()
        private var scale = 1f
        private var dx = 0f
        private var dy = 0f
        private var lastX = 0f
        private var lastY = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var onMoved: (() -> Unit)? = null

        /** Whether the picture is bigger than the screen in some direction, so dragging does something. */
        val canMove: Boolean get() = bitmap?.let { it.width * scale > width + 1 || it.height * scale > height + 1 } ?: false

        init {
            isFocusable = false
        }

        fun setPicture(b: Bitmap) {
            bitmap = b
            fit()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

        /** Covers the view, centered. */
        private fun fit() {
            val b = bitmap ?: return
            if (width == 0) return
            scale = max(width / b.width.toFloat(), height / b.height.toFloat())
            dx = (width - b.width * scale) / 2
            dy = (height - b.height * scale) / 2
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            canvas.drawBitmap(b, matrix, paint)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val b = bitmap ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    // Moves only as far as the picture still covers the whole screen.
                    dx = (dx + event.x - lastX).coerceIn(width - b.width * scale, 0f)
                    dy = (dy + event.y - lastY).coerceIn(height - b.height * scale, 0f)
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                    onMoved?.invoke()
                }
            }
            return true
        }

        /** What's on screen, at [size]. */
        fun visibleArea(size: Pair<Int, Int>): Bitmap? {
            val b = bitmap ?: return null
            val (w, h) = size
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val k = w / width.toFloat()
            Canvas(out).drawBitmap(b, Matrix().apply {
                setScale(scale * k, scale * k)
                postTranslate(dx * k, dy * k)
            }, paint)
            return out
        }
    }

    // endregion

    // region Pictures

    /** The size the wallpaper is set at: the screen's, in pixels. */
    private fun targetSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        val bounds = windowManager.currentWindowMetrics.bounds
        return max(bounds.width(), m.widthPixels) to max(bounds.height(), m.heightPixels)
    }

    /**
     * [bitmap] softly blurred and a little darker, for the home screen under a sharp lock screen.
     * Shrinking a picture a lot and growing it back with smoothing blurs it evenly; doing it in
     * steps keeps the blur free of blocks.
     */
    private fun blurred(bitmap: Bitmap): Bitmap {
        var small = bitmap
        repeat(4) { small = Bitmap.createScaledBitmap(small, maxOf(1, small.width / 2), maxOf(1, small.height / 2), true) }
        var big = small
        repeat(4) { big = Bitmap.createScaledBitmap(big, big.width * 2, big.height * 2, true) }
        return Bitmap.createScaledBitmap(big, bitmap.width, bitmap.height, true).also {
            Canvas(it).drawColor(0x26000000)
        }
    }

    /**
     * A color wallpaper: [stops] blended corner to corner, with a soft glow of the lightest
     * near the top so it has depth rather than looking flat.
     */
    private fun colorPicture(w: Int, h: Int, stops: IntArray): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), stops, null, Shader.TileMode.CLAMP)
            })
            if (stops.distinct().size > 1) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = android.graphics.RadialGradient(w * 0.8f, h * 0.15f, h * 0.55f,
                    (stops.last() and 0xFFFFFF) or 0x66000000, stops.last() and 0xFFFFFF, Shader.TileMode.CLAMP)
            })
        }

    /** A tile's picture, kept on the phone after the first time so the page opens with them already there. */
    private fun thumbnail(id: Int, url: String): Bitmap? {
        val file = java.io.File(cacheDir, "wallpaper-thumbs/$id-${dp(TILE_WIDTH)}.jpg")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val bytes = runCatching { open(url).inputStream.use { it.readBytes() } }.getOrNull() ?: return null
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun download(url: String): Bitmap? = runCatching { open(url).inputStream.use(BitmapFactory::decodeStream) }.getOrNull()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    // endregion

    companion object {
        private const val REQUEST_PHOTO = 41
        private const val REQUEST_SHUFFLE = 42

        /** The photo ids in the collection called [name], for [Shuffle]. */
        fun collection(name: String) = COLLECTIONS.firstOrNull { it.first == name }?.second

        private const val FEATURED_WIDTH = 120
        private const val FEATURED_HEIGHT = 210
        private const val TILE_WIDTH = 112
        private const val TILE_HEIGHT = 200

        /**
         * Photos picked by hand from Picsum's Unsplash photos, by their Picsum id: nature,
         * sky and abstract, with no people in them and nothing religious.
         */
        private val COLLECTIONS = listOf(
            "Mountains" to listOf(29, 1043, 906, 908, 905, 961, 676, 664, 558, 599, 806, 873, 872, 235),
            "Sea and coast" to listOf(1041, 913, 909, 907, 1050, 871, 851, 853, 912, 973, 1053, 614, 716, 865, 1052),
            "Forest and green" to listOf(1039, 502, 498, 704, 666, 919, 634, 932, 924, 571, 958, 940, 915, 955),
            "Sky and night" to listOf(1022, 901, 724, 981, 825, 681, 683, 769, 794, 896, 715, 733, 583, 994),
            "Desert" to listOf(564, 829, 247, 261, 902, 184),
            "Abstract" to listOf(893, 1031, 948, 942, 677, 846, 1002),
        )

        /** Rich color blends: name and the colors from corner to corner. */
        private val COLORS = listOf(
            "Dusk" to intArrayOf(0xFF2E1A47.toInt(), 0xFF7B2F6B.toInt(), 0xFFF28A5B.toInt()),
            "Lagoon" to intArrayOf(0xFF0B3D4F.toInt(), 0xFF0F7C8C.toInt(), 0xFF7FD8C6.toInt()),
            "Midnight" to intArrayOf(0xFF050A1F.toInt(), 0xFF1B2A6B.toInt(), 0xFF4A63C9.toInt()),
            "Ember" to intArrayOf(0xFF2A0A0A.toInt(), 0xFF8C1D1D.toInt(), 0xFFF2703A.toInt()),
            "Meadow" to intArrayOf(0xFF0E2A1B.toInt(), 0xFF2F6B3A.toInt(), 0xFFA8C66C.toInt()),
            "Blossom" to intArrayOf(0xFF9C7BC2.toInt(), 0xFFE6A3B5.toInt(), 0xFFF6D5D0.toInt()),
            "Sky" to intArrayOf(0xFF3557C9.toInt(), 0xFF6FA8F5.toInt(), 0xFFBFE3FF.toInt()),
            "Sand" to intArrayOf(0xFF9E7650.toInt(), 0xFFD9B382.toInt(), 0xFFF3E3C3.toInt()),
            "Slate" to intArrayOf(0xFF1C1F26.toInt(), 0xFF3A4250.toInt(), 0xFF6B7A8F.toInt()),
            "Black" to intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt()),
        )
    }
}
