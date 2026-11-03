package app.featherlauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The launcher's accessibility service. It sees which app comes to the front however it was
 * opened (the launcher, recent apps, a notification, a link), so app limits can cover locked
 * apps, and it gets hardware keys before the phone's own shortcuts do.
 */
class LauncherService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    /** The app whose activity is in front. */
    private var current: String? = null
    /** Window class names already checked, and whether they're activities (and not pop-ups or the keyboard). */
    private val activities = HashMap<String, Boolean>()

    private val recheck = Runnable { check() }
    private val screenOn = object : BroadcastReceiver() {
        // Delayed checks don't run while the phone sleeps, so look again when it wakes. The
        // Weather wallpaper catches up too, before the lock screen is looked at.
        override fun onReceive(context: Context, intent: Intent) {
            check()
            // The rest once per wake, as the screen turns on, not again at the unlock.
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            WeatherWallpaper.refresh(context)
            Shuffle.next(context, screenOn = true)
            // Filters catch any permanent notification that came back while the phone slept.
            NotificationService.refilter()
        }
    }

    override fun onServiceConnected() {
        running = this
        registerReceiver(screenOn, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onDestroy() {
        running = null
        runCatching { unregisterReceiver(screenOn) }
        main.removeCallbacks(recheck)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (!isActivity(pkg, cls)) return
        current = pkg
        // However an app gets opened, it's no longer new.
        NewApps.get(this).opened(pkg)
        check()
    }

    override fun onInterrupt() = Unit

    // region Keys

    /** Keys whose press this service took, so the rest of that press (repeats, release) is taken too. */
    private val owned = HashSet<Int>()
    /** Keys being held, with what happens if they're still down when the long press time is up. */
    private val holds = HashMap<Int, Runnable>()
    /** Keys that were held long enough; their release does nothing more. */
    private val held = HashSet<Int>()
    /** A key released once, waiting to see whether a second press follows. */
    private val singles = HashMap<Int, Runnable>()

    /** Every key that's down right now, whether or not this service took it. */
    private val pressed = HashSet<Int>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val others = pressed - code
        if (down) pressed += code else pressed -= code
        if (down && event.repeatCount == 0) owned -= code
        // While settings waits for a key to be pressed, every key goes there untouched.
        if (capturing && code !in owned) return false
        if (!down || event.repeatCount > 0) {
            if (code !in owned) return false
            if (!down) {
                owned -= code
                release(code)
            }
            return true
        }

        // A first press: decide whether this key is ours.
        // On the lock screen, only shortcuts for all apps work, and numbers type the PIN, held or
        // not. Android is only asked about it when that could matter.
        val locked by lazy(LazyThreadSafetyMode.NONE) { getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }
        val app by lazy(LazyThreadSafetyMode.NONE) { current.takeIf { !locked } }
        val home = HomeActivity.running?.takeIf { (current == packageName || current == null) && it.atHome() && !locked }

        // Two keys together. The first one has already done its usual thing; the second sets off the shortcut.
        for (other in others) {
            val combo = KeyShortcuts.get(this).combo(other, code, app, home != null) ?: continue
            owned += code
            cancelTiming(other)
            held += code
            run(combo.action)
            return true
        }

        if (KeyShortcuts.isNumber(code)) {
            val digit = code - KeyEvent.KEYCODE_0
            val downTime = event.downTime
            // Feather is in front but not ready for keys yet: just as the screen turns on, a number
            // pressed straight away would be lost.
            val waking = home == null && current == packageName && HomeActivity.running == null
            // In other apps, a number that isn't held for a shortcut is the app's; that's known
            // without asking Android anything.
            if (home == null && !waking && KeyShortcuts.get(this).forKey(code, current, false).none { it.press == Press.LONG }) return false
            if (locked) return false
            // On the home screen, a number searches when pressed and does its shortcut (or offers
            // to set one) when held.
            if (home != null || waking) {
                owned += code
                press(code, onHold = { whenReady { it.holdDigit(digit) } }, onPress = { whenReady { it.pressDigit(digit, downTime) } })
                return true
            }
        }
        if (!KeyShortcuts.canUse(code)) return false
        val shortcuts = KeyShortcuts.get(this).forKey(code, current, home != null).ifEmpty { return false }
            .let { if (locked) KeyShortcuts.get(this).forKey(code, null, false) else it }
        if (shortcuts.isEmpty()) return false
        val short = shortcuts.firstOrNull { it.press == Press.SHORT }
        val long = shortcuts.firstOrNull { it.press == Press.LONG }
        val double = shortcuts.firstOrNull { it.press == Press.DOUBLE }
        if (long == null && double == null) {
            // Only a plain press is set: act right away.
            owned += code
            run(short!!.action)
            return true
        }
        // Held back, a number, * or # gets typed for it afterwards; older Android can't do that in
        // a password box, so there the key goes straight to the box.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && KeyShortcuts.typesInTextBoxes(code) && textBox()?.isPassword == true) return false
        owned += code
        // A second press while waiting for one is a double press.
        singles.remove(code)?.let { pending ->
            main.removeCallbacks(pending)
            held += code
            run(double!!.action)
            return true
        }
        press(code,
            // Without a hold shortcut, holding an arrow at home keeps its built-in action.
            onHold = long?.let { { run(it.action) } }
                ?: home?.takeIf { code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT }?.let { { it.holdKey(code) } },
            onPress = { single(code, short, waitForSecond = double != null, event.downTime) })
        return true
    }

    /** Starts timing a press: [onHold] if it's still down after the long press time, else [onPress] on release. */
    private fun press(code: Int, onHold: (() -> Unit)?, onPress: () -> Unit) {
        held -= code
        val hold = Runnable {
            holds.remove(code)
            held += code
            onHold?.invoke()
        }
        if (onHold != null) {
            holds[code] = hold
            main.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
        }
        pressActions[code] = onPress
    }

    private val pressActions = HashMap<Int, () -> Unit>()

    /** Runs [act] on the home screen as soon as it's ready for keys (it's waking), for up to a second and a half. */
    private fun whenReady(tries: Int = 0, act: (HomeActivity) -> Unit) {
        HomeActivity.running?.let { return act(it) }
        if (tries < 30) main.postDelayed({ whenReady(tries + 1, act) }, 50)
    }

    /** A key held back for timing became part of a combination: it does nothing more. */
    private fun cancelTiming(code: Int) {
        holds.remove(code)?.let(main::removeCallbacks)
        pressActions.remove(code)
        singles.remove(code)?.let(main::removeCallbacks)
        if (code in owned) held += code
    }

    private fun release(code: Int) {
        holds.remove(code)?.let(main::removeCallbacks)
        val onPress = pressActions.remove(code)
        if (held.remove(code)) return
        onPress?.invoke()
    }

    /** A press that wasn't held: its own shortcut, or the key's normal job, possibly after waiting for a second press. */
    private fun single(code: Int, short: KeyShortcut?, waitForSecond: Boolean, downTime: Long) {
        val act = Runnable {
            singles.remove(code)
            if (short != null) run(short.action) else normalPress(code, downTime)
        }
        if (waitForSecond) {
            singles[code] = act
            main.postDelayed(act, DOUBLE_PRESS_MS)
        } else {
            act.run()
        }
    }

    /**
     * The key's usual job, for a key held back to time it. Feather gets the key itself when it's
     * in front (its search and app list type numbers too). A number pressed where there's
     * nothing to type in can't be handed back, so it's lost; few apps use numbers outside text.
     */
    private fun normalPress(code: Int, downTime: Long) {
        val home = HomeActivity.running
        if (home != null && current == packageName) return home.pressKey(code, downTime)
        KeyActionRunner.repeatNormalPress(this, code)
    }

    private fun run(action: KeyAction) = KeyActionRunner.run(this, action, this)

    /**
     * The text box in focus, for Android 11 and 12, where the service can't be told about typing
     * (Android 13 added that). On those versions it may read the screen for this (xml/accessibility).
     */
    private fun textBox(): AccessibilityNodeInfo? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    /** Types [text] at the cursor of the text box being edited. False when there's no box to type in. */
    fun type(text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val connection = inputMethod?.currentInputConnection ?: return false
            connection.commitText(text, 1, null)
            return true
        }
        // Older Android can only set a box's whole text: it gets the text put in at the cursor,
        // and the cursor goes after it. A password box's text can't be read, so it isn't typed in.
        val box = textBox()?.takeIf { !it.isPassword } ?: return false
        val old = if (box.isShowingHintText) "" else box.text?.toString().orEmpty()
        val selection = listOf(box.textSelectionStart, box.textSelectionEnd)
        val start = selection.min().takeIf { it in 0..old.length } ?: old.length
        val end = selection.max().takeIf { it in start..old.length } ?: start
        val set = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old.substring(0, start) + text + old.substring(end))
        }
        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) return false
        box.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + text.length)
        })
        return true
    }

    // endregion

    /** Covers the app in front if it's locked, and looks again when that could next change. */
    fun check() {
        main.removeCallbacks(recheck)
        val pkg = current ?: return
        if (pkg == packageName) return
        val limits = AppLimits.get(this)
        val now = System.currentTimeMillis()
        if (limits.isLocked(pkg, now)) {
            startActivity(LimitActivity.intent(this, pkg))
            return
        }
        if (pkg !in limits.allApps) return
        limits.nextChange(now, pkg)?.let { at -> main.postDelayed(recheck, (at - now).coerceAtLeast(0) + 500) }
    }

    private fun isActivity(pkg: String, cls: String): Boolean = activities.getOrPut("$pkg/$cls") {
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

    companion object {
        private const val DOUBLE_PRESS_MS = 300L

        /** Set by settings while it waits for the user to press the key for a shortcut. */
        @Volatile var capturing = false

        /** The running service, if the user has turned it on. */
        var running: LauncherService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, LauncherService::class.java)
            // Saved either in full or in the short ".Class" form, depending on how it was turned on.
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
