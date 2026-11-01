package app.featherlauncher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Size
import java.util.concurrent.Executors

/** One thing Smart Search found that isn't an app or a contact. */
class Found(
    val title: String,
    val subtitle: String?,
    /** A line icon (settings, file types)... */
    val iconRes: Int = 0,
    /** ...or a picture (shortcut icons, photo thumbnails). */
    var drawable: Drawable? = null,
    var bitmap: Bitmap? = null,
    val open: () -> Unit,
)

/**
 * The extra sources Smart Search looks through besides apps and contacts:
 * phone settings, app shortcuts and files on the phone.
 */
class SmartSearch(private val context: Context, private val start: (Intent) -> Unit) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    // region Settings

    private class Setting(val name: String, val extra: String, val icon: Int, val action: String) {
        val words = Search.words("$name $extra")
        val digitWords = words.map(Search::toDigits)
    }

    private val settings = listOf(
        Setting("Wi-Fi", "wireless internet network", R.drawable.ic_set_wifi, Settings.ACTION_WIFI_SETTINGS),
        Setting("Bluetooth", "headphones pair", R.drawable.ic_set_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
        Setting("Mobile network", "data sim cellular", R.drawable.ic_set_network, Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Setting("Hotspot", "tethering share internet", R.drawable.ic_set_hotspot, "android.settings.TETHER_SETTINGS"),
        Setting("Airplane mode", "flight", R.drawable.ic_set_airplane, Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Setting("Display", "brightness screen dark theme font size", R.drawable.ic_set_display, Settings.ACTION_DISPLAY_SETTINGS),
        Setting("Sound", "volume ringtone vibrate", R.drawable.ic_set_sound, Settings.ACTION_SOUND_SETTINGS),
        Setting("Notifications", "alerts", R.drawable.ic_set_notification, "android.settings.NOTIFICATION_SETTINGS"),
        Setting("Do not disturb", "quiet silence focus", R.drawable.ic_set_dnd, "android.settings.ZEN_MODE_SETTINGS"),
        Setting("Battery", "power saver charging", R.drawable.ic_set_battery, Intent.ACTION_POWER_USAGE_SUMMARY),
        Setting("Apps", "applications installed uninstall", R.drawable.ic_set_apps, Settings.ACTION_APPLICATION_SETTINGS),
        Setting("Default apps", "home browser phone launcher", R.drawable.ic_set_apps, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Setting("Storage", "space memory files", R.drawable.ic_set_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Setting("Location", "gps", R.drawable.ic_set_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Setting("Security", "lock screen password pin fingerprint", R.drawable.ic_set_security, Settings.ACTION_SECURITY_SETTINGS),
        Setting("Privacy", "permissions", R.drawable.ic_set_security, Settings.ACTION_PRIVACY_SETTINGS),
        Setting("Accessibility", "talkback magnification", R.drawable.ic_set_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Setting("Languages", "keyboard input language", R.drawable.ic_set_language, Settings.ACTION_LOCALE_SETTINGS),
        Setting("Date and time", "clock timezone", R.drawable.ic_set_time, Settings.ACTION_DATE_SETTINGS),
        Setting("Accounts", "google sync users", R.drawable.ic_set_user, Settings.ACTION_SYNC_SETTINGS),
        Setting("Connected devices", "nfc cast usb", R.drawable.ic_set_link, "android.settings.CONNECTED_DEVICES_SETTINGS"),
        Setting("Developer options", "usb debugging", R.drawable.ic_set_code, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Setting("About phone", "android version model", R.drawable.ic_set_about, Settings.ACTION_DEVICE_INFO_SETTINGS),
    )

    fun settings(raw: String): List<Found> {
        val t9 = Search.isT9(raw)
        val query = if (t9) raw else Search.normalizeText(raw)
        return settings
            .mapNotNull { s -> Search.score(query, if (t9) s.digitWords else s.words)?.let { s to it } }
            .sortedBy { it.second }
            .map { (s, _) -> Found(s.name, "Settings", iconRes = s.icon) { start(Intent(s.action)) } }
    }

    // endregion

    // region Shortcuts

    private class Shortcut(val info: android.content.pm.ShortcutInfo, val label: String, val app: String) {
        val words = Search.words(label)
        val digitWords = words.map(Search::toDigits)
    }

    private var shortcuts: List<Shortcut> = emptyList()

    /** Loads every app's shortcuts in the background (only the default home app may see them). */
    fun loadShortcuts(appName: (String) -> String?, done: () -> Unit) {
        if (!launcherApps.hasShortcutHostPermission()) return
        io.execute {
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
            val found = runCatching { launcherApps.getShortcuts(query, Process.myUserHandle()) }.getOrNull().orEmpty()
                .filter { it.isEnabled }
                .mapNotNull { info ->
                    val label = (info.shortLabel ?: info.longLabel)?.toString() ?: return@mapNotNull null
                    Shortcut(info, label, appName(info.`package`) ?: info.`package`)
                }
            main.post {
                val changed = found.map { it.info.id } != shortcuts.map { it.info.id }
                shortcuts = found
                if (changed) done()
            }
        }
    }

    /** Shortcuts matching [raw], leaving out those of apps in [excluded] (hidden apps). */
    fun shortcuts(raw: String, excluded: Set<String> = emptySet()): List<Found> {
        val t9 = Search.isT9(raw)
        val query = if (t9) raw else Search.normalizeText(raw)
        val density = context.resources.displayMetrics.densityDpi
        return shortcuts
            .filter { it.info.`package` !in excluded }
            .mapNotNull { s -> Search.score(query, if (t9) s.digitWords else s.words)?.let { s to it } }
            .sortedBy { it.second }
            .map { (s, _) ->
                Found(s.label, s.app, drawable = runCatching { launcherApps.getShortcutIconDrawable(s.info, density) }.getOrNull()) {
                    runCatching { launcherApps.startShortcut(s.info, null, null) }
                }
            }
    }

    // endregion

    // region Media

    /** Android 13 split reading files into photos, videos and music; before that it was one permission. */
    val mediaPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) arrayOf(
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO,
    ) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    val mediaAllowed get() = mediaPermissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private var mediaQuery = ""

    /** Looks for photos, videos, music and files by name; results (and then thumbnails) arrive later. */
    fun media(raw: String, limit: Int, done: (List<Found>) -> Unit) {
        mediaQuery = raw
        if (!mediaAllowed || raw.length < 2) return done(emptyList())
        io.execute {
            if (raw != mediaQuery) return@execute
            val results = ArrayList<Found>()
            val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE),
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$raw%"),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && results.size < limit) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val type = c.getInt(2)
                        val mime = c.getString(3) ?: "*/*"
                        val (icon, kind, base) = when (type) {
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Triple(R.drawable.ic_media_pic, "Photo", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Triple(R.drawable.ic_media_video, "Video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> Triple(R.drawable.ic_media_music, "Audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            else -> Triple(R.drawable.ic_media_file, "File", uri)
                        }
                        val itemUri = ContentUris.withAppendedId(base, id)
                        val found = Found(name, kind, iconRes = icon) {
                            start(Intent(Intent.ACTION_VIEW).setDataAndType(itemUri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                        if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                            found.bitmap = runCatching { context.contentResolver.loadThumbnail(itemUri, Size(128, 128), null) }.getOrNull()
                        }
                        results += found
                    }
                }
            }
            main.post { if (raw == mediaQuery) done(results) }
        }
    }

    // endregion

    companion object {
        /**
         * [label] with the letters that matched [raw] picked out, the way the search found them:
         * at the start of a word, as word initials, or anywhere. T9 digits match by their letters.
         */
        fun highlight(label: String, raw: String, color: Int, dim: Int = (color and 0xFFFFFF) or 0x8C000000.toInt()): CharSequence {
            if (raw.isBlank()) return label
            val t9 = Search.isT9(raw)
            val query = if (t9) raw else Search.normalizeText(raw)
            if (query.isEmpty()) return label
            // Letters and digits of the label, each with its place in the label.
            val chars = label.withIndex().filter { it.value.isLetterOrDigit() }
            fun key(c: Char): Char {
                val plain = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0].lowercaseChar()
                return if (t9) Search.toDigits(plain.toString())[0] else plain
            }
            val keys = chars.map { key(it.value) }.joinToString("")
            val wordStarts = chars.indices.filter { i ->
                val at = chars[i].index
                at == 0 || !label[at - 1].isLetterOrDigit() || (label[at].isUpperCase() && label[at - 1].isLowerCase())
            }
            val picked: List<Int> = run {
                // A word that starts with the query, else the query anywhere, else word initials.
                wordStarts.firstOrNull { keys.startsWith(query, it) }?.let { s -> return@run (s until s + query.length).toList() }
                keys.indexOf(query).takeIf { it >= 0 }?.let { s -> return@run (s until s + query.length).toList() }
                if (query.length <= wordStarts.size) {
                    for (first in 0..wordStarts.size - query.length) {
                        val run = wordStarts.subList(first, first + query.length)
                        if (run.withIndex().all { (i, s) -> keys[s] == query[i] }) return@run run
                    }
                }
                emptyList()
            }
            val span = SpannableString(label)
            // The rest of the name steps back so the matched letters stand out.
            if (picked.isNotEmpty()) span.setSpan(ForegroundColorSpan(dim), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            picked.forEach { i ->
                val at = chars[i].index
                span.setSpan(ForegroundColorSpan(color), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return span
        }
    }
}
