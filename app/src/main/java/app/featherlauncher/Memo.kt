package app.featherlauncher

/**
 * Remembers the last [size] answers of something that always gives the same answer for the same
 * question (a day's sunset, a week's parsha), so the widgets don't work them out every minute.
 */
class Memo<K, V>(private val size: Int) {
    private val map = object : LinkedHashMap<K, V>(size, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = this.size > this@Memo.size
    }

    @Synchronized
    fun get(key: K, compute: () -> V): V {
        if (map.containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            return map[key] as V
        }
        return compute().also { map[key] = it }
    }
}
