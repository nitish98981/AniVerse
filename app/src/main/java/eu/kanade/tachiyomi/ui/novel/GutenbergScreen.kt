package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class GutenbergBook(
    val id: Int,
    val title: String,
    val author: String,
    val epubUrl: String?,
)

class GutenbergScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var query by remember { mutableStateOf("") }
        var language by remember { mutableStateOf("") }
        var books by remember { mutableStateOf<List<GutenbergBook>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(setOf<Int>()) }
        var added by remember { mutableStateOf(setOf<Int>()) }

        val libraryUris = remember(added) { NovelLibraryStore.load(context).map { it.uri } }

        fun search(text: String, lang: String) {
            scope.launch {
                loading = true
                error = null
                val params = ArrayList<String>()
                if (text.isNotBlank()) {
                    params.add("search=" + URLEncoder.encode(text.trim(), "UTF-8"))
                }
                if (lang.isNotEmpty()) {
                    params.add("languages=$lang")
                }
                val queryString = if (params.isEmpty()) "" else "?" + params.joinToString("&")
                val url = "https://gutendex.com/books$queryString"
                val result = withContext(Dispatchers.IO) { runCatching { parseBooks(httpGet(url)) } }
                val found = result.getOrNull()
                if (found != null) {
                    books = found
                    if (found.isEmpty()) {
                        error = "No books found"
                    }
                } else {
                    books = emptyList()
                    error = "Could not load books. Check your internet and try again."
                }
                loading = false
            }
        }

        fun download(book: GutenbergBook) {
            scope.launch {
                busy = busy + book.id
                val result = withContext(Dispatchers.IO) { runCatching { downloadEpub(context, book) } }
                busy = busy - book.id
                val file = result.getOrNull()
                if (file != null) {
                    NovelLibraryStore.addFile(context, file, book.title, "epub")
                    added = added + book.id
                    Toast.makeText(context, "Added to your library", Toast.LENGTH_SHORT).show()
                } else {
                    val message = result.exceptionOrNull()?.message ?: "Download failed"
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        LaunchedEffect(Unit) { search("", "") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Get free books") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Title or author") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(query, language) }),
                    )
                    Button(onClick = { search(query, language) }) { Text("Search") }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LanguageChoice(
                        label = "All",
                        selected = language.isEmpty(),
                        onClick = {
                            language = ""
                            search(query, "")
                        },
                    )
                    LanguageChoice(
                        label = "English",
                        selected = language == "en",
                        onClick = {
                            language = "en"
                            search(query, "en")
                        },
                    )
                    LanguageChoice(
                        label = "Hindi",
                        selected = language == "hi",
                        onClick = {
                            language = "hi"
                            search(query, "hi")
                        },
                    )
                }

                val message = error
                when {
                    loading -> Box(modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    message != null -> Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = message,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(books, key = { it.id }) { book ->
                            val inLibrary = libraryUris.any { it.endsWith("/pg${book.id}.epub") }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = book.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (book.author.isNotEmpty()) {
                                        Text(
                                            text = book.author,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                when {
                                    busy.contains(book.id) -> CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .size(24.dp),
                                    )
                                    inLibrary -> Text(
                                        text = "In library",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    else -> TextButton(onClick = { download(book) }) { Text("Download") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 30000
    connection.setRequestProperty("User-Agent", "AniVerse/1.0 (book reader)")
    try {
        if (connection.responseCode !in 200..299) {
            error("Server error ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun parseBooks(json: String): List<GutenbergBook> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    val books = ArrayList<GutenbergBook>()
    for (index in 0 until results.length()) {
        val item = results.getJSONObject(index)
        val authors = item.optJSONArray("authors")
        val author = if (authors != null && authors.length() > 0) {
            authors.getJSONObject(0).optString("name", "")
        } else {
            ""
        }
        var epub: String? = null
        val formats = item.optJSONObject("formats")
        if (formats != null) {
            val keys = formats.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("application/epub+zip")) {
                    epub = formats.optString(key)
                    break
                }
            }
        }
        if (epub != null) {
            books.add(GutenbergBook(item.getInt("id"), item.optString("title", "Untitled"), author, epub))
        }
    }
    return books
}

private fun downloadEpub(context: Context, book: GutenbergBook): File {
    val dir = File(context.filesDir, "gutenberg")
    dir.mkdirs()
    val target = File(dir, "pg${book.id}.epub")
    if (target.exists() && target.length() > 0) {
        return target
    }
    val url = book.epubUrl ?: error("No EPUB available for this book")
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 15000
    connection.readTimeout = 60000
    connection.setRequestProperty("User-Agent", "AniVerse/1.0 (book reader)")
    val temp = File(dir, "pg${book.id}.part")
    try {
        if (connection.responseCode !in 200..299) {
            error("Download failed (${connection.responseCode})")
        }
        connection.inputStream.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        connection.disconnect()
    }
    if (!temp.renameTo(target)) {
        error("Could not save the file")
    }
    return target
}
