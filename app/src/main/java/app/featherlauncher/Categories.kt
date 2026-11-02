package app.featherlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app list's categories, which the user can add, rename, reorder and delete.
 * Each app goes where the user put it, or else to its automatic [Category].
 */
class Categories(context: Context) {
    class Item(val id: String, var name: String, val custom: Boolean, var icon: String) {
        val iconRes get() = ICONS[icon] ?: R.drawable.ic_cat_category
        /** The filled version, for the selected tab. */
        val filledIconRes get() = FILLED[icon] ?: R.drawable.ic_cat_category_fill
    }

    private val sp = context.getSharedPreferences("categories", Context.MODE_PRIVATE)
    val list = mutableListOf<Item>()
    /** App key → category id, for apps the user moved. */
    private val assigned = HashMap<String, String>()
    /** Category id → app keys in the order the user arranged them. */
    private val order = HashMap<String, MutableList<String>>()

    init {
        val saved = sp.getString("list", null)
        if (saved == null) {
            Category.entries.forEach { list += Item(it.name, it.label, custom = false, defaultIcon(it.name)) }
        } else {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.getString("id")
                list += Item(id, o.getString("name"), o.optBoolean("custom"), o.optString("icon").ifEmpty { defaultIcon(id) })
            }
        }
        val map = JSONObject(sp.getString("assigned", "{}")!!)
        map.keys().forEach { assigned[it] = map.getString(it) }
        val orders = JSONObject(sp.getString("order", "{}")!!)
        orders.keys().forEach { id ->
            val a = orders.getJSONArray(id)
            order[id] = MutableList(a.length()) { a.getString(it) }
        }
    }

    private fun save() {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("custom", it.custom).put("icon", it.icon)) }
        val orders = JSONObject()
        order.forEach { (id, keys) -> orders.put(id, JSONArray(keys)) }
        sp.edit()
            .putString("list", array.toString())
            .putString("assigned", JSONObject(assigned as Map<*, *>).toString())
            .putString("order", orders.toString())
            .apply()
    }

    fun find(id: String?) = list.firstOrNull { it.id == id }

    /** The category id an app shows under. */
    fun of(entry: AppEntry): String {
        assigned[entry.key]?.let { id -> if (find(id) != null) return id }
        if (find(entry.category.name) != null) return entry.category.name
        sp.getString("fallback", null)?.let { id -> if (find(id) != null) return id }
        return find(Category.OTHER.name)?.id ?: list.first().id
    }

    /**
     * Replaces every category with [layout] (name, icon, apps in order). Apps not placed go to
     * the category named [fallback].
     */
    fun replaceAll(layout: List<Triple<String, String, List<AppEntry>>>, fallback: String) {
        list.clear()
        assigned.clear()
        order.clear()
        layout.forEachIndexed { i, (name, icon, apps) ->
            val id = "custom_import_$i"
            list += Item(id, name, custom = true, icon)
            apps.forEach { assigned[it.key] = id }
            order[id] = apps.map { it.key }.toMutableList()
            if (name == fallback) sp.edit().putString("fallback", id).apply()
        }
        save()
    }

    /** [apps] (all in category [id]) in the user's order; apps never placed come after, alphabetically. */
    fun sorted(id: String, apps: List<AppEntry>): List<AppEntry> {
        val keys = order[id] ?: return apps
        val rank = keys.withIndex().associate { it.value to it.index }
        return apps.sortedWith(compareBy { rank[it.key] ?: Int.MAX_VALUE })
    }

    /** Remembers the order of a category's apps. */
    fun setOrder(id: String, keys: List<String>) {
        order[id] = keys.toMutableList()
        save()
    }

    fun assign(entry: AppEntry, id: String) {
        if (id == entry.category.name) assigned.remove(entry.key) else assigned[entry.key] = id
        save()
    }

    fun setIcon(id: String, icon: String) {
        find(id)?.icon = icon
        save()
    }

    fun add(name: String): Item = Item("custom_${System.currentTimeMillis()}", name, custom = true, "star").also {
        list += it
        save()
    }

    fun rename(id: String, name: String) {
        find(id)?.name = name
        save()
    }

    /** Puts the categories in [ids] in that order, in the places they already take (others stay put). */
    fun reorder(ids: List<String>) {
        val slots = list.indices.filter { list[it].id in ids }
        val items = ids.mapNotNull(::find)
        if (items.size != slots.size) return
        slots.forEachIndexed { i, slot -> list[slot] = items[i] }
        save()
    }

    fun move(id: String, by: Int) {
        val from = list.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save()
    }

    /** Deletes it; its apps go back to their automatic category. Every app needs somewhere to go, so the last one stays. */
    fun delete(id: String) {
        if (list.size <= 1) return
        list.removeAll { it.id == id }
        assigned.values.removeAll { it == id }
        if (find(Category.OTHER.name) == null && list.none { !it.custom && it.id == Category.OTHER.name }) {
            // Apps whose automatic category was deleted fall into Other, so bring it back.
            list += Item(Category.OTHER.name, Category.OTHER.label, custom = false, defaultIcon(Category.OTHER.name))
        }
        save()
    }

    companion object {
        /** The hidden apps page, for [sorted] and [setOrder]: its apps keep an order too, though it's no category. */
        const val HIDDEN = "hidden"

        /** Icons a category can use, by name. */
        val ICONS = linkedMapOf(
            "chat" to R.drawable.ic_cat_chat, "phone" to R.drawable.ic_cat_phone, "mail" to R.drawable.ic_cat_mail,
            "people" to R.drawable.ic_cat_people, "play_circle" to R.drawable.ic_cat_play_circle,
            "music_note" to R.drawable.ic_cat_music_note, "photo_camera" to R.drawable.ic_cat_photo_camera,
            "theater_comedy" to R.drawable.ic_cat_theater_comedy, "work" to R.drawable.ic_cat_work,
            "school" to R.drawable.ic_cat_school, "menu_book" to R.drawable.ic_cat_menu_book,
            "newspaper" to R.drawable.ic_cat_newspaper, "explore" to R.drawable.ic_cat_explore, "map" to R.drawable.ic_cat_map,
            "flight" to R.drawable.ic_cat_flight, "public" to R.drawable.ic_cat_public, "payments" to R.drawable.ic_cat_payments,
            "shopping_cart" to R.drawable.ic_cat_shopping_cart, "restaurant" to R.drawable.ic_cat_restaurant,
            "fitness_center" to R.drawable.ic_cat_fitness_center, "favorite" to R.drawable.ic_cat_favorite,
            "star" to R.drawable.ic_cat_star, "home" to R.drawable.ic_cat_home, "sports_esports" to R.drawable.ic_cat_sports_esports,
            "build" to R.drawable.ic_cat_build, "category" to R.drawable.ic_cat_category, "apps" to R.drawable.ic_cat_apps,
            "settings" to R.drawable.ic_cat_settings,
        )

        val FILLED = mapOf(
            "chat" to R.drawable.ic_cat_chat_fill, "phone" to R.drawable.ic_cat_phone_fill, "mail" to R.drawable.ic_cat_mail_fill,
            "people" to R.drawable.ic_cat_people_fill, "play_circle" to R.drawable.ic_cat_play_circle_fill,
            "music_note" to R.drawable.ic_cat_music_note_fill, "photo_camera" to R.drawable.ic_cat_photo_camera_fill,
            "theater_comedy" to R.drawable.ic_cat_theater_comedy_fill, "work" to R.drawable.ic_cat_work_fill,
            "school" to R.drawable.ic_cat_school_fill, "menu_book" to R.drawable.ic_cat_menu_book_fill,
            "newspaper" to R.drawable.ic_cat_newspaper_fill, "explore" to R.drawable.ic_cat_explore_fill, "map" to R.drawable.ic_cat_map_fill,
            "flight" to R.drawable.ic_cat_flight_fill, "public" to R.drawable.ic_cat_public_fill, "payments" to R.drawable.ic_cat_payments_fill,
            "shopping_cart" to R.drawable.ic_cat_shopping_cart_fill, "restaurant" to R.drawable.ic_cat_restaurant_fill,
            "fitness_center" to R.drawable.ic_cat_fitness_center_fill, "favorite" to R.drawable.ic_cat_favorite_fill,
            "star" to R.drawable.ic_cat_star_fill, "home" to R.drawable.ic_cat_home_fill, "sports_esports" to R.drawable.ic_cat_sports_esports_fill,
            "build" to R.drawable.ic_cat_build_fill, "category" to R.drawable.ic_cat_category_fill, "apps" to R.drawable.ic_cat_apps_fill,
            "settings" to R.drawable.ic_cat_settings_fill,
        )

        private fun defaultIcon(id: String) = when (id) {
            "COMMUNICATION" -> "chat"
            "MEDIA" -> "play_circle"
            "PRODUCTIVITY" -> "work"
            "TRAVEL" -> "explore"
            "READING" -> "menu_book"
            "GAMES" -> "sports_esports"
            "TOOLS" -> "build"
            else -> "category"
        }
    }
}
