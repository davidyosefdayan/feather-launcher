package app.featherlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.ContactsContract
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A shortcut being made or changed on the editor page; any part can still be missing. */
class ShortcutDraft(
    var keyCode: Int? = null,
    /** The second key, when two are pressed together. */
    var keyCode2: Int? = null,
    var press: Press = Press.LONG,
    var action: KeyAction? = null,
    var scope: Scope = Scope.ALL,
    /** The app's package name, when [scope] is one app. */
    var app: String? = null,
    /** The saved shortcut this replaces, or null for a new one. */
    val original: KeyShortcut? = null,
) {
    private fun state() = listOf(keyCode, keyCode2, press, action, scope, app)
    private val initial = state()

    /** Whether anything was changed since the editor opened, so leaving it would lose something. */
    val changed get() = state() != initial

    companion object {
        fun of(sc: KeyShortcut) = ShortcutDraft(sc.keyCode, sc.keyCode2, sc.press, sc.action, sc.scope, sc.app, sc)
    }
}

/** The Gestures and keys page, and the page for making or changing one key shortcut. */
class KeySettings(private val s: SettingsActivity) {
    private val shortcuts = KeyShortcuts.get(s)
    private val prefs = s.prefs

    fun build(p: Palette) {
        val content = s.content
        if (!LauncherService.isEnabled(s)) {
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_unlock, "Accessibility access", "Off", color = p.danger,
                    chevron = true, inset = Ui.SPACE_L) {
                    Setup.ACCESSIBILITY.start(s)
                },
            )))
            content.addView(Components.footnote(s, p,
                "Allow accessibility access to use key shortcuts and hold number keys for actions instead of speed dial. Open ${s.getString(R.string.app_name)} in Accessibility and turn it on."))
        }

        content.addView(s.heading(p, "Gestures"))
        content.addView(s.spacer())
        val down = listOf("Smart Search", "Notifications")
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_swipe, "Swipe down", down[if (prefs.swipeDownSearch) 0 else 1],
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(s, p, s.overlays, "Swipe down on the home screen", down.map { it to null },
                    if (prefs.swipeDownSearch) 0 else 1) {
                    prefs.swipeDownSearch = it == 0
                    s.build()
                }
            },
        )))
        content.addView(Components.footnote(s, p,
            "On the keypad, hold Up on the home screen for Smart Search, Down for the app list, and Left or Right to change pages."))

        content.addView(s.heading(p, "Shortcuts"))
        content.addView(s.spacer())
        val rows = shortcuts.list.filter { !it.isDigit }.sortedWith(compareBy({ KeyShortcuts.keyName(it.keyCode) }, { it.press.ordinal })).map { sc ->
            Components.row(s, p, sc.action.type.icon, KeyShortcuts.describe(sc),
                subtitle = KeyShortcuts.describe(sc.action, ::appLabel) + when (sc.scope) {
                    Scope.ALL -> ""
                    Scope.HOME -> " · Home screen"
                    Scope.APP -> " · In ${packageLabel(sc.app)}"
                },
                chevron = true, inset = Ui.SPACE_L) { edit(ShortcutDraft.of(sc)) }
        } + Components.row(s, p, R.drawable.ic_add, "Add shortcut", color = p.accent, inset = Ui.SPACE_L) { edit(ShortcutDraft()) }
        content.addView(Components.group(s, p, rows))
        content.addView(Components.footnote(s, p, "Press, hold or double press a key to open an app, call someone, turn on the flashlight and more."))

        content.addView(s.heading(p, "Number keys"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0).map { digit -> digitRow(p, digit) }))
        content.addView(Components.footnote(s, p,
            "Hold a number key to run its shortcut. A quick press still types the number, or searches on the home screen. On the lock screen, number keys only type your PIN."))
    }

    private fun digitRow(p: Palette, digit: Int) = LinearLayout(s).apply {
        val action = shortcuts.digit(digit)?.action
        // Apps and app actions show the app's icon; anything else, the action's own.
        val entry = action?.takeIf { it.type == ActionType.APP || it.type == ActionType.APP_ACTION }?.value?.let(s.repo::find)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = s.dp(Components.ROW_HEIGHT)
        setPadding(s.dp(Ui.SPACE_L), 0, s.dp(Ui.SPACE_L - 4), 0)
        background = Ui.pressable(s, 0)
        isFocusable = true
        addView(keyCap(p, digit.toString()), LinearLayout.LayoutParams(s.dp(30), s.dp(30)))
        addView(ImageView(s).apply {
            when {
                entry != null -> setImageBitmap(s.repo.icon(entry))
                action != null -> {
                    setImageResource(action.type.icon)
                    imageTintList = ColorStateList.valueOf(p.textSecondary)
                    setPadding(s.dp(3), s.dp(3), s.dp(3), s.dp(3))
                }
            }
        }, LinearLayout.LayoutParams(s.dp(if (action != null) 28 else 0), s.dp(28)).apply { marginStart = s.dp(Ui.SPACE_L) })
        addView(TextView(s).apply {
            text = when {
                action == null -> "Not set"
                action.type == ActionType.APP -> entry?.label ?: "Not set"
                else -> KeyShortcuts.describe(action, ::appLabel)
            }
            style(TextStyle.Body, if (action != null) p.text else p.textTertiary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = s.dp(if (action != null) Ui.SPACE_M else Ui.SPACE_L)
        })
        addView(chevron(p), LinearLayout.LayoutParams(s.dp(22), s.dp(22)))
        setOnClickListener {
            edit(shortcuts.digit(digit)?.let(ShortcutDraft::of) ?: ShortcutDraft(KeyEvent.KEYCODE_0 + digit, press = Press.LONG))
        }
    }

    /** A key drawn like a keypad key, for numbers and the key being set. */
    private fun keyCap(p: Palette, label: String) = TextView(s).apply {
        text = label
        gravity = Gravity.CENTER
        style(TextStyle.Label, p.text)
        background = Ui.rounded(s, p.surface, Ui.RADIUS_S)
    }

    private fun chevron(p: Palette) = ImageView(s).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ColorStateList.valueOf(p.textTertiary)
    }

    private fun edit(draft: ShortcutDraft) {
        s.draft = draft
        s.open(SettingsActivity.Page.SHORTCUT)
    }

    // region Editor

    /** The editor: which key, how it's pressed, what it does, and where. Saved with the button at the bottom. */
    fun buildEditor(p: Palette) {
        val draft = s.draft ?: return s.open(SettingsActivity.Page.KEYS)
        val content = s.content
        val key = draft.keyCode

        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_keyboard, "Key", keyLabel(draft) ?: "Choose",
                chevron = true, inset = Ui.SPACE_L) { captureKey(draft) },
            Components.row(s, p, draft.action?.type?.icon ?: R.drawable.ic_hotkey, "Action",
                draft.action?.let { KeyShortcuts.describe(it, ::appLabel) } ?: "Choose", chevron = true, inset = Ui.SPACE_L) {
                pickAction("Choose an action", draft.action) { picked ->
                    draft.action = picked
                    s.build()
                }
            },
        )))

        // Two keys together are just pressed; a number is held, since its press types it; any
        // other key can be pressed, held or double pressed.
        if (draft.keyCode2 == null && key?.let(KeyShortcuts::isNumber) != true) {
            content.addView(s.heading(p, "How it's pressed"))
            content.addView(s.spacer())
            content.addView(Components.segmented(s, p, Press.entries.map { it.label }, draft.press.ordinal) {
                draft.press = Press.entries[it]
                s.build()
            })
        }

        content.addView(s.heading(p, "Works in"))
        content.addView(s.spacer())
        content.addView(Components.segmented(s, p, Scope.entries.map { it.label }, draft.scope.ordinal) {
            draft.scope = Scope.entries[it]
            s.build()
            if (draft.scope == Scope.APP && draft.app == null) pickScopeApp(draft)
        })
        if (draft.scope == Scope.APP) {
            content.addView(s.spacer())
            content.addView(Components.group(s, p, listOf(
                Components.row(s, p, R.drawable.ic_app_list, "App", draft.app?.let(::packageLabel) ?: "Choose",
                    chevron = true, inset = Ui.SPACE_L) { pickScopeApp(draft) },
            )))
        }
        explain(draft)?.let { content.addView(Components.footnote(s, p, it)) }

        val asShortcut = key?.let { KeyShortcut(it, draft.press, draft.action ?: KeyAction(ActionType.FLASHLIGHT), draft.scope, draft.keyCode2, draft.app) }
        val conflict = asShortcut?.let { sc -> shortcuts.list.firstOrNull { it != draft.original && shortcuts.sameTrigger(it, sc) } }
        if (conflict != null) {
            content.addView(Components.footnote(s, p,
                "${KeyShortcuts.describe(conflict)} already does ${KeyShortcuts.describe(conflict.action, ::appLabel).replaceFirstChar { it.lowercase() }} here. Saving replaces it."))
        }

        val ready = key != null && draft.action != null && (draft.scope != Scope.APP || draft.app != null)
        content.addView(LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_L), 0)
            addView(Components.pillButton(s, if (draft.original == null) "Add shortcut" else "Save",
                if (ready) p.accent else p.surfaceHigh, if (ready) p.onAccent else p.textTertiary) {
                if (!ready) {
                    Toast.makeText(s, when {
                        key == null -> "Choose a key first"
                        draft.action == null -> "Choose an action first"
                        else -> "Choose the app it works in"
                    }, Toast.LENGTH_SHORT).show()
                    return@pillButton
                }
                save(draft)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)))
            if (draft.original != null) {
                addView(TextView(s).apply {
                    text = "Delete shortcut"
                    gravity = Gravity.CENTER
                    style(TextStyle.Label, p.danger)
                    background = Ui.pressable(s, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener {
                        shortcuts.remove(draft.original)
                        s.closeEditor()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s.dp(48)).apply { topMargin = s.dp(Ui.SPACE_S) })
            }
        })
    }

    /** What happens to the key's usual press, when that's worth knowing. */
    private fun explain(draft: ShortcutDraft): String? {
        val key = draft.keyCode ?: return null
        val name = KeyShortcuts.keyName(key)
        val where = when (draft.scope) {
            Scope.ALL -> "in other apps"
            Scope.HOME -> "on the home screen"
            Scope.APP -> "in ${draft.app?.let(::packageLabel) ?: "that app"}"
        }
        return when {
            draft.keyCode2 != null -> "Press both keys at the same time. Each key still works as usual on its own."
            KeyShortcuts.isNumber(key) -> "A quick press of $name still types it."
            draft.press == Press.SHORT -> "A quick press of $name does only this $where."
            draft.scope != Scope.ALL -> "Everywhere else, $name works as usual."
            KeyShortcuts.canRepeatNormalPress(key) -> "A quick press of $name still works as usual."
            KeyShortcuts.typesInTextBoxes(key) -> "A quick press of $name still types it in text boxes, but its other uses in apps won't work."
            else -> "In other apps, a quick press of $name will no longer perform its normal action. To preserve that action elsewhere, choose Home screen or One app."
        }
    }

    private fun keyLabel(draft: ShortcutDraft): String? {
        val key = draft.keyCode ?: return null
        return draft.keyCode2?.let { "${KeyShortcuts.keyName(key)} + ${KeyShortcuts.keyName(it)}" }
            // A number is only ever held, which shows here instead of under How it's pressed.
            ?: if (KeyShortcuts.isNumber(key)) "${Press.LONG.verb} ${KeyShortcuts.keyName(key)}" else KeyShortcuts.keyName(key)
    }

    private fun pickScopeApp(draft: ShortcutDraft) {
        val current = s.repo.apps.firstOrNull { it.packageName == draft.app }?.key
        s.root.postDelayed({
            pickApp("Works in", current) { key ->
                draft.app = s.repo.find(key)?.packageName
                s.build()
            }
        }, 160)
    }

    private fun packageLabel(pkg: String?): String =
        s.repo.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg ?: "an app"

    private fun save(draft: ShortcutDraft) {
        val action = draft.action!!
        shortcuts.put(KeyShortcut(draft.keyCode!!, draft.press, action, draft.scope, draft.keyCode2,
            draft.app.takeIf { draft.scope == Scope.APP }), draft.original)
        s.closeEditor()
        if (action.type == ActionType.CALL && s.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Without it, the shortcut opens the dialer with the number instead of calling.
            s.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 0)
        }
    }

    /** Waits for the user to press the key they want, on the phone's own keypad. */
    private fun captureKey(draft: ShortcutDraft) {
        val p = s.palette
        val hint = TextView(s).apply {
            text = "Press the key you want to use, or two keys at the same time"
            style(TextStyle.Body, p.textSecondary)
            gravity = Gravity.CENTER
        }
        val column = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_L), s.dp(Ui.SPACE_XL), s.dp(Ui.SPACE_XL + Ui.SPACE_L))
            addView(ImageView(s).apply {
                setImageResource(R.drawable.ic_keyboard)
                imageTintList = ColorStateList.valueOf(p.accent)
            }, LinearLayout.LayoutParams(s.dp(40), s.dp(40)))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = s.dp(Ui.SPACE_L)
            })
        }
        s.keyCapture = { keys ->
            val unusable = keys.firstOrNull { !KeyShortcuts.canUse(it) }
            when {
                unusable != null -> hint.text = "${KeyShortcuts.keyName(unusable)} can't be used. Press another key."
                else -> {
                    draft.keyCode = keys[0]
                    draft.keyCode2 = keys.getOrNull(1)
                    if (keys.size == 1 && KeyShortcuts.isNumber(keys[0])) draft.press = Press.LONG
                    s.keyCapture = null
                    LauncherService.capturing = false
                    s.overlays.dismiss()
                    s.build()
                }
            }
        }
        LauncherService.capturing = true
        s.overlays.showSheet("Choose a key", column, onClosed = {
            s.keyCapture = null
            LauncherService.capturing = false
        })
    }

    /** What a shortcut does. */
    private fun pickAction(title: String, current: KeyAction?, onPick: (KeyAction) -> Unit) {
        val p = s.palette
        val types = ActionType.entries.filter { it.listed }
        // An app action counts as Open an app, which is where it's chosen.
        val currentType = if (current?.type == ActionType.APP_ACTION) ActionType.APP else current?.type
        Components.choiceSheet(s, p, s.overlays, title,
            types.map { t ->
                t.label to ImageView(s).apply {
                    setImageResource(t.icon)
                    imageTintList = ColorStateList.valueOf(p.textSecondary)
                }
            },
            types.indexOf(currentType)) { i ->
            val type = types[i]
            when (type.needs) {
                ActionType.Needs.NOTHING -> onPick(KeyAction(type))
                ActionType.Needs.APP -> s.root.postDelayed({
                    val currentApp = current?.takeIf { it.type == ActionType.APP || it.type == ActionType.APP_ACTION }?.value
                    pickApp("Open an app", currentApp) { key ->
                        val entry = s.repo.find(key) ?: return@pickApp
                        pickAppAction(entry, current, onPick)
                    }
                }, 160)
                ActionType.Needs.CONTACT -> s.pickContact { number, name -> onPick(KeyAction(ActionType.CALL, number, name)) }
            }
        }
    }

    /**
     * Opening [entry] itself or one of its own actions (Messages' "New conversation", a diary
     * app's barcode scanner). Apps without actions just open.
     */
    private fun pickAppAction(entry: AppEntry, current: KeyAction?, onPick: (KeyAction) -> Unit) {
        val actions = Shortcuts(s).list(entry)
        val open = KeyAction(ActionType.APP, entry.key)
        if (actions.isEmpty()) return onPick(open)
        val p = s.palette
        val selected = when {
            current?.type == ActionType.APP_ACTION && current.value == entry.key -> actions.indexOfFirst { it.id == current.id } + 1
            else -> 0
        }
        s.root.postDelayed({
            Components.choiceSheet(s, p, s.overlays, entry.label,
                listOf("Open ${entry.label}" to ImageView(s).apply { setImageBitmap(s.repo.icon(entry)) }) + actions.map { info ->
                    (info.shortLabel ?: info.longLabel ?: "").toString() to ImageView(s).apply { setImageDrawable(Shortcuts(s).icon(info)) }
                },
                selected) { i ->
                if (i == 0) return@choiceSheet onPick(open)
                val info = actions[i - 1]
                onPick(KeyAction(ActionType.APP_ACTION, entry.key, (info.shortLabel ?: info.longLabel ?: "").toString(), info.id))
            }
        }, 160)
    }

    private fun pickApp(title: String, current: String?, onPick: (String) -> Unit) {
        val apps = s.visibleApps.sortedBy { it.label.lowercase() }
        val options = apps.map { e -> e.label to ImageView(s).apply { setImageBitmap(s.repo.icon(e)) } }
        Components.choiceSheet(s, s.palette, s.overlays, title, options, apps.indexOfFirst { it.key == current }) { i ->
            onPick(apps[i].key)
        }
    }

    private fun appLabel(key: String): String? = s.repo.find(key)?.label

    // endregion

    companion object {
        /** Reads the number and name of a contact picked from the phone's contact picker. */
        fun readPicked(s: SettingsActivity, data: Intent?): Pair<String, String?>? {
            val uri = data?.data ?: return null
            return s.contentResolver.query(uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
            }
        }
    }
}
