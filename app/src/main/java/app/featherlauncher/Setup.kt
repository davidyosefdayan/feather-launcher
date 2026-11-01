package app.featherlauncher

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The permissions and switches the launcher's features need, each with why and a button that
 * goes straight to the right place, so nobody has to hunt through the phone's settings.
 */
object Setup {
    class Item(
        val title: String,
        val why: String,
        val icon: Int,
        val done: (Context) -> Boolean,
        val start: (Activity) -> Unit,
    )

    val items get() = listOf(HOME, ACCESSIBILITY, NOTIFICATIONS) + permissions

    fun isHome(context: Context) = HOME.done(context)

    val HOME = Item("Set Feather Launcher as your home app", "Open Feather Launcher when you press Home", R.drawable.ic_home,
            done = { it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) },
            start = { a ->
                @Suppress("DEPRECATION")
                a.startActivityForResult(a.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), 0)
            })

    val ACCESSIBILITY = Item("Accessibility", "For app limits and key shortcuts, including holding number keys", R.drawable.ic_unlock,
            done = LauncherService::isEnabled,
            start = { a ->
                // Opens Launcher's own switch; older phones get the list instead.
                val component = ComponentName(a, LauncherService::class.java).flattenToString()
                open(a, Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component),
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })

    val NOTIFICATIONS = Item("Notification access", "For badges on app icons, notification filters, and app limits", R.drawable.ic_notification,
            done = NotificationService::isEnabled,
            start = { a ->
                val component = ComponentName(a, NotificationService::class.java).flattenToString()
                open(a, Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component),
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })

    private val permissions = listOfNotNull(
        permission(Manifest.permission.READ_CONTACTS, "Contacts", "To find people in Smart Search", R.drawable.ic_contact),
        permission(Manifest.permission.CALL_PHONE, "Phone calls", "So calling from search starts the call right away", R.drawable.ic_phone_call),
        // Before Android 13, apps could show notifications without asking.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            permission(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "To tell you when an app limit stops an app's sound", R.drawable.ic_notification)
        else null,
        permission(Manifest.permission.READ_CALENDAR, "Calendar", "To show calendar events on the clock and in Agenda", R.drawable.ic_calendar),
        permission(Manifest.permission.ACCESS_COARSE_LOCATION, "Location", "To show local weather and calculate daily times", R.drawable.ic_location),
    )

    fun pending(context: Context) = items.filter { !it.done(context) }

    private fun permission(name: String, title: String, why: String, icon: Int) = Item(title, why, icon,
        done = { it.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED },
        start = { a ->
            val asked = a.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            // Once Android stops showing the question, the app's own settings page is the only way.
            if (asked.getBoolean("asked_$name", false) && !a.shouldShowRequestPermissionRationale(name)) {
                open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)), null)
            } else {
                asked.edit().putBoolean("asked_$name", true).apply()
                a.requestPermissions(arrayOf(name), 0)
            }
        })

    private fun open(a: Activity, intent: Intent, fallback: Intent?) {
        runCatching { a.startActivity(intent) }.onFailure { fallback?.let { f -> runCatching { a.startActivity(f) } } }
    }

    /** One item: icon, what and why, and a Turn on button. */
    fun row(a: Activity, p: Palette, item: Item, onStart: () -> Unit = {}): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(a, Ui.SPACE_L), Ui.dp(a, Ui.SPACE_M), Ui.dp(a, Ui.SPACE_M), Ui.dp(a, Ui.SPACE_M))
        background = Ui.pressable(a, 0)
        isFocusable = true
        addView(ImageView(a).apply {
            setImageResource(item.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(p.textSecondary)
        }, LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(a).apply {
                text = item.title
                style(TextStyle.Body, p.text)
            })
            addView(TextView(a).apply {
                text = item.why
                style(TextStyle.Secondary, p.textSecondary)
                setPadding(0, Ui.dp(a, Ui.SPACE_XS), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(a, Ui.SPACE_L)
            marginEnd = Ui.dp(a, Ui.SPACE_S)
        })
        val go = {
            onStart()
            item.start(a)
        }
        addView(Components.pillButton(a, "Turn on", p.accent, p.onAccent) { go() }.apply { isFocusable = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 36)))
        setOnClickListener { go() }
    }
}
