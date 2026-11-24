package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipInputStream

private const val PREFS_NAME = "aniverse_novel"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_THEME = "theme"
private const val KEY_LAST_URI = "last_uri"
private const val IMAGE_START = "[[IMG:"
private const val IMAGE_END = "]]"
private val THEME_NAMES = listOf("App theme", "Light", "Sepia", "Dark")

private class NovelBook(val title: String, val chapters: List<NovelChapter>)

private class NovelChapter(val title: String, val paragraphs: List<String>)

class NovelReaderScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

        var fontSize by remember { mutableFloatStateOf(prefs.getFloat(KEY_FONT_SIZE, 18f)) }
        var theme by remember { mutableIntStateOf(prefs.getInt(KEY_THEME, 0)) }
        var uri by remember { mutableStateOf(prefs.getString(KEY_LAST_URI, null)?.let { Uri.parse(it) }) }
        var book by remember { mutableStateOf<NovelBook?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var chapterIndex by remember { mutableIntStateOf(0) }
        var startIndex by remember { mutableIntStateOf(0) }
        var showSettings by remember { mutableStateOf(false) }
        var showChapters by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
            if (picked != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        picked,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                NovelLibraryStore.add(context, picked)
                val isPdf = context.contentResolver.getType(picked) == "application/pdf" ||
                    displayName(context, picked).endsWith(".pdf", ignoreCase = true)
                if (isPdf) {
                    navigator.push(PdfReaderScreen(picked.toString()))
                } else {
                    prefs.edit().putString(KEY_LAST_URI, picked.toString()).apply()
                    uri = picked
                }
            }
        }

        LaunchedEffect(uri) {
            val target = uri ?: return@LaunchedEffect
            loading = true
            error = null
            val result = withContext(Dispatchers.IO) { runCatching { loadBook(context, target) } }
            val loaded = result.getOrNull()
            if (loaded != null) {
                val posKey = positionKey(target)
                book = loaded
                chapterIndex = prefs.getInt("${posKey}_ch", 0).coerceIn(0, loaded.chapters.lastIndex)
                startIndex = prefs.getInt("${posKey}_idx", 0)
            } else {
                book = null
                error = result.exceptionOrNull()?.message ?: "Could not open this file"
            }
            loading = false
        }

        val background: Color
        val textColor: Color
        when (theme) {
            1 -> {
                background = Color(0xFFFFFFFF)
                textColor = Color(0xFF1A1A1A)
            }
            2 -> {
                background = Color(0xFFF4ECD8)
                textColor = Color(0xFF5B4636)
            }
            3 -> {
                background = Color(0xFF121212)
                textColor = Color(0xFFE0E0E0)
            }
            else -> {
                background = MaterialTheme.colorScheme.background
                textColor = MaterialTheme.colorScheme.onBackground
            }
        }

        fun setTheme(value: Int) {
            theme = value
            prefs.edit().putInt(KEY_THEME, value).apply()
        }

        fun goToChapter(index: Int) {
            chapterIndex = index
            startIndex = 0
        }

        Scaffold(
            containerColor = background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = book?.title ?: "Novel reader",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (book != null) {
                            TextButton(onClick = { showSettings = true }) { Text("Aa") }
                        }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open") }
                    },
                )
            },
            bottomBar = {
                val current = book
                if (current != null) {
                    BottomAppBar(containerColor = background, contentColor = textColor) {
                        TextButton(
                            enabled = chapterIndex > 0,
                            onClick = { goToChapter(chapterIndex - 1) },
                        ) { Text("Prev") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showChapters = true }) {
                            Text("${chapterIndex + 1} / ${current.chapters.size}")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = chapterIndex < current.chapters.lastIndex,
                            onClick = { goToChapter(chapterIndex + 1) },
                        ) { Text("Next") }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(background),
            ) {
                val current = book
                val message = error
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    current != null -> {
                        val chapter = current.chapters[chapterIndex.coerceIn(0, current.chapters.lastIndex)]
                        key(uri, chapterIndex) {
                            ChapterText(
                                paragraphs = chapter.paragraphs,
                                fontSize = fontSize,
                                color = textColor,
                                initialIndex = startIndex,
                                onPosition = { index ->
                                    val target = uri
                                    if (target != null) {
                                        val posKey = positionKey(target)
                                        prefs.edit()
                                            .putInt("${posKey}_ch", chapterIndex)
                                            .putInt("${posKey}_idx", index)
                                            .apply()
                                    }
                                },
                            )
                        }
                    }
                    else -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = message ?: "Open a .txt or .epub file to start reading.",
                            color = textColor,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open file") }
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
                title = { Text("Reading settings") },
                text = {
                    Column {
                        Text("Font size: ${fontSize.toInt()}")
                        Slider(
                            value = fontSize,
                            onValueChange = {
                                fontSize = it
                                prefs.edit().putFloat(KEY_FONT_SIZE, it).apply()
                            },
                            valueRange = 12f..32f,
                        )
                        THEME_NAMES.forEachIndexed { index, name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { setTheme(index) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = theme == index, onClick = { setTheme(index) })
                                Text(name)
                            }
                        }
                    }
                },
            )
        }

        val dialogBook = book
        if (showChapters && dialogBook != null) {
            AlertDialog(
                onDismissRequest = { showChapters = false },
                confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
                title = { Text("Chapters") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(dialogBook.chapters) { index, chapter ->
                            Text(
                                text = "${index + 1}. ${chapter.title}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        goToChapter(index)
                                        showChapters = false
                                    }
                                    .padding(vertical = 12.dp),
                                color = if (index == chapterIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChapterText(
    paragraphs: List<String>,
    fontSize: Float,
    color: Color,
    initialIndex: Int,
    onPosition: (Int) -> Unit,
) {
    val safeIndex = initialIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onPosition(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(paragraphs) { paragraph ->
            val imagePath = imagePathOf(paragraph)
            if (imagePath != null) {
                ChapterImage(imagePath)
            } else {
                Text(
                    text = paragraph,
                    color = color,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6f).sp,
                )
            }
        }
    }
}

@Composable
private fun ChapterImage(path: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeScaled(path, 1080) }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

private fun decodeScaled(path: String, maxWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options()
    bounds.inJustDecodeBounds = true
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) {
        return null
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) {
        sample *= 2
    }
    val options = BitmapFactory.Options()
    options.inSampleSize = sample
    return BitmapFactory.decodeFile(path, options)
}

private fun imagePathOf(paragraph: String): String? {
    return if (paragraph.startsWith(IMAGE_START) && paragraph.endsWith(IMAGE_END)) {
        paragraph.substring(IMAGE_START.length, paragraph.length - IMAGE_END.length)
    } else {
        null
    }
}

private fun positionKey(uri: Uri): String = uri.toString().hashCode().toString()

private fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name ?: uri.lastPathSegment ?: "Novel"
}

private fun loadBook(context: Context, uri: Uri): NovelBook {
    val name = displayName(context, uri)
    val isEpub = name.endsWith(".epub", ignoreCase = true) ||
        context.contentResolver.getType(uri) == "application/epub+zip"
    return if (isEpub) parseEpub(context, uri, name) else parseText(context, uri, name)
}

private fun parseText(context: Context, uri: Uri, name: String): NovelBook {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Cannot open this file")
    val paragraphs = toParagraphs(String(bytes, Charsets.UTF_8))
    if (paragraphs.isEmpty()) error("The file is empty")
    val chapters = paragraphs.chunked(400).mapIndexed { index, chunk ->
        NovelChapter("Part ${index + 1}", chunk)
    }
    return NovelBook(name.substringBeforeLast('.'), chapters)
}

private fun parseEpub(context: Context, uri: Uri, name: String): NovelBook {
    val files = HashMap<String, ByteArray>()
    val imageDir = File(context.cacheDir, "novel_img/" + positionKey(uri).replace("-", "n"))
    imageDir.mkdirs()
    val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open this file")
    ZipInputStream(stream).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val entryName = entry.name
            if (!entry.isDirectory && isTextEntry(entryName)) {
                files[entryName] = zip.readBytes()
            } else if (!entry.isDirectory && isImageEntry(entryName)) {
                val target = imageFile(imageDir, entryName)
                if (!target.exists() || target.length() == 0L) {
                    runCatching {
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }.onFailure { target.delete() }
                }
            }
            entry = zip.nextEntry
        }
    }

    val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        ?: error("This EPUB is not valid")
    val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
        ?: error("This EPUB is not valid")
    val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: error("This EPUB is not valid")
    val baseDir = opfPath.substringBeforeLast('/', "")

    val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(opf)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: name.substringBeforeLast('.')

    val manifest = HashMap<String, String>()
    Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
        val id = tagAttr(match.value, "id")
        val href = tagAttr(match.value, "href")
        if (id != null && href != null) {
            manifest[id] = href
        }
    }
    val spine = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
        .mapNotNull { tagAttr(it.value, "idref") }
        .toList()

    val chapters = ArrayList<NovelChapter>()
    for (idref in spine) {
        val href = manifest[idref] ?: continue
        val chapterPath = resolvePath(baseDir, href)
        val html = files[chapterPath]?.toString(Charsets.UTF_8) ?: continue
        val paragraphs = htmlToParagraphs(html, chapterPath.substringBeforeLast('/', ""), imageDir)
        if (paragraphs.isEmpty()) continue
        val first = paragraphs.first()
        val chapterTitle = if (first.length <= 60 && imagePathOf(first) == null) {
            first
        } else {
            "Chapter ${chapters.size + 1}"
        }
        chapters.add(NovelChapter(chapterTitle, paragraphs))
    }
    if (chapters.isEmpty()) error("No readable content found in this EPUB")
    return NovelBook(title, chapters)
}

private fun isTextEntry(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
        lower.endsWith(".xml") || lower.endsWith(".opf")
}

private fun isImageEntry(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
        lower.endsWith(".gif") || lower.endsWith(".webp")
}

private fun imageFile(dir: File, zipPath: String): File {
    return File(dir, zipPath.replace(Regex("[^A-Za-z0-9._-]"), "_"))
}

private fun tagAttr(tag: String, attr: String): String? {
    return Regex("\\b$attr\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        .find(tag)?.groupValues?.get(1)
}

private fun resolvePath(baseDir: String, href: String): String {
    val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
    val full = if (baseDir.isEmpty()) clean else "$baseDir/$clean"
    val parts = ArrayList<String>()
    for (segment in full.split('/')) {
        if (segment.isEmpty() || segment == ".") {
            continue
        }
        if (segment == "..") {
            if (parts.isNotEmpty()) {
                parts.removeAt(parts.lastIndex)
            }
        } else {
            parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

private fun htmlToParagraphs(html: String, chapterDir: String, imageDir: File): List<String> {
    val cleaned = html
        .replace(Regex("(?is)<head.*?</head>"), "")
        .replace(Regex("(?is)<script.*?</script>"), "")
        .replace(Regex("(?is)<style.*?</style>"), "")
    val withImages = Regex("<(img|image)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(cleaned) { match ->
        val tag = match.value
        val src = tagAttr(tag, "src") ?: tagAttr(tag, "href")
        val file = if (src == null) null else imageFile(imageDir, resolvePath(chapterDir, src))
        if (file != null && file.exists()) {
            "<p>" + IMAGE_START + file.absolutePath + IMAGE_END + "</p>"
        } else {
            ""
        }
    }
    val text = Html.fromHtml(withImages, Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFFFC", "")
    return toParagraphs(text)
}

private fun toParagraphs(text: String): List<String> {
    return text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
