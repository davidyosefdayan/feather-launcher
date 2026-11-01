package app.featherlauncher

import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the launcher keeps, in one JSON file: every setting (layout, categories, hidden apps
 * and PIN, app limits, key shortcuts, notification filters, clock and appearance), the home
 * screen, and the light and dark wallpapers. Restoring it on another phone, or into a fresh
 * install, brings all of that back. Widgets have to be added again: Android ties each one to
 * the install that added it.
 */
object Backup {
    private const val FORMAT = 1

    /** The saved settings, by their store's name. Icon and app list caches are left out; they're rebuilt. */
    private val STORES = listOf("launcher", "categories", "keys", "notifications", "limits", "limits_state", "new_apps", "launches")
    private val FILES = listOf("workspace.json", "wallpaper_light.png", "wallpaper_dark.png")

    fun export(context: Context): String {
        val stores = JSONObject()
        STORES.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                entry?.let { values.put(key, it) }
            }
            stores.put(name, values)
        }
        val files = JSONObject()
        FILES.forEach { name ->
            val file = File(context.filesDir, name)
            if (file.exists()) files.put(name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("app", "Feather Launcher")
            .put("saved", System.currentTimeMillis())
            .put("stores", stores)
            .put("files", files)
            .toString()
    }

    /** Whether [text] is a Feather Launcher backup this version can read. */
    fun isBackup(text: String): Boolean = runCatching {
        val json = JSONObject(text)
        json.optInt("format") in 1..FORMAT && json.has("stores")
    }.getOrDefault(false)

    /**
     * Replaces everything with what's in [text], then restarts the launcher so every screen
     * starts from the restored setup.
     */
    fun restore(context: Context, text: String) {
        val json = JSONObject(text)
        val stores = json.getJSONObject("stores")
        STORES.forEach { name ->
            val values = stores.optJSONObject(name) ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                when (entry.getString("t")) {
                    "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                    "i" -> editor.putInt(key, entry.getInt("v"))
                    "l" -> editor.putLong(key, entry.getLong("v"))
                    "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                    "s" -> editor.putString(key, entry.getString("v"))
                    "set" -> entry.getJSONArray("v").let { a -> editor.putStringSet(key, (0 until a.length()).map(a::getString).toSet()) }
                }
            }
            // Widgets belong to the install that added them; their old numbers mean nothing here.
            if (name == "launcher") editor.remove("widgets")
            editor.commit()
        }
        val files = json.optJSONObject("files") ?: JSONObject()
        FILES.forEach { name ->
            val file = File(context.filesDir, name)
            val data = files.optString(name).takeIf { it.isNotEmpty() }
            if (data == null) file.delete() else file.writeBytes(Base64.decode(data, Base64.NO_WRAP))
        }
        dropWidgets(File(context.filesDir, "workspace.json"))
        // The wallpaper pair is applied again for the current mode.
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove("pair_applied").commit()
        restart(context)
    }

    /**
     * Other apps' widgets on the home screen are left out: their numbers belong to the install
     * that added them, so here they'd leave empty gaps, and a widget added later could get one of
     * those numbers and turn up in the old spot.
     */
    private fun dropWidgets(file: File) = runCatching {
        val json = JSONObject(file.readText())
        val items = json.getJSONArray("items")
        val kept = JSONArray()
        for (i in 0 until items.length()) items.getJSONObject(i).let { if (it.optString("type") != "WIDGET") kept.put(it) }
        file.writeText(json.put("items", kept).toString())
    }

    /**
     * Starts the launcher again from scratch, so nothing restored is shadowed by what's in memory.
     * It's asked for while the launcher is still on screen: Android doesn't let an app that's
     * gone open itself again, so a restart scheduled for later never came back unless it was the
     * home app.
     */
    private fun restart(context: Context) {
        context.startActivity(Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** "feather-launcher-backup-2026-10-26.json". */
    fun fileName(): String =
        "feather-launcher-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
}
