package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import org.json.JSONArray
import org.json.JSONObject

data class NovelShelfItem(
    val sourceId: String,
    val url: String,
    val title: String,
    val cover: String,
)

object NovelShelfStore {
    private const val PREFS = "aniverse_novel_shelf"
    private const val KEY = "items"

    fun load(context: Context): List<NovelShelfItem> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                NovelShelfItem(
                    o.getString("sourceId"),
                    o.getString("url"),
                    o.optString("title"),
                    o.optString("cover"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun contains(context: Context, url: String): Boolean = load(context).any { it.url == url }

    fun add(context: Context, item: NovelShelfItem) {
        save(context, listOf(item) + load(context).filter { it.url != item.url })
    }

    fun remove(context: Context, url: String) {
        save(context, load(context).filter { it.url != url })
    }

    private fun save(context: Context, list: List<NovelShelfItem>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("sourceId", it.sourceId)
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("cover", it.cover),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

class NovelShelfScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var shelf by remember { mutableStateOf(NovelShelfStore.load(context)) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Novel library") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            if (shelf.isEmpty()) {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    Text(
                        text = "Library is empty. Open a novel from a source and tap \"Add to library\".",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                    items(shelf, key = { it.url }) { item ->
                        val last = NovelProgress.get(context, item.url)
                        val total = NovelCache.details[item.url]?.chapters?.size
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navigator.push(NovelDetailScreen(item.sourceId, item.url, item.title))
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.cover.isNotEmpty()) {
                                AsyncImage(
                                    model = item.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 56.dp, height = 84.dp),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 84.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    text = item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (last >= 0) {
                                    val suffix = if (total != null) " / $total" else ""
                                    Text(
                                        text = "Last read: ch ${last + 1}$suffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    NovelShelfStore.remove(context, item.url)
                                    shelf = NovelShelfStore.load(context)
                                },
                            ) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
