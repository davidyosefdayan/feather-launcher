package app.featherlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads a personal setup (categories, hidden apps, key shortcuts, notification filters) sent
 * from a computer, so one person's setup lives in a file rather than in the app:
 *
 *     adb shell am broadcast -a app.featherlauncher.SETUP -n app.featherlauncher/.SetupImport \
 *         --es json64 "$(base64 < setup.json)"
 *
 * Only adb can send it: the receiver requires the DUMP permission, which apps can't have.
 * Every part is optional, and shortcuts and filters are added to what's already there.
 * `--es wallpaper <path>` sets the wallpaper instead, for checking colors against many wallpapers;
 * `--es iconsheet 1` draws every app's icon normal and dark into one picture, for tuning dark icons.
 * `--es widgetsheet 1` draws each of Feather's widgets at every size into widgets/ ([WidgetSheet]).
 */
class SetupImport : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // For trying the launcher's colors with different wallpapers from a computer.
        intent.getStringExtra("wallpaper")?.let { path ->
            val result = runCatching {
                java.io.File(path).inputStream().use { android.app.WallpaperManager.getInstance(context).setStream(it) }
                "Wallpaper set"
            }.getOrElse { "failed: $it" }
            return setResult(0, result, null)
        }
        // Backups over adb, for moving a setup to another install: written to and read from this
        // app's own folder on the phone (Android/data/<app>/files), which adb can reach.
        if (intent.hasExtra("backup")) {
            val out = java.io.File(context.getExternalFilesDir(null), "backup.json")
            out.writeText(Backup.export(context))
            return setResult(0, out.path, null)
        }
        intent.getStringExtra("restore")?.let { name ->
            val text = java.io.File(context.getExternalFilesDir(null), name).readText()
            if (!Backup.isBackup(text)) return setResult(1, "Not a backup", null)
            setResult(0, "Restoring", null)
            Backup.restore(context, text)
            return
        }
        // Zmanim for a span of days, for checking them against another source:
        // --es zmanim "lat,lon,zone,yyyy-mm-dd,days[,mga]" writes zmanim.csv to the app's files folder.
        intent.getStringExtra("zmanim")?.let { spec ->
            val parts = spec.split(',')
            val place = ClockInfo.Place("Check", parts[0].toDouble(), parts[1].toDouble(), java.util.TimeZone.getTimeZone(parts[2]))
            val start = java.text.SimpleDateFormat("yyyy-MM-dd").apply { timeZone = place.zone }.parse(parts[3])!!.time + 12 * 3600_000L
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply { timeZone = place.zone }
            val lines = (0 until parts[4].toInt()).flatMap { d ->
                Zmanim.forDay(start + d * 86_400_000L, place, parts.getOrNull(5) == "mga", 18).map { "${it.name},${time.format(java.util.Date(it.time))}" }
            }
            val out = java.io.File(context.getExternalFilesDir(null), "zmanim.csv")
            out.writeText(lines.joinToString("\n"))
            return setResult(0, out.path, null)
        }
        // The Jewish calendar for a span of days, for checking it against another source:
        // --es calendar "yyyy-mm-dd,days" writes calendar.csv (date, place, kind, value) to the app's files folder.
        intent.getStringExtra("calendar")?.let { spec ->
            val (from, count) = spec.split(',')
            val first = java.time.LocalDate.parse(from).toEpochDay()
            val lines = ArrayList<String>()
            for (day in first until first + count.toInt()) {
                val date = java.time.LocalDate.ofEpochDay(day)
                for (israel in listOf(false, true)) {
                    val where = if (israel) "il" else "da"
                    JewishCalendar.holidays(day, israel).forEach { lines += "$date,$where,holiday,$it" }
                    if (JewishCalendar.weekday(day) == java.util.Calendar.SATURDAY) {
                        lines += "$date,$where,parsha,${JewishCalendar.parsha(day, israel) ?: "-"}"
                    }
                }
                JewishCalendar.omer(day)?.let { lines += "$date,da,omer,$it" }
                JewishCalendar.dafYomi(day)?.let { lines += "$date,da,daf,$it" }
            }
            val out = java.io.File(context.getExternalFilesDir(null), "calendar.csv")
            out.writeText(lines.joinToString("\n"))
            return setResult(0, out.path, null)
        }
        // A sheet of every app's icon, normal and dark side by side, for checking dark icons.
        if (intent.hasExtra("widgetsheet")) {
            val done = goAsync()
            return WidgetSheet.draw(context) { result ->
                done.setResult(0, result, null)
                done.finish()
            }
        }
        if (intent.hasExtra("iconsheet")) {
            val pm = context.packageManager
            val launcher = context.getSystemService(android.content.pm.LauncherApps::class.java)
            val apps = launcher.getActivityList(null, android.os.Process.myUserHandle()).sortedBy { it.label.toString().lowercase() }
            val cell = 96
            val size = 72
            val cols = 6
            val rows = (apps.size + cols - 1) / cols
            val sheet = android.graphics.Bitmap.createBitmap(cols * cell * 2, rows * (cell + 20), android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sheet)
            canvas.drawColor(0xFF203028.toInt())
            val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 13f }
            val shape = Prefs(context).iconShape
            apps.forEachIndexed { i, a ->
                val x = (i % cols) * cell * 2
                val y = (i / cols) * (cell + 20)
                val icon = a.getIcon(android.util.DisplayMetrics.DENSITY_XHIGH)
                // Drawn at the size the launcher really uses on this screen, then shrunk for the sheet.
                val real = (context.resources.displayMetrics.density * 64).toInt()
                fun small(b: android.graphics.Bitmap) = android.graphics.Bitmap.createScaledBitmap(b, size, size, true)
                canvas.drawBitmap(small(shape.render(icon, real, IconLook.NORMAL)), (x + 8).toFloat(), (y + 8).toFloat(), null)
                canvas.drawBitmap(small(shape.render(icon, real, IconLook.DARK)), (x + cell).toFloat(), (y + 8).toFloat(), null)
                canvas.drawText(a.label.toString().take(22), (x + 8).toFloat(), (y + cell + 10).toFloat(), text)
            }
            // The order of the apps, one per line, so two sheets can be compared app by app.
            java.io.File(context.getExternalFilesDir(null), "icons.txt").writeText(apps.joinToString("\n") { it.label.toString() })
            val out = java.io.File(context.getExternalFilesDir(null), "icons.png")
            out.outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return setResult(0, out.path, null)
        }
        val encoded = intent.getStringExtra("json64") ?: return
        val json = runCatching { JSONObject(String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))) }.getOrNull()
            ?: return setResult(1, "Not valid JSON", null)

        json.optJSONArray("shortcuts")?.let { array ->
            KeyShortcuts.get(context).import((0 until array.length()).mapNotNull { KeyShortcut.fromJson(array.getJSONObject(it)) })
        }
        json.optJSONArray("filters")?.let { array ->
            val store = NotificationFilters.get(context)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val words = o.optJSONArray("words") ?: JSONArray()
                store.put(NotificationFilter(
                    System.currentTimeMillis() + i, o.optString("app").ifEmpty { null },
                    (0 until words.length()).map { words.getString(it) },
                    o.optBoolean("allowOnly"), o.optLong("during").takeIf { it != 0L },
                ))
            }
        }
        // The layout needs the app list, so it's applied by the home screen once that's loaded.
        if (json.has("categories") || json.has("hidden")) {
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(PENDING, json.toString()).apply()
            HomeActivity.running?.let { home -> home.runOnUiThread { home.recreate() } }
        }
        setResult(0, "Setup loaded", null)
    }

    companion object {
        private const val PENDING = "pending_setup"

        /**
         * Applies a waiting layout: categories (apps matched by name; where two apps share a
         * name, the Google one goes in the category and the other can be hidden) and hidden apps.
         */
        fun applyPending(context: Context, apps: List<AppEntry>, categories: Categories, prefs: Prefs): Boolean {
            val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val json = sp.getString(PENDING, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
            if (apps.isEmpty()) return false

            val used = HashSet<String>()
            fun take(label: String, preferGoogle: Boolean): AppEntry? {
                val matches = apps.filter { it.key !in used && it.label.equals(label, ignoreCase = true) }
                    .ifEmpty { apps.filter { it.key !in used && it.label.startsWith(label, ignoreCase = true) } }
                val pick = matches.sortedBy { if (it.packageName.startsWith("com.google") == preferGoogle) 0 else 1 }.firstOrNull()
                pick?.let { used += it.key }
                return pick
            }
            fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }

            json.optJSONArray("categories")?.let { array ->
                val layout = (0 until array.length()).map { i ->
                    val c = array.getJSONObject(i)
                    Triple(c.getString("name"), c.optString("icon", "category"), strings(c.optJSONArray("apps")).mapNotNull { take(it, true) })
                }
                categories.replaceAll(layout, fallback = json.optString("fallback"))
            }
            strings(json.optJSONArray("hidden")).mapNotNull { take(it, false) }.forEach { prefs.setHidden(it.key, true) }
            sp.edit().remove(PENDING).apply()
            return true
        }
    }
}
