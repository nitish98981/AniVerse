package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfReaderScreen(private val uriString: String) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val uri = remember { Uri.parse(uriString) }
        val title = remember { pdfTitle(context, uri) }

        var holder by remember { mutableStateOf<PdfHolder?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(uri) {
            val result = withContext(Dispatchers.IO) { runCatching { PdfHolder.open(context, uri) } }
            holder = result.getOrNull()
            if (holder == null) {
                error = result.exceptionOrNull()?.message ?: "Could not open this PDF"
            }
        }

        DisposableEffect(holder) {
            val opened = holder
            onDispose { opened?.close() }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                val current = holder
                val message = error
                when {
                    current != null -> PdfPages(current, uriString)
                    message != null -> Text(
                        text = message,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                    else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun PdfPages(holder: PdfHolder, uriString: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aniverse_pdf", Context.MODE_PRIVATE) }
    val posKey = remember(uriString) { "pos_" + uriString.hashCode() }
    val count = holder.pageCount
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = prefs.getInt(posKey, 0).coerceIn(0, (count - 1).coerceAtLeast(0)),
    )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect {
            prefs.edit().putInt(posKey, it).apply()
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val renderWidth = minOf(constraints.maxWidth, 1080)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count) { index ->
                PdfPage(holder, index, renderWidth)
            }
        }
        Text(
            text = "${listState.firstVisibleItemIndex + 1} / $count",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PdfPage(holder: PdfHolder, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, holder, index, widthPx) {
        value = runCatching { holder.render(index, widthPx) }.getOrNull()
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(holder.firstAspect),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

private class PdfHolder(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val firstAspect: Float,
) {
    val pageCount: Int = renderer.pageCount

    private val lock = Mutex()
    private val cache = LruCache<Int, Bitmap>(6)

    suspend fun render(index: Int, targetWidth: Int): Bitmap {
        cache.get(index)?.let { return it }
        return lock.withLock {
            cache.get(index) ?: withContext(Dispatchers.IO) {
                renderPage(index, targetWidth)
            }.also { cache.put(index, it) }
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private fun renderPage(index: Int, targetWidth: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val height = (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfHolder {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Cannot open this file")
            try {
                val renderer = PdfRenderer(descriptor)
                if (renderer.pageCount == 0) {
                    renderer.close()
                    error("This PDF has no pages")
                }
                val first = renderer.openPage(0)
                val aspect = first.width.toFloat() / first.height
                first.close()
                return PdfHolder(descriptor, renderer, aspect)
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}

private fun pdfTitle(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return (name ?: uri.lastPathSegment ?: "PDF").substringBeforeLast('.')
}
