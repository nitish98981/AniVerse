package eu.kanade.tachiyomi.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

class NovelSearchScreen(private val sourceId: String) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val source = remember { NovelSourceStore.load(context).firstOrNull { it.id == sourceId } }
        var query by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var results by remember { mutableStateOf<List<NovelItem>>(emptyList()) }

        val runSearch: () -> Unit = {
            if (source != null && query.isNotBlank() && !loading) {
                scope.launch {
                    loading = true
                    message = null
                    val r = withContext(Dispatchers.IO) {
                        runCatching { NovelEngine.search(source, query.trim()) }
                    }
                    loading = false
                    results = r.getOrNull() ?: emptyList()
                    message = when {
                        r.isFailure -> "Error: " + (r.exceptionOrNull()?.message ?: "unknown")
                        results.isEmpty() -> "0 results. Nothing matched, or the selectors do not fit this site."
                        else -> null
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(source?.name ?: "Source not found") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search") },
                    )
                    TextButton(enabled = !loading && query.isNotBlank(), onClick = runSearch) {
                        Text(if (loading) "..." else "Search")
                    }
                }
                message?.let { Text(it, modifier = Modifier.padding(16.dp)) }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.url }) { item ->
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navigator.push(NovelDetailScreen(sourceId, item.url, item.title))
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

class NovelDetailScreen(
    private val sourceId: String,
    private val novelUrl: String,
    private val novelTitle: String,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { NovelSourceStore.load(context).firstOrNull { it.id == sourceId } }
        var detail by remember { mutableStateOf(NovelCache.details[novelUrl]) }
        var error by remember { mutableStateOf<String?>(null) }
        val last = remember { NovelProgress.get(context, novelUrl) }

        LaunchedEffect(Unit) {
            if (detail == null && source != null) {
                val r = withContext(Dispatchers.IO) {
                    runCatching { NovelEngine.detail(source, novelUrl) }
                }
                val d = r.getOrNull()
                if (d != null) NovelCache.details[novelUrl] = d
                detail = d
                error = r.exceptionOrNull()?.message
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(novelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            val d = detail
            if (d == null) {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    Text(
                        text = if (error != null) "Error: $error" else "Loading...",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(d.title.ifEmpty { novelTitle }, style = MaterialTheme.typography.titleLarge)
if (d.cover.isNotEmpty()) {
    AsyncImage(
        model = d.cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.padding(top = 8.dp).size(width = 100.dp, height = 150.dp),
    )
}
var inShelf by remember { mutableStateOf(NovelShelfStore.contains(context, novelUrl)) }
TextButton(
    onClick = {
        if (inShelf) {
            NovelShelfStore.remove(context, novelUrl)
        } else {
            NovelShelfStore.add(
                context,
                NovelShelfItem(sourceId, novelUrl, d.title.ifEmpty { novelTitle }, d.cover),
            )
        }
        inShelf = !inShelf
    },
) { Text(if (inShelf) "Remove from library" else "Add to library") }
                            if (d.description.isNotEmpty()) {
                                Text(
                                    text = d.description,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (last in d.chapters.indices) {
                                TextButton(
                                    onClick = { navigator.push(NovelWebReaderScreen(sourceId, novelUrl, last)) },
                                ) { Text("Continue: " + d.chapters[last].title) }
                            }
                            Text(
                                text = "${d.chapters.size} chapters",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                    itemsIndexed(d.chapters) { index, ch ->
                        Text(
                            text = ch.title,
                            fontWeight = if (index == last) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navigator.push(NovelWebReaderScreen(sourceId, novelUrl, index))
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

class NovelWebReaderScreen(
    private val sourceId: String,
    private val novelUrl: String,
    private val startIndex: Int,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { NovelSourceStore.load(context).firstOrNull { it.id == sourceId } }
        val chapters = remember { NovelCache.details[novelUrl]?.chapters ?: emptyList() }

        if (source == null || chapters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Chapters not loaded. Go back and open the novel again.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        var idx by rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, chapters.size - 1)) }
        var fontSize by rememberSaveable { mutableIntStateOf(18) }
        var theme by rememberSaveable { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
        val scroll = rememberScrollState()

        LaunchedEffect(idx) {
            loading = true
            error = null
            NovelProgress.set(context, novelUrl, idx)
            val r = withContext(Dispatchers.IO) {
                runCatching { NovelEngine.content(source, chapters[idx].url) }
            }
            paragraphs = r.getOrNull() ?: emptyList()
            error = r.exceptionOrNull()?.message
            loading = false
            scroll.scrollTo(0)
        }

        val bg = when (theme) {
            1 -> Color(0xFFF4ECD8)
            2 -> Color(0xFF121212)
            else -> Color(0xFFFFFFFF)
        }
        val fg = when (theme) {
            1 -> Color(0xFF5B4636)
            2 -> Color(0xFFE0E0E0)
            else -> Color(0xFF111111)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(chapters[idx].title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(enabled = idx > 0, onClick = { idx = idx - 1 }) { Text("Prev") }
                    TextButton(onClick = { if (fontSize > 12) fontSize = fontSize - 2 }) { Text("A-") }
                    TextButton(onClick = { if (fontSize < 32) fontSize = fontSize + 2 }) { Text("A+") }
                    TextButton(onClick = { theme = (theme + 1) % 3 }) { Text("Theme") }
                    TextButton(
                        enabled = idx < chapters.size - 1,
                        onClick = { idx = idx + 1 },
                    ) { Text("Next") }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize().background(bg)) {
                when {
                    loading -> Text(
                        text = "Loading...",
                        color = fg,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    error != null -> Text(
                        text = "Error: $error",
                        color = fg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    paragraphs.isEmpty() -> Text(
                        text = "Empty chapter. Check the content selector.",
                        color = fg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        paragraphs.forEach { p ->
                            Text(
                                text = p,
                                color = fg,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.6f).sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
