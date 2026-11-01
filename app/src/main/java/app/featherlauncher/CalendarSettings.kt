package app.featherlauncher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * Calendar and times: the one place everything that needs a location gets it from, the second
 * calendar shown next to the date, the day's times (zmanim, or just sunrise and sunset), and
 * what the clock shows besides the time.
 */
class CalendarSettings(private val s: SettingsActivity) {
    private val prefs = s.prefs

    fun build(p: Palette) {
        val content = s.content
        val info = ClockInfo(s)
        val place = info.place()
        val zmanim = prefs.dailyZmanim

        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_location, "Location", prefs.chosenPlace?.name ?: "Current location",
                chevron = true, inset = Ui.SPACE_L) { locationSheet() },
        )))
        when {
            place == null && prefs.chosenPlace == null && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                needs(p, Setup.items.first { it.title == "Location" })
            place == null -> content.addView(Components.footnote(s, p, "Your location isn't available yet. Search for a city, or try again later."))
            place.zone.id != java.util.TimeZone.getDefault().id ->
                content.addView(Components.footnote(s, p, "Times are shown in ${place.name}'s local time."))
            else -> content.addView(Components.footnote(s, p, "This location is used for weather, daily times and calendar dates that change at sunset."))
        }

        content.addView(s.heading(p, "Date"))
        content.addView(s.spacer())
        val now = System.currentTimeMillis()
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        val hebrewCalendar = prefs.secondCalendar == SecondCalendar.HEBREW
        content.addView(Components.group(s, p, listOfNotNull(
            Components.row(s, p, R.drawable.ic_calendar, "Second calendar", prefs.secondCalendar.label, chevron = true, inset = Ui.SPACE_L) {
                val calendars = SecondCalendar.entries
                Components.choiceSheet(s, p, s.overlays, "Second calendar",
                    calendars.map { c -> (c.format(now, sunset)?.let { "${c.label} · $it" } ?: c.label) to null },
                    calendars.indexOf(prefs.secondCalendar)) {
                    prefs.secondCalendar = calendars[it]
                    s.build()
                }
            },
            if (hebrewCalendar) Components.toggleRow(s, p, R.drawable.ic_text_fields, "Show in Hebrew", prefs.hebrewInHebrew) {
                prefs.hebrewInHebrew = it
                s.build()
            } else null,
        )))
        content.addView(Components.footnote(s, p, if (hebrewCalendar)
            "Shows the Hebrew date next to the main date. With Show in Hebrew on, the date, parsha and holidays are written in Hebrew, like ${HebrewText.isolate(SecondCalendar.HEBREW.format(now, sunset, true)!!)}."
            else "Shows the selected calendar's date next to the main date."))

        content.addView(s.heading(p, "Daily times"))
        content.addView(s.spacer())
        val rows = mutableListOf(
            Components.row(s, p, R.drawable.ic_sunrise, "Times", if (zmanim) "Zmanim" else "Sunrise and sunset",
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(s, p, s.overlays, "Times", listOf("Zmanim" to null, "Sunrise and sunset" to null),
                    if (zmanim) 0 else 1) {
                    prefs.dailyZmanim = it == 0
                    s.build()
                }
            },
        )
        if (zmanim) {
            rows += Components.row(s, p, R.drawable.ic_sunrise, "Sof zman Shema and Tefila", if (prefs.zmanMagenAvraham) "Magen Avraham" else "Gra",
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(s, p, s.overlays, "Sof zman Shema and Tefila", listOf("Gra" to null, "Magen Avraham" to null),
                    if (prefs.zmanMagenAvraham) 1 else 0) {
                    prefs.zmanMagenAvraham = it == 1
                    s.build()
                }
            }
            rows += Components.row(s, p, R.drawable.ic_sunset, "Candle lighting", "${prefs.candleMinutes} min before shkiah",
                chevron = true, inset = Ui.SPACE_L) {
                val minutes = listOf(18, 20, 22, 30, 40)
                Components.choiceSheet(s, p, s.overlays, "Candle lighting", minutes.map { "$it minutes before shkiah" to null },
                    minutes.indexOf(prefs.candleMinutes)) {
                    prefs.candleMinutes = minutes[it]
                    s.build()
                }
            }
        }
        if (place != null) rows += Components.row(s, p, R.drawable.ic_time, if (zmanim) "Today's zmanim" else "Today's times",
            chevron = true, inset = Ui.SPACE_L) {
            ZmanimSheet.show(s, p, s.overlays, place)
        }
        content.addView(Components.group(s, p, rows))

        // What the clock and At a glance show under the date. Each only shows when it matters.
        content.addView(s.heading(p, "At a glance"))
        content.addView(s.spacer())
        fun toggle(icon: Int, label: String, on: Boolean, set: (Boolean) -> Unit) = Components.toggleRow(s, p, icon, label, on) {
            set(it)
            s.build()
        }
        val jewish = prefs.secondCalendar == SecondCalendar.HEBREW
        content.addView(Components.group(s, p, listOfNotNull(
            toggle(R.drawable.ic_sunrise, "Weather", prefs.glanceWeather) { prefs.glanceWeather = it },
            if (jewish) toggle(R.drawable.ic_calendar, "Holidays", prefs.glanceHolidays) { prefs.glanceHolidays = it } else null,
            if (jewish) toggle(R.drawable.ic_calendar, "Parsha of the week", prefs.glanceParsha) { prefs.glanceParsha = it } else null,
            if (jewish) toggle(R.drawable.ic_calendar, "Omer", prefs.glanceOmer) { prefs.glanceOmer = it } else null,
            if (jewish) toggle(R.drawable.ic_calendar, "Daf Yomi", prefs.glanceDaf) { prefs.glanceDaf = it } else null,
        )))
        content.addView(Components.footnote(s, p, if (jewish)
            "Shown next to the date. Holidays appear on their dates, and the Omer count appears during the Omer."
            else "Shows the temperature under the time."))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOfNotNull(
            toggle(R.drawable.ic_calendar, "Next event", prefs.clockEvent) { prefs.clockEvent = it },
            toggle(R.drawable.ic_time, "Next alarm", prefs.glanceAlarm) { prefs.glanceAlarm = it },
            if (jewish) toggle(R.drawable.ic_sunset, "Candle lighting", prefs.glanceCandles) { prefs.glanceCandles = it } else null,
            if (jewish) toggle(R.drawable.ic_calendar, "Coming holidays", prefs.glanceUpcoming) { prefs.glanceUpcoming = it } else null,
            toggle(R.drawable.ic_time, if (zmanim) "Next zman" else "Next sunrise or sunset", prefs.clockZman) { prefs.clockZman = it },
        )))
        content.addView(Components.footnote(s, p, buildString {
            append("Up next shows the most pressing of these, and the clock shows two under the time.")
            if (jewish) append(" Candle lighting shows on Friday and Erev Yom Tov.")
            append(" The alarm shows in the evening.")
        }))
        if (prefs.clockEvent && !granted(Manifest.permission.READ_CALENDAR)) needs(p, Setup.items.first { it.title == "Calendar" })
    }

    /** Where the phone is, or a city searched for by name. */
    private fun locationSheet() {
        val p = s.palette
        val chosen = prefs.chosenPlace
        val column = android.widget.LinearLayout(s).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, s.dp(Ui.SPACE_M))
            addView(Components.row(s, p, R.drawable.ic_location, "Current location", subtitle = "Where the phone is",
                color = if (chosen == null) p.accent else p.text) {
                prefs.chosenPlace = null
                s.overlays.dismiss()
                s.build()
            })
            chosen?.let { place ->
                addView(Components.row(s, p, R.drawable.ic_check, place.name, subtitle = place.area, color = p.accent) { s.overlays.dismiss() })
            }
            addView(Components.row(s, p, R.drawable.ic_search, "Search for a city", color = p.text) {
                s.overlays.dismiss()
                s.root.postDelayed({ searchCity() }, 160)
            })
        }
        s.overlays.showSheet("Location", column)
    }

    private fun searchCity() {
        s.overlays.showInput("Search for a city", "", "City or town", action = "Search") { query ->
            if (query.isBlank()) return@showInput
            val main = Handler(Looper.getMainLooper())
            Thread {
                val found = ClockInfo.search(query)
                main.post { if (!s.isDestroyed) results(query, found) }
            }.start()
        }
    }

    private fun results(query: String, found: List<ClockInfo.Place>?) {
        val p = s.palette
        when {
            found == null -> android.widget.Toast.makeText(s, "Couldn't search. Check that you're online.", android.widget.Toast.LENGTH_SHORT).show()
            found.isEmpty() -> android.widget.Toast.makeText(s, "No cities found for \"$query\"", android.widget.Toast.LENGTH_SHORT).show()
            else -> {
                val column = android.widget.LinearLayout(s).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, 0, 0, s.dp(Ui.SPACE_M))
                    found.forEach { place ->
                        addView(Components.row(s, p, R.drawable.ic_location, place.name, subtitle = place.area) {
                            prefs.chosenPlace = place
                            s.overlays.dismiss()
                            s.build()
                        })
                    }
                }
                s.overlays.showSheet("Choose a place", column)
            }
        }
    }

    /** A permission the clock needs, with the button to allow it. */
    private fun needs(p: Palette, item: Setup.Item) {
        s.content.addView(s.spacer())
        s.content.addView(Components.group(s, p, listOf(Setup.row(s, p, item))))
    }

    private fun granted(permission: String) = s.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
