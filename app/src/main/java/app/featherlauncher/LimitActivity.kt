package app.featherlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What a locked app shows while a limit is on: the limit's message and, for a limit that waits,
 * a button that opens the app after the wait. The wait only counts while this screen stays in
 * front; leaving starts it over.
 */
class LimitActivity : Activity() {
    private lateinit var limits: AppLimits
    /** The limit keeping the app closed, as of when this screen opened. */
    private var limit: AppLimit? = null
    private lateinit var palette: Palette
    private lateinit var pkg: String
    private lateinit var label: String
    private lateinit var button: TextView
    private lateinit var progress: ClipDrawable
    private lateinit var until: TextView
    /** When the wait started (elapsed realtime), or 0 when not waiting. */
    private var waitStarted = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            button.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        limits = AppLimits.get(this)
        val prefs = Prefs(this)
        palette = Palette.from(this, prefs.colorStyle, prefs.themeMode, prefs.accentColor)
        read(intent)
        build()

        // Newer Android sends Back here instead of as a key press.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
        build()
    }

    private fun read(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
        waitStarted = 0
        limit = limits.lockFor(pkg)
    }

    private fun build() {
        val p = palette
        val limit = limit ?: return openApp()
        val root = FrameLayout(this).apply { setBackgroundColor(p.searchScrim) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(dp(Ui.SPACE_XL), bars.top + dp(Ui.SPACE_XL), dp(Ui.SPACE_XL), bars.bottom + dp(Ui.SPACE_L))
            insets
        }

        // The message, in the middle of the screen.
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(ImageView(this).apply {
            setImageResource(limit.strictness.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        column.addView(TextView(this).apply {
            text = limit.message.ifBlank { if (limit.strictness == Strictness.BLOCK) "This app is blocked for now." else "Do you want to open this right now?" }
            style(TextStyle.Title, p.onWall)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ui.SPACE_L)
        })
        until = TextView(this).apply {
            style(TextStyle.Secondary, p.onWallSecondary)
            gravity = Gravity.CENTER
        }
        column.addView(until, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ui.SPACE_M)
        })
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // The app, and the way into it.
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageView(this@LimitActivity).apply {
                val repo = Launcher.repo(this@LimitActivity)
                val entry = repo.apps.firstOrNull { it.packageName == pkg }
                val bitmap = entry?.let(repo::icon)
                if (bitmap != null) setImageBitmap(bitmap) else runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
                IconBadges.apply(this, locked = true, isNew = false, sizeDp = 28)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(this@LimitActivity).apply {
                text = label
                style(TextStyle.Label, p.onWall)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(Ui.SPACE_S)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(Ui.SPACE_L)
        })

        progress = ClipDrawable(Ui.rounded(this, 0x33FFFFFF, Ui.RADIUS_PILL), Gravity.START, ClipDrawable.HORIZONTAL)
        button = TextView(this).apply {
            gravity = Gravity.CENTER
            style(TextStyle.Label, p.onWall)
            background = LayerDrawable(arrayOf(Ui.rounded(this@LimitActivity, p.glass, Ui.RADIUS_PILL, p.hairline), progress))
            foreground = Ui.pressable(this@LimitActivity, Ui.RADIUS_PILL)
            isFocusable = true
            setOnClickListener { onButton() }
        }
        // A blocked app has no way in until the limit ends.
        if (limit.strictness == Strictness.WAIT) column.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        column.addView(TextView(this).apply {
            text = "Close"
            gravity = Gravity.CENTER
            style(TextStyle.Label, p.onWallSecondary)
            background = Ui.pressable(this@LimitActivity, Ui.RADIUS_PILL)
            isFocusable = true
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(Ui.SPACE_XS) })

        setContentView(root)
        if (limit.strictness == Strictness.WAIT) button.requestFocus()
        update()
    }

    override fun onResume() {
        super.onResume()
        // The limit may have ended, or the app was opened in the meantime.
        if (!limits.isLocked(pkg)) return openApp()
        button.post(tick)
    }

    override fun onPause() {
        super.onPause()
        button.removeCallbacks(tick)
        // Leaving this screen starts the wait over.
        waitStarted = 0
    }

    private fun onButton() {
        val limit = limit ?: return
        val now = System.currentTimeMillis()
        if (now < limits.readyAt(pkg, limit)) return
        waitStarted = if (waitStarted == 0L) SystemClock.elapsedRealtime() else 0L
        update()
    }

    private fun update() {
        val limit = limit ?: return
        val now = System.currentTimeMillis()
        // A limit that's on all day, every day, never ends; others say when they do.
        until.text = if (limit.allDay && limit.days == AppLimits.ALL_DAYS) limit.name
            else limit.endsAt(now)?.let { "${limit.name} until ${AppLimits.formatTime(this, it)}" } ?: limit.name
        if (limit.strictness == Strictness.BLOCK) return
        val ready = limits.readyAt(pkg, limit)
        val minutes = limit.openMinutes
        when {
            now < ready -> {
                val left = ((ready - now + 999) / 1000).toInt()
                button.text = "Can open again in %d:%02d".format(left / 60, left % 60)
                button.alpha = 0.6f
                progress.level = 0
            }
            waitStarted != 0L -> {
                val total = limit.waitSeconds * 1000L
                val elapsed = SystemClock.elapsedRealtime() - waitStarted
                if (elapsed >= total) {
                    limits.open(pkg, limit)
                    return openApp()
                }
                val left = ((total - elapsed + 999) / 1000).toInt()
                button.text = "Opening in $left sec · Tap to cancel"
                button.alpha = 1f
                progress.level = (elapsed * 10000 / total).toInt()
            }
            else -> {
                button.text = "Open for $minutes min"
                button.alpha = 1f
                progress.level = 0
            }
        }
    }

    private fun openApp() {
        button.removeCallbacks(tick)
        packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) goHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, LimitActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
