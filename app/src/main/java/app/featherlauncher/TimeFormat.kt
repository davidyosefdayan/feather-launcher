package app.featherlauncher

import android.content.Context
import java.util.TimeZone

/**
 * Times as the phone writes them ("4:05 PM", or "16:05" with the 24-hour clock), with the
 * format made once per time zone. Android works the format out from scratch each time it's
 * asked, which was most of what the widgets did every minute. It's made again when the
 * 24-hour setting, the language or the time zone changes.
 */
object TimeFormat {
    private val formats = HashMap<String, java.text.DateFormat>()
    private var h24: Boolean? = null
    private var watching = false

    /** [time] in [zone], as the phone writes times. */
    @Synchronized
    fun format(context: Context, time: Long, zone: TimeZone = TimeZone.getDefault()): String {
        watch(context)
        return formats.getOrPut(zone.id) {
            android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = zone }
        }.format(java.util.Date(time))
    }

    /** Whether the phone uses the 24-hour clock. */
    @Synchronized
    fun is24(context: Context): Boolean {
        watch(context)
        return h24 ?: android.text.format.DateFormat.is24HourFormat(context).also { h24 = it }
    }

    private fun watch(context: Context) {
        if (watching) return
        watching = true
        // Switching the 24-hour clock sends TIME_CHANGED.
        context.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) = forget()
        }, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
        })
    }

    @Synchronized
    private fun forget() {
        formats.clear()
        h24 = null
    }
}
