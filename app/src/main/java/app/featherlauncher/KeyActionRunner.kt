package app.featherlauncher

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast

/** Does what a key shortcut asks. Global actions (recent apps, lock) need the running [service]. */
object KeyActionRunner {
    private var torchOn = false
    private var torchWatched = false

    fun run(context: Context, action: KeyAction, service: AccessibilityService?) {
        when (action.type) {
            ActionType.APP -> ComponentName.unflattenFromString(action.value ?: return)?.let { component ->
                start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
            ActionType.APP_ACTION -> {
                val pkg = ComponentName.unflattenFromString(action.value ?: return)?.packageName ?: return
                if (!Shortcuts(context).start(pkg, action.id ?: return)) {
                    Toast.makeText(context, "That action isn't available anymore", Toast.LENGTH_SHORT).show()
                }
            }
            ActionType.CALL -> {
                val number = action.value ?: return
                val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                start(context, Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
            }
            ActionType.FLASHLIGHT -> toggleTorch(context)
            // Straight to the home screen when it's in front; otherwise it's brought up first.
            ActionType.SEARCH -> HomeActivity.running?.openFromKey(HomeActivity.OPEN_SEARCH)
                ?: start(context, Intent(context, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN, HomeActivity.OPEN_SEARCH))
            ActionType.APP_LIST -> HomeActivity.running?.openFromKey(HomeActivity.OPEN_APPS)
                ?: start(context, Intent(context, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN, HomeActivity.OPEN_APPS))
            ActionType.SWITCH_KEYBOARD -> switchKeyboard(context, service)
            ActionType.NEXT_PAGE -> HomeActivity.running?.flipPage(1)
            ActionType.PREVIOUS_PAGE -> HomeActivity.running?.flipPage(-1)
            ActionType.NOTIFICATIONS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.RECENTS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.LOCK -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            ActionType.SCREENSHOT -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.RING_MODE -> {
                val audio = context.getSystemService(AudioManager::class.java)
                val vibrate = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
                runCatching { audio.ringerMode = if (vibrate) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL }
                Toast.makeText(context, if (vibrate) "Vibrate" else "Ring", Toast.LENGTH_SHORT).show()
            }
            ActionType.PLAY_PAUSE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.NEXT_TRACK -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    /**
     * Does a key's normal job when the key was held back to see whether it would be held or
     * double pressed. Returns false for keys whose normal press can't be redone.
     */
    fun repeatNormalPress(context: Context, keyCode: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustSuggestedStreamVolume(
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
            )
            KeyEvent.KEYCODE_CALL -> start(context, Intent(Intent.ACTION_DIAL))
            KeyEvent.KEYCODE_CAMERA -> start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> mediaKey(context, keyCode)
            KeyEvent.KEYCODE_BACK -> (context as? AccessibilityService)?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> (context as? AccessibilityService)?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND -> {
                // Typed into the text box being edited, if there is one.
                if ((context as? LauncherService)?.type(if (keyCode == KeyEvent.KEYCODE_STAR) "*" else "#") != true) return false
            }
            else -> return false
        }
        return true
    }

    /** Moves to the next turned-on keyboard and says which one it is. */
    private fun switchKeyboard(context: Context, service: AccessibilityService?) {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val keyboards = imm.enabledInputMethodList
        if (keyboards.size < 2) return Toast.makeText(context, "Only one keyboard is turned on", Toast.LENGTH_SHORT).show()
        val current = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        val next = keyboards[(keyboards.indexOfFirst { it.id == current } + 1) % keyboards.size]
        val switched = service?.softKeyboardController?.switchToInputMethod(next.id) == true
        if (switched) {
            Toast.makeText(context, next.loadLabel(context.packageManager), Toast.LENGTH_SHORT).show()
        } else {
            // Without the service (or if the phone refuses), fall back to the system's keyboard list.
            imm.showInputMethodPicker()
        }
    }

    private fun mediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun toggleTorch(context: Context) {
        val camera = context.getSystemService(CameraManager::class.java)
        if (!torchWatched) {
            torchWatched = true
            // Keeps track if the flashlight is also switched from quick settings.
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchOn = enabled
                }
            }, null)
        }
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Toast.makeText(context, "This phone has no flashlight", Toast.LENGTH_SHORT).show()
        runCatching { camera.setTorchMode(id, !torchOn) }
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Nothing on this phone can do that", Toast.LENGTH_SHORT).show()
        }
    }
}
