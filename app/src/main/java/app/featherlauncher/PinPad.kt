package app.featherlauncher

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * Asking for a 4-digit PIN in a sheet, as phones do: a lock, what it's for, four dots and a
 * number pad. The PIN is checked as soon as the fourth digit is in, so there's no OK. The
 * phone's own number keys work too, and Delete or Back takes a digit away (the activity passes
 * them to [PinPad.active]).
 */
class PinPad private constructor(
    private val context: Context,
    private val palette: Palette,
    private val overlays: Overlays,
    private val savedHash: String?,
    /** What the PIN is for, as in "To open hidden apps". */
    private val reason: String,
    private val done: (String) -> Unit,
) {
    private enum class Step { ENTER, CHOOSE, CONFIRM }

    private var step = if (savedHash != null) Step.ENTER else Step.CHOOSE
    private var entered = ""
    private var chosen = ""
    /** A wrong PIN is showing (red dots, shaking); typing waits until they've cleared. */
    private var rejecting = false
    private val title = TextView(context)
    private val message = TextView(context)
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }

    private fun show() {
        active = this
        title.apply {
            style(TextStyle.Title, palette.text)
            gravity = Gravity.CENTER
        }
        message.apply {
            style(TextStyle.Secondary, palette.textSecondary)
            gravity = Gravity.CENTER
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_L))
            addView(FrameLayout(context).apply {
                background = Ui.rounded(context, palette.accentSurface, Ui.RADIUS_PILL)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lock)
                    imageTintList = ColorStateList.valueOf(palette.accent)
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(Ui.SPACE_M)
            })
            addView(title, wide().apply { topMargin = dp(Ui.SPACE_M) })
            addView(message, wide().apply { topMargin = dp(Ui.SPACE_S) })
            addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply {
                topMargin = dp(Ui.SPACE_L)
                bottomMargin = dp(Ui.SPACE_L)
            })
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<")).forEach { keys ->
                addView(LinearLayout(context).apply {
                    keys.forEach { k -> addView(key(k), LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
                })
            }
        }
        render()
        overlays.showSheet(null, column) { if (active === this) active = null }
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun key(label: String): View {
        if (label.isEmpty()) return View(context)
        val view = if (label == "<") ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace)
            imageTintList = ColorStateList.valueOf(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Delete"
        } else TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            style(TextStyle.Title, palette.text)
        }
        return view.apply {
            background = Ui.rounded(context, palette.surfaceHigh, Ui.RADIUS_M)
            foreground = Ui.pressable(context, Ui.RADIUS_M)
            isFocusable = true
            setOnClickListener { if (label == "<") delete() else type(label[0]) }
        }
    }

    fun type(digit: Char) {
        if (rejecting || entered.length >= LENGTH) return
        entered += digit
        render()
        if (entered.length == LENGTH) dots.postDelayed(::check, 120)
    }

    fun delete() {
        // With all 4 in, they're being checked; nothing changes them until that's done.
        if (rejecting || entered.length == LENGTH) return
        entered = entered.dropLast(1)
        render()
    }

    /** Back on the keypad: takes a digit away, or closes the pad when there's none. */
    fun back(): Boolean {
        if (entered.isEmpty() || rejecting) return false
        delete()
        return true
    }

    private fun check() {
        val pin = entered
        when (step) {
            Step.ENTER -> if (hash(pin) == savedHash) finish(pin) else reject("Wrong PIN. Try again.")
            Step.CHOOSE -> {
                chosen = pin
                entered = ""
                step = Step.CONFIRM
                render()
            }
            Step.CONFIRM -> if (pin == chosen) finish(pin) else {
                step = Step.CHOOSE
                reject("The PINs didn't match. Try again.")
            }
        }
    }

    private fun finish(pin: String) {
        active = null
        overlays.dismiss()
        done(pin)
    }

    /** Shakes the dots in red with [why] underneath, then clears them for another try. */
    private fun reject(why: String) {
        rejecting = true
        dots.performHapticFeedback(HapticFeedbackConstants.REJECT)
        render(error = why)
        val d = dp(10).toFloat()
        ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, -d, d, -d, d, -d / 2, d / 2, 0f).setDuration(400).start()
        dots.postDelayed({
            rejecting = false
            entered = ""
            render(error = why)
        }, 500)
    }

    private fun render(error: String? = null) {
        title.text = when (step) {
            Step.ENTER -> "Enter PIN"
            Step.CHOOSE -> "Choose a PIN"
            Step.CONFIRM -> "Confirm PIN"
        }
        message.text = error ?: when (step) {
            Step.ENTER -> reason
            Step.CHOOSE -> "4 digits. You'll need it ${reason.replaceFirstChar { it.lowercase() }}."
            Step.CONFIRM -> "Enter the same 4 digits again."
        }
        message.setTextColor(if (error != null) palette.danger else palette.textSecondary)
        val outline = if (error != null && rejecting) palette.danger else palette.textSecondary
        dots.removeAllViews()
        for (i in 0 until LENGTH) {
            val filled = i < entered.length && !rejecting
            dots.addView(View(context).apply {
                background = Ui.rounded(context, if (filled) palette.text else 0, Ui.RADIUS_PILL, if (filled) 0 else outline)
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(8), 0, dp(8), 0) })
        }
    }

    private fun dp(v: Int) = Ui.dp(context, v)

    companion object {
        private const val LENGTH = 4

        /** The pad on screen, if any; number keys go to it. */
        var active: PinPad? = null
            private set

        fun hash(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("launcher:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

        /** Asks for the saved PIN, saying what it's for ([reason]); [unlocked] runs once it's right. */
        fun ask(context: Context, palette: Palette, overlays: Overlays, savedHash: String, reason: String, unlocked: () -> Unit) {
            PinPad(context, palette, overlays, savedHash, reason) { unlocked() }.show()
        }

        /** Asks for a new PIN, then for it again; [set] gets it once both match. */
        fun create(context: Context, palette: Palette, overlays: Overlays, reason: String, set: (String) -> Unit) {
            PinPad(context, palette, overlays, null, reason, set).show()
        }
    }
}
