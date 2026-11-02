package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Photo Shuffle: the wallpaper changes by itself through a set of photos, either your own
 * (copied into Feather, so they stay even if the originals go) or one of the collections. It
 * moves on when the screen turns on, every hour, or every day, looked at whenever the screen
 * turns on or the home screen shows.
 */
object Shuffle {
    enum class Every(val label: String, val ms: Long) {
        WAKE("Each time the screen turns on", 0),
        HOUR("Every hour", 60 * 60_000L),
        DAY("Every day", 24 * 60 * 60_000L),
    }

    // Behind whatever's on screen: it runs as the phone wakes, while the lock screen animates.
    private val io = Executors.newSingleThreadExecutor { work ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private fun folder(context: Context) = File(context.filesDir, "shuffle")

    /** The collections' photos, kept once downloaded, so the shuffle doesn't download one at every wake. */
    private fun downloads(context: Context) = File(context.cacheDir, "shuffle")

    /** Your own photos in the shuffle, in order. */
    fun photos(context: Context): List<File> = folder(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** Copies [uris] in as the shuffle's photos, replacing any before, and starts it. */
    fun startWithPhotos(context: Context, uris: List<Uri>, done: (Boolean) -> Unit) = io.execute {
        val dir = folder(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val (w, h) = screen(context)
        uris.forEachIndexed { i, uri ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching
                // Kept cropped to the screen, so each change hands it to Android as it is.
                val cropped = crop(bitmap, w, h)
                bitmap.recycle()
                File(dir, "%03d.jpg".format(i)).outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                cropped.recycle()
            }
        }
        val ok = photos(context).isNotEmpty()
        main.post {
            if (ok) start(context, null, done) else done(false)
        }
    }

    /** Starts shuffling: [collection] names one of the collections, or null for your own photos. */
    fun start(context: Context, collection: String?, done: (Boolean) -> Unit) {
        val prefs = Prefs(context)
        prefs.shuffleOn = true
        prefs.shuffleCollection = collection
        prefs.shuffleIndex = -1
        prefs.shuffleLast = 0
        downloads(context).deleteRecursively()
        // One wallpaper that changes by itself at a time, and none of the light and dark pair.
        WeatherWallpaper.stop(context)
        DarkMode.clearPair(context)
        next(context, force = true, done = done)
    }

    fun stop(context: Context) {
        Prefs(context).shuffleOn = false
    }

    /** Called when the screen turns on ([screenOn]) or the home screen shows: moves on if it's time. */
    fun next(context: Context, screenOn: Boolean = false, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.shuffleOn || busy) return
        val every = prefs.shuffleEvery
        val now = System.currentTimeMillis()
        val due = force || if (every == Every.WAKE) screenOn else now - prefs.shuffleLast >= every.ms
        if (!due) return
        val collection = prefs.shuffleCollection
        val ids = collection?.let { name -> WallpaperActivity.collection(name) }
        val count = ids?.size ?: photos(app).size
        if (count == 0) return
        val index = (prefs.shuffleIndex + 1) % count
        busy = true
        io.execute {
            val (w, h) = screen(app)
            val file = if (ids != null) downloaded(app, ids[index], w, h) else photos(app)[index]
            val ok = file != null && runCatching { set(app, file, w, h) }.isSuccess
            // A download that turned out not to be a picture is fetched again next time.
            if (!ok && ids != null) file?.delete()
            main.post {
                busy = false
                // Offline (a collection's photo didn't come): it tries again next time.
                if (ok) {
                    prefs.shuffleIndex = index
                    prefs.shuffleLast = now
                }
                done?.invoke(ok)
            }
        }
    }

    /**
     * Sets [file] as the wallpaper: as it is when it's already the screen's size (no decoding,
     * scaling or encoding here), otherwise cropped to it first.
     */
    private fun set(context: Context, file: File, w: Int, h: Int) {
        val manager = WallpaperManager.getInstance(context)
        val flags = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth == w && bounds.outHeight == h) {
            file.inputStream().use { manager.setStream(it, null, true, flags) }
        } else {
            val picture = BitmapFactory.decodeFile(file.path)?.let { crop(it, w, h) } ?: error("Can't read ${file.name}")
            manager.setBitmap(picture, null, true, flags)
            picture.recycle()
        }
    }

    /** Photo [id] of a collection at [w] by [h], downloaded the first time; null offline. */
    private fun downloaded(context: Context, id: Int, w: Int, h: Int): File? {
        val file = File(downloads(context).apply { mkdirs() }, "$id-${w}x$h.jpg")
        if (file.exists()) return file
        val part = File(file.path + ".part")
        return runCatching {
            (URL("https://picsum.photos/id/$id/$w/$h").openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            }
            // A filter's or a hotspot's page in place of the photo isn't kept.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.path, bounds)
            check(bounds.outWidth > 0)
            check(part.renameTo(file))
            file
        }.getOrElse { part.delete(); null }
    }

    /** [bitmap] scaled to cover [w] by [h], centered. */
    fun crop(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val sw = (w / scale).toInt()
        val sh = (h / scale).toInt()
        val left = (bitmap.width - sw) / 2
        val top = (bitmap.height - sh) / 2
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, Rect(left, top, left + sw, top + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun screen(context: Context): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }
}
