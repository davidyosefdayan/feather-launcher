package app.featherlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Updates from the app's GitHub releases, without an app store. Once a day, when the home screen
 * opens, it looks at the latest release; when that's newer than this version, Settings offers it
 * and a notification says so. Installing downloads the APK, checks it against the release, and
 * hands it to Android's installer, which only accepts it if it's signed with the same key.
 */
class Updates private constructor(private val context: Context) {
    class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?, val notes: String)

    private val sp = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val currentVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** The newest release, if it's newer than this version. */
    var available: Release? = load()
        private set

    /** Downloading or installing right now. */
    var busy = false
        private set

    /** Checks again if the last check was a day or more ago. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - sp.getLong("checked", 0) < DAY) return
        check { if (it) notifyIfNew() }
    }

    /** Looks at the latest release; [done] gets false if GitHub couldn't be reached. */
    fun check(done: (Boolean) -> Unit) {
        io.execute {
            val latest = runCatching { retrying { parse(JSONObject(fetch(LATEST))) } }
            main.post {
                latest.onSuccess { release ->
                    sp.edit().putLong("checked", System.currentTimeMillis()).apply()
                    save(release?.takeIf { isNewer(it.version, currentVersion) })
                }
                done(latest.isSuccess)
            }
        }
    }

    /**
     * Downloads [release] and asks Android to install it. [progress] gets 0 to 100 while
     * downloading; [failed] gets a reason to show if it doesn't get as far as Android's prompt.
     */
    fun install(release: Release, progress: (Int) -> Unit, failed: (String) -> Unit) {
        if (busy) return
        busy = true
        io.execute {
            val result = runCatching {
                val apk = download(release) { pct -> main.post { progress(pct) } }
                commit(apk)
            }
            main.post {
                busy = false
                result.onFailure { failed(it.message ?: "Couldn't download the update") }
            }
        }
    }

    private fun download(release: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "FeatherLauncher-${release.version}.apk")
        // One update on disk at a time; a good copy from an earlier try is used again.
        dir.listFiles()?.forEach { if (it != apk) it.delete() }
        if (apk.exists() && problem(apk, release) == null) return apk
        val part = File(dir, apk.name + ".part")
        runCatching { retrying {
            open(release.apkUrl).inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (release.size > 0) progress((100 * read / release.size).toInt())
                    }
                }
            }
        } }.onFailure {
            part.delete()
            throw IllegalStateException("Couldn't download the update. Check your connection.")
        }
        problem(part, release)?.let {
            part.delete()
            throw IllegalStateException(it)
        }
        part.renameTo(apk)
        return apk
    }

    /** What's wrong with a downloaded file, or null when it's the file the release lists. */
    private fun problem(file: File, release: Release): String? = when {
        release.size > 0 && file.length() != release.size -> "The download was incomplete. Try again."
        release.sha256 != null && sha256(file) != release.sha256 -> "The download was damaged. Try again."
        else -> null
    }

    /** Hands the APK to Android's installer; [UpdateReceiver] hears how it went. */
    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once this app installed the last update, Android lets the next go through without
            // asking again. Android 11 always asks.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer adds the result to it.
            val status = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(status.intentSender)
        }
    }

    /** A notification the first time a version is found; tapping it opens Settings. */
    private fun notifyIfNew() {
        val release = available ?: return
        if (sp.getString("notified", null) == release.version) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        sp.edit().putString("notified", release.version).apply()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_arrow_downward)
            .setContentTitle("Feather Launcher ${release.version} is available")
            .setContentText("Tap to see what's new and update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    private fun save(release: Release?) {
        available = release
        sp.edit().apply {
            if (release == null) remove("release")
            else putString("release", JSONObject()
                .put("version", release.version).put("url", release.apkUrl).put("size", release.size)
                .put("sha256", release.sha256).put("notes", release.notes).toString())
        }.apply()
    }

    /** The saved release, unless this version has caught up with it (it was just installed). */
    private fun load(): Release? = runCatching {
        val o = JSONObject(sp.getString("release", null) ?: return null)
        Release(o.getString("version"), o.getString("url"), o.optLong("size"), o.optString("sha256").ifEmpty { null }, o.optString("notes"))
    }.getOrNull()?.takeIf { isNewer(it.version, currentVersion) }

    private fun fetch(url: String): String = open(url).apply {
        setRequestProperty("Accept", "application/vnd.github+json")
    }.inputStream.bufferedReader().use { it.readText() }

    /**
     * Tries [block] up to three times. Internet filters on many phones (Livigent, NetSpark) often
     * turn away the first connection to a site and let the next one through.
     */
    private fun <T> retrying(block: () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(800L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/davidyosefdayan/feather-launcher/releases/latest"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val CHANNEL = "updates"
        private const val NOTIFICATION = 7001

        @Volatile private var instance: Updates? = null

        fun get(context: Context): Updates =
            instance ?: synchronized(this) { instance ?: Updates(context.applicationContext).also { instance = it } }

        /** A release with its APK, or null when it has none. Tags look like "v1.2". */
        private fun parse(o: JSONObject): Release? {
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            // GitHub publishes each file's checksum as "sha256:<hex>".
            val digest = apk.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
            return Release(o.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"),
                apk.optLong("size"), digest, plain(o.optString("body")))
        }

        /** Release notes are Markdown on GitHub; in the app they're plain text with bullets. */
        private fun plain(markdown: String) = markdown.lines().joinToString("\n") { line ->
            line.replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""^(\s*)[-*]\s+"""), "$1• ")
        }.trim()

        /** Compares versions like "1.10" and "1.9" number by number. */
        fun isNewer(version: String, than: String): Boolean {
            val a = version.split('.').map { it.toIntOrNull() ?: 0 }
            val b = than.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Hears back from Android's installer: shows its prompt, or says why the update failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Updated: Android closed Feather to replace it, leaving whatever app was open before on
        // screen until it starts the home screen again. Start it now instead.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Not over the lock screen: it would cover it while it stays locked underneath, taking
            // the keypad. Unlocking shows the home screen anyway.
            if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // When it works, Android restarts the launcher as the new version.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Cancelled on Android's prompt: nothing to say.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, "The update didn't install. Try again from Settings.", Toast.LENGTH_LONG).show()
        }
    }
}
