package app.featherlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import java.io.File
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The installed app list and icons. Both are cached on disk so a cold start
 * (Android kills the launcher often on this phone) draws immediately; the live
 * list is then reloaded in the background and only changed icons are re-rendered.
 */
class AppRepository(private val context: Context, shape: IconShape) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listFile = File(context.filesDir, "apps.tsv")
    private val iconSize = (context.resources.displayMetrics.density * 64).roundToInt()

    @Volatile var shape: IconShape = shape
        private set
    /** Dark or tinted icons (in dark mode, say), and the tint color. */
    @Volatile var look: IconLook = IconLook.NORMAL
        private set
    @Volatile private var tint = 0
    // Icons are cached per shape and look, so switching back is instant.
    @Volatile private var iconDir = iconDirFor(shape)

    @Volatile var apps: List<AppEntry> = emptyList()
        private set
    @Volatile private var icons: Map<String, Bitmap> = emptyMap()
    private var byKey: Map<String, AppEntry> = emptyMap()
    @Volatile private var refreshQueued = false

    var onChanged: (() -> Unit)? = null

    fun find(key: String?): AppEntry? = key?.let(byKey::get)

    fun icon(entry: AppEntry): Bitmap? = icons[entry.key]

    /** Loads the cache; [priorityKeys] (the dock) get their icons decoded before the first frame. */
    private var started = false

    fun start(priorityKeys: Collection<String>) {
        if (started) return
        started = true
        setApps(readList())
        icons = priorityKeys.mapNotNull { key -> decodeIcon(key)?.let { key to it } }.toMap()
        iconsDir = iconDir
        io.execute {
            val dir = iconDir
            val cached = apps.mapNotNull { e -> (icons[e.key] ?: decodeIcon(e.key, dir))?.let { e.key to it } }.toMap()
            main.post {
                if (dir == iconDir) icons = cached
                onChanged?.invoke()
            }
            reload(cached)
        }
        launcherApps.registerCallback(callback, main)
        // A new day: the Calendar icon shows the new date.
        context.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
        }, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    fun setShape(newShape: IconShape) {
        if (newShape == shape) return
        shape = newShape
        rebuildIcons()
    }

    /** Switches between normal, dark and tinted icons; ones drawn before come straight from disk. */
    fun setLook(newLook: IconLook, newTint: Int) {
        val tintKey = if (newLook == IconLook.TINTED) newTint else 0
        if (newLook == look && tintKey == tint) return
        look = newLook
        tint = tintKey
        rebuildIcons()
    }

    private fun rebuildIcons() {
        iconDir = iconDirFor(shape)
        io.execute {
            val cached = apps.mapNotNull { e -> decodeIcon(e.key)?.let { e.key to it } }.toMap()
            reload(cached)
        }
    }

    private fun iconDirFor(shape: IconShape): File {
        val variant = when (look) {
            IconLook.NORMAL -> ""
            // Versioned, so dark icons saved by an older version are drawn again.
            IconLook.DARK -> "_dark$LOOK_VERSION"
            IconLook.TINTED -> "_tint${LOOK_VERSION}_%06x".format(tint and 0xFFFFFF)
        }
        return File(context.filesDir, "icons/${shape.name.lowercase()}$variant").apply { mkdirs() }
    }

    fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        io.execute {
            refreshQueued = false
            reload()
        }
    }

    private fun setApps(list: List<AppEntry>) {
        apps = list
        byKey = list.associateBy { it.key }
    }

    /** The folder the icons in memory were drawn for (a shape and look). */
    @Volatile private var iconsDir: File? = null

    private fun reload(currentIcons: Map<String, Bitmap> = icons) {
        // One consistent look for the whole pass: if it changes meanwhile, this pass is dropped
        // and the newer one (already queued) takes over, so light and dark icons never mix.
        val dir = iconDir
        val passLook = look
        val passTint = tint
        // Icons in memory only count if they were drawn for this look; a map passed in was read from this folder.
        val reusable = if (currentIcons !== icons || iconsDir == dir) currentIcons else emptyMap()
        val pm = context.packageManager
        val previous = apps.associateBy { it.key }
        val newIcons = HashMap<String, Bitmap>()
        val collator = Collator.getInstance()

        val fresh = launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != context.packageName }
            .map { info ->
                val key = info.componentName.flattenToShortString()
                val packageInfo = runCatching { pm.getPackageInfo(info.componentName.packageName, 0) }.getOrNull()
                val updatedAt = packageInfo?.lastUpdateTime ?: 0L
                val old = previous[key]
                val cachedIcon = reusable[key] ?: decodeIcon(key, dir)
                // Apps whose icon shows today's date (Google Calendar) are drawn fresh each day.
                val dated = datedIcon(info)
                newIcons[key] = if (dated == null && old != null && old.updatedAt == updatedAt && cachedIcon != null) {
                    cachedIcon
                } else {
                    val mono = if (passLook == IconLook.TINTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                        IconLooks.monochromeOf(context, info.componentName) else null
                    shape.render(dated ?: info.getIcon(DisplayMetrics.DENSITY_XHIGH), iconSize, passLook, passTint, mono).also { saveIcon(key, it, dir) }
                }
                AppEntry(info.componentName, info.label.toString(), updatedAt,
                    Category.of(info.componentName.packageName, packageInfo?.applicationInfo))
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }

        val removed = previous.keys - fresh.map { it.key }.toSet()
        removed.forEach { iconFile(it).delete() }
        writeList(fresh)

        main.post {
            setApps(fresh)
            if (dir == iconDir) {
                icons = newIcons
                iconsDir = dir
            }
            onChanged?.invoke()
        }
    }

    /**
     * Today's icon for apps that have one per day of the month, as Pixel phones show them:
     * Google Calendar lists 31 icons under this meta-data name.
     */
    private fun datedIcon(info: android.content.pm.LauncherActivityInfo): android.graphics.drawable.Drawable? = runCatching {
        val pm = context.packageManager
        val activity = pm.getActivityInfo(info.componentName, android.content.pm.PackageManager.GET_META_DATA)
        val res = activity.metaData?.getInt(DATED_ICONS)?.takeIf { it != 0 } ?: return null
        val resources = pm.getResourcesForApplication(info.componentName.packageName)
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        val array = resources.obtainTypedArray(res)
        try {
            val id = array.getResourceId(day - 1, 0).takeIf { it != 0 } ?: return null
            resources.getDrawableForDensity(id, DisplayMetrics.DENSITY_XHIGH, null)
        } finally {
            array.recycle()
        }
    }.getOrNull()

    private fun iconFile(key: String, dir: File = iconDir) = File(dir, key.replace('/', '_') + ".png")

    private fun decodeIcon(key: String, dir: File = iconDir): Bitmap? = iconFile(key, dir).takeIf { it.exists() }?.let {
        BitmapFactory.decodeFile(it.path)
    }

    private fun saveIcon(key: String, bitmap: Bitmap, dir: File = iconDir) {
        runCatching { iconFile(key, dir).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun readList(): List<AppEntry> = runCatching {
        listFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val component = ComponentName.unflattenFromString(parts[0]) ?: return@mapNotNull null
            val category = parts.getOrNull(3)?.let { runCatching { Category.valueOf(it) }.getOrNull() } ?: Category.OTHER
            AppEntry(component, parts[1], parts[2].toLongOrNull() ?: 0L, category)
        }
    }.getOrDefault(emptyList())

    private fun writeList(list: List<AppEntry>) {
        val tmp = File(listFile.path + ".tmp")
        tmp.writeText(list.joinToString("\n") { "${it.key}\t${it.label.replace('\t', ' ')}\t${it.updatedAt}\t${it.category.name}" })
        tmp.renameTo(listFile)
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    private companion object {
        /** Bump when dark or tinted icons are drawn differently. */
        const val LOOK_VERSION = 30
        const val DATED_ICONS = "com.google.android.calendar.dynamic_icons"
    }
}
