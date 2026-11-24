package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen

class NovelLibraryScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var entries by remember { mutableStateOf(NovelLibraryStore.load(context)) }
        var pendingRemove by remember { mutableStateOf<NovelEntry?>(null) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
            if (picked != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        picked,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                NovelLibraryStore.add(context, picked)
                entries = NovelLibraryStore.load(context)
                val added = entries.firstOrNull { it.uri == picked.toString() }
                if (added != null) {
                    openBook(navigator, context, added)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Novel library") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                                                TextButton(onClick = { navigator.push(GutenbergScreen()) }) { Text("Get books") }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add file") }
                    },
                )
            },
        ) { padding ->
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "Your library is empty. Add an EPUB, TXT or PDF file to start reading.",
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add file") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    items(entries, key = { it.uri }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openBook(navigator, context, entry) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subtitleOf(context, entry),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { pendingRemove = entry }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        val target = pendingRemove
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("Remove from library?") },
                text = { Text("\"${target.title}\" will be removed from this list. The file itself is not deleted.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            NovelLibraryStore.remove(context, target.uri)
                            entries = NovelLibraryStore.load(context)
                            pendingRemove = null
                        },
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
                },
            )
        }
    }
}

private fun openBook(navigator: Navigator, context: Context, entry: NovelEntry) {
    NovelLibraryStore.touch(context, entry.uri)
    if (entry.type == "pdf") {
        navigator.push(PdfReaderScreen(entry.uri))
    } else {
        context.getSharedPreferences("aniverse_novel", Context.MODE_PRIVATE)
            .edit()
            .putString("last_uri", entry.uri)
            .apply()
        navigator.push(NovelReaderScreen())
    }
}

private fun subtitleOf(context: Context, entry: NovelEntry): String {
    val progress = if (entry.type == "pdf") {
        val page = context.getSharedPreferences("aniverse_pdf", Context.MODE_PRIVATE)
            .getInt("pos_" + entry.uri.hashCode(), -1)
        if (page >= 0) "Page ${page + 1}" else null
    } else {
        val chapter = context.getSharedPreferences("aniverse_novel", Context.MODE_PRIVATE)
            .getInt(entry.uri.hashCode().toString() + "_ch", -1)
        if (chapter >= 0) "Chapter ${chapter + 1}" else null
    }
    val opened = DateUtils.getRelativeTimeSpanString(
        entry.lastOpened,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return listOfNotNull(entry.type.uppercase(), progress, "Opened $opened").joinToString(" • ")
}
