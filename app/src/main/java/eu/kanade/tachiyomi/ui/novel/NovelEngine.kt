package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class NovelItem(val title: String, val url: String, val cover: String)

data class NovelChapterRef(val title: String, val url: String)

data class NovelDetail(
    val title: String,
    val cover: String,
    val description: String,
    val chapters: List<NovelChapterRef>,
)

object NovelCache {
    val details = HashMap<String, NovelDetail>()
}

object NovelProgress {
    private const val PREFS = "aniverse_novel_progress"

    fun get(context: Context, novelUrl: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(novelUrl, -1)

    fun set(context: Context, novelUrl: String, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(novelUrl, index)
            .apply()
    }
}

// "css@attr" -> attr: text (default), html, or any attribute name.
private fun Element.pick(spec: String): String {
    if (spec.isBlank()) return ""
    val at = spec.lastIndexOf('@')
    val css = (if (at >= 0) spec.substring(0, at) else spec).trim()
    val attr = (if (at >= 0) spec.substring(at + 1) else "text").trim().ifEmpty { "text" }
    val el: Element = if (css.isEmpty()) this else (selectFirst(css) ?: return "")
    return when (attr) {
        "text" -> el.text().trim()
        "html" -> el.html().trim()
        else -> {
            val isUrl = attr == "href" || attr.contains("src") || attr.contains("url")
            val abs = if (isUrl) el.absUrl(attr) else ""
            (if (abs.isNotEmpty()) abs else el.attr(attr)).trim()
        }
    }
}

object NovelEngine {

    private fun load(url: String) = Jsoup.parse(NovelHttp.fetchText(url), url)

    fun search(src: NovelSourceDef, query: String, page: Int = 1): List<NovelItem> {
        val url = src.searchUrl
            .replace("{baseUrl}", src.baseUrl)
            .replace("{query}", URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", page.toString())
        val doc = load(url)
        return doc.select(src.searchList).mapNotNull { el ->
            val link = el.pick(src.searchLink)
            val title = el.pick(src.searchTitle)
            if (link.isEmpty() || title.isEmpty()) {
                null
            } else {
                NovelItem(title, link, el.pick(src.searchCover))
            }
        }.distinctBy { it.url }
    }

    fun detail(src: NovelSourceDef, url: String): NovelDetail {
        val doc = load(url)
        var chapters = doc.select(src.chapterList).mapNotNull { el ->
            val link = el.pick(src.chapterLink)
            if (link.isEmpty()) {
                null
            } else {
                val t = el.pick(src.chapterTitle).ifEmpty { el.text().trim() }
                NovelChapterRef(t.ifEmpty { link }, link)
            }
        }.distinctBy { it.url }
        if (src.chaptersReverse) chapters = chapters.reversed()
        val title = doc.pick(src.detailTitle).ifEmpty { doc.title() }
        // Page itself may be a chapter: no list found -> treat it as a single chapter.
        if (chapters.isEmpty()) chapters = listOf(NovelChapterRef(title, url))
        return NovelDetail(
            title = title,
            cover = doc.pick(src.detailCover),
            description = doc.pick(src.detailDescription),
            chapters = chapters,
        )
    }

    fun content(src: NovelSourceDef, url: String): List<String> {
        val doc = load(url)
        val box = doc.selectFirst(src.contentContainer) ?: error("Content container not found")
        src.contentRemove.forEach { box.select(it).remove() }
        val paragraphs = box.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isNotEmpty()) return paragraphs
        val html = box.html().replace(Regex("(?i)<br\\s*/?>"), "\n")
        return Jsoup.parse(html).wholeText().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
