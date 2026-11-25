package eu.kanade.tachiyomi.ui.novel

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NovelSourcesScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var sources by remember { mutableStateOf(NovelSourceStore.load(context)) }
        var showAdd by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var pendingRemove by remember { mutableStateOf<NovelSourceDef?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Novel sources") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { navigator.push(NovelShelfScreen()) }) { Text("Library") }
                        TextButton(onClick = { showAdd = true }) { Text("Add source") }
                    },
                )
            },
        ) { padding ->
            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    Text(
                        text = "No sources yet. Tap \"Add source\" and paste a source JSON or a link to one.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    items(sources, key = { it.id }) { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navigator.push(NovelSearchScreen(source.id)) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = source.lang.uppercase() + " • " + source.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { pendingRemove = source }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (showAdd) {
            AlertDialog(
                onDismissRequest = { if (!busy) showAdd = false },
                title = { Text("Add source") },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Source JSON or link to JSON") },
                        minLines = 4,
                        maxLines = 10,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && input.isNotBlank(),
                        onClick = {
                            val text = input.trim()
                            scope.launch {
                                busy = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val raw = if (text.startsWith("http://") || text.startsWith("https://")) {
                                            NovelHttp.fetchText(text)
                                        } else {
                                            text
                                        }
                                        NovelSourceStore.addAny(context, raw)
                                    }
                                }
                                busy = false
                                val added = result.getOrNull()
                                if (added != null) {
                                    sources = NovelSourceStore.load(context)
                                    input = ""
                                    showAdd = false
                                    Toast.makeText(context, "Added " + added.size + " source(s)", Toast.LENGTH_SHORT).show()
                                } else {
                                    val reason = result.exceptionOrNull()?.message ?: "unknown error"
                                    Toast.makeText(context, "Invalid source: $reason", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    ) { Text(if (busy) "Adding..." else "Add") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { showAdd = false }) { Text("Cancel") }
                },
            )
        }

        val target = pendingRemove
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("Remove source?") },
                text = { Text("\"${target.name}\" will be removed.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            NovelSourceStore.remove(context, target.id)
                            sources = NovelSourceStore.load(context)
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
