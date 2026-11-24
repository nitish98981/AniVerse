package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

internal data class NovelEntry(
    val uri: String,
    val title: String,
    val type: String,
    val lastOpened: Long,
)

internal object NovelLibraryStore {

    private const val PREFS = "aniverse_novel_library"
    private const val KEY = "entries"

    fun load(context: Context): List<NovelEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                NovelEntry(
                    uri = item.getString("uri"),
                    title = item.getString("title"),
                    type = item.getString("type"),
                    lastOpened = item.optLong("last", 0L),
                )
            }
        }.getOrDefault(emptyList())
        return parsed.sortedByDescending { it.lastOpened }
    }

    private fun save(context: Context, entries: List<NovelEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val item = JSONObject()
            item.put("uri", entry.uri)
            item.put("title", entry.title)
            item.put("type", entry.type)
            item.put("last", entry.lastOpened)
            array.put(item)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    fun add(context: Context, uri: Uri) {
        val name = nameOf(context, uri)
        val key = uri.toString()
        val others = load(context).filter { it.uri != key }
        val entry = NovelEntry(
            uri = key,
            title = name.substringBeforeLast('.'),
            type = typeOf(context, uri, name),
            lastOpened = System.currentTimeMillis(),
        )
        save(context, others + entry)
    }

    fun touch(context: Context, uri: String) {
        val updated = load(context).map {
            if (it.uri == uri) it.copy(lastOpened = System.currentTimeMillis()) else it
        }
        save(context, updated)
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
    }

    private fun nameOf(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "Book"
    }

    private fun typeOf(context: Context, uri: Uri, name: String): String {
        val mime = context.contentResolver.getType(uri)
        return when {
            name.endsWith(".pdf", ignoreCase = true) || mime == "application/pdf" -> "pdf"
            name.endsWith(".epub", ignoreCase = true) || mime == "application/epub+zip" -> "epub"
            else -> "txt"
        }
    }
}
