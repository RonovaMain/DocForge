package dev.ronova.docforge

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Список недавно открытых файлов (хранится в настройках приложения). */
class RecentFiles(context: Context) {
    class Entry(val uri: Uri, val name: String)

    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Entry(Uri.parse(o.getString("u")), o.getString("n"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(uri: Uri, name: String) {
        val items = list().filter { it.uri != uri }.toMutableList()
        items.add(0, Entry(uri, name))
        save(items.take(MAX))
    }

    fun remove(uri: Uri) = save(list().filter { it.uri != uri })

    fun clear() = save(emptyList())

    private fun save(items: List<Entry>) {
        val array = JSONArray()
        for (e in items) array.put(JSONObject().put("u", e.uri.toString()).put("n", e.name))
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 12
    }
}
