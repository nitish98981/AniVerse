package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NovelSourceDef(
    val id: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val searchUrl: String,
    val searchList: String,
    val searchTitle: String,
    val searchLink: String,
    val searchCover: String,
    val detailTitle: String,
    val detailCover: String,
    val detailDescription: String,
    val chapterList: String,
    val chapterTitle: String,
    val chapterLink: String,
    val chaptersReverse: Boolean,
    val contentContainer: String,
    val contentRemove: List<String>,
    val raw: String,
) {
    companion object {
        fun parse(text: String): NovelSourceDef {
            val root = JSONObject(text)
            val search = root.getJSONObject("search")
            val detail = root.getJSONObject("detail")
            val content = root.getJSONObject("content")

            fun req(obj: JSONObject, key: String): String {
                val value = obj.optString(key, "").trim()
                require(value.isNotEmpty()) { "Missing field: $key" }
                return value
            }

            val removeArray = content.optJSONArray("remove")
            val remove = ArrayList<String>()
            if (removeArray != null) {
                for (i in 0 until removeArray.length()) {
                    remove.add(removeArray.getString(i))
                }
            }

            return NovelSourceDef(
                id = req(root, "id"),
                name = req(root, "name"),
                lang = root.optString("lang", "all"),
                baseUrl = req(root, "baseUrl").trimEnd('/'),
                searchUrl = req(search, "url"),
                searchList = req(search, "list"),
                searchTitle = req(search, "title"),
                searchLink = req(search, "link"),
                searchCover = search.optString("cover", ""),
                detailTitle = detail.optString("title", ""),
                detailCover = detail.optString("cover", ""),
                detailDescription = detail.optString("description", ""),
                chapterList = req(detail, "chapterList"),
                chapterTitle = detail.optString("chapterTitle", ""),
                chapterLink = req(detail, "chapterLink"),
                chaptersReverse = detail.optBoolean("reverse", false),
                contentContainer = req(content, "container"),
                contentRemove = remove,
                raw = root.toString(),
            )
        }
    }
}

object NovelHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body.string()
        }
    }
}

object NovelSourceStore {
    private const val PREFS = "aniverse_novel_sources"
    private const val KEY = "sources"

    fun load(context: Context): List<NovelSourceDef> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                runCatching { NovelSourceDef.parse(array.getString(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, raw: String): NovelSourceDef {
        val def = NovelSourceDef.parse(raw)
        save(context, load(context).filter { it.id != def.id } + def)
        return def
            fun addAny(context: Context, raw: String): List<NovelSourceDef> {
        val text = raw.trim()
        val items = ArrayList<String>()

        fun collect(array: JSONArray) {
            for (i in 0 until array.length()) {
                when (val v = array.get(i)) {
                    is JSONObject -> items.add(v.toString())
                    is String -> items.add(NovelHttp.fetchText(v))
                }
            }
        }

        if (text.startsWith("[")) {
            collect(JSONArray(text))
        } else {
            val obj = JSONObject(text)
            val array = obj.optJSONArray("sources")
            if (array != null) collect(array) else items.add(text)
        }
        return items.map { add(context, it) }
            }
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    private fun save(context: Context, list: List<NovelSourceDef>) {
        val array = JSONArray()
        list.forEach { array.put(it.raw) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
