package io.pdfoundry.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the page editor is doing. */
sealed interface EditMode {
    data class Ink(val highlighter: Boolean) : EditMode
    /** An image (signature or typed text) being positioned before it is placed. */
    data class Place(val image: Bitmap, val label: String) : EditMode
}

/** State and editing actions for one open document. */
class ReaderState(private val context: Context, val file: File, private val scope: CoroutineScope) {
    var source by mutableStateOf<PdfSource?>(null)
        private set
    /** Bumped after every change so pages re-render. */
    var version by mutableIntStateOf(0)
        private set
    var locked by mutableStateOf(false)
        private set
    var unreadable by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Password-protected files are shown from an unlocked copy and stay read-only, so their protection is never removed. */
    var readOnly by mutableStateOf(false)
        private set

    private var viewFile = file
    private val undoFolder = File(context.cacheDir, "undo/${file.nameWithoutExtension.hashCode()}").apply { deleteRecursively(); mkdirs() }
    private val undoStack = ArrayDeque<File>()

    val pageCount: Int get() = source?.pageCount ?: 0
    val sizes: List<Size> get() = source?.sizes ?: emptyList()

    fun open() {
        scope.launch { reopen() }
    }

    private suspend fun reopen() {
        val old = source
        val opened = withContext(Dispatchers.IO) {
            try {
                PdfSource(viewFile)
            } catch (e: SecurityException) {
                locked = true
                null
            } catch (e: Exception) {
                unreadable = true
                null
            }
        }
        source = opened
        version++
        withContext(Dispatchers.IO) { old?.close() }
    }

    fun unlock(password: String, onWrong: () -> Unit) {
        scope.launch {
            busy = true
            val copy = File(context.cacheDir, "unlocked-${file.nameWithoutExtension.hashCode()}.pdf")
            val ok = withContext(Dispatchers.IO) { runCatching { PdfEngine.unlock(file, password, copy) }.getOrDefault(false) }
            busy = false
            if (ok) {
                viewFile = copy
                locked = false
                readOnly = true
                reopen()
            } else {
                onWrong()
            }
        }
    }

    /** Runs one change to the file. A copy is kept first so the change can be undone. */
    fun edit(change: (File) -> Unit) {
        if (readOnly || busy) return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = File(undoFolder, "${System.nanoTime()}.pdf")
                    file.copyTo(snapshot, overwrite = true)
                    try {
                        change(file)
                    } catch (e: Exception) {
                        snapshot.delete()
                        throw e
                    }
                    undoStack.addLast(snapshot)
                    while (undoStack.size > 10) undoStack.removeFirst().delete()
                }
                canUndo = true
                reopen()
            } catch (e: Exception) {
                message = e.message ?: "The change could not be saved."
            } finally {
                busy = false
            }
        }
    }

    fun undo() {
        if (busy) return
        val snapshot = undoStack.removeLastOrNull() ?: return
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                snapshot.copyTo(file, overwrite = true)
                snapshot.delete()
            }
            canUndo = undoStack.isNotEmpty()
            reopen()
            busy = false
        }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        runCatching { PdfEngine.search(viewFile, query) }.getOrDefault(emptyList())
    }

    fun close() {
        val old = source
        source = null
        Thread {
            old?.close()
            undoFolder.deleteRecursively()
            if (viewFile != file) viewFile.delete()
        }.start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(file) { ReaderState(context.applicationContext, file, scope) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var mode by remember { mutableStateOf<EditMode?>(null) }
    var editPage by remember { mutableIntStateOf(0) }
    var showPages by remember { mutableStateOf(false) }
    var showSignatures by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    DisposableEffect(state) {
        state.open()
        onDispose { state.close() }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            state.message = null
        }
    }

    // The page most of the screen is showing: edits go to this one.
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - middle) }?.index ?: 0
        }
    }

    fun begin(newMode: EditMode) {
        editPage = currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        mode = newMode
    }

    val editing = mode
    if (editing != null && state.source != null) {
        BackHandler { mode = null }
        PageEditor(
            state = state,
            pageIndex = editPage,
            mode = editing,
            onCancel = { mode = null },
            onInk = { strokes: List<InkStroke> ->
                mode = null
                if (strokes.isNotEmpty()) state.edit { PdfEngine.ink(it, editPage, strokes) }
            },
            onPlace = { image: Bitmap, rect: RectF ->
                mode = null
                state.edit { PdfEngine.stamp(it, editPage, image, rect) }
            }
        )
        return
    }
    if (showPages && state.source != null) {
        BackHandler { showPages = false }
        PagesScreen(state = state, onDone = { showPages = false })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.source != null) {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search in document") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!state.readOnly) {
                                    DropdownMenuItem(text = { Text("Organize pages") }, leadingIcon = { Icon(Icons.Filled.GridView, null) }, onClick = { menu = false; showPages = true })
                                }
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; context.sharePdfs(listOf(file)) })
                                DropdownMenuItem(text = { Text("Print") }, leadingIcon = { Icon(Icons.Filled.Print, null) }, onClick = { menu = false; context.printPdf(file) })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.source != null) {
                if (state.readOnly) {
                    Surface(tonalElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("Password-protected documents are read-only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    BottomAppBar {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ToolButton(Icons.Outlined.BorderColor, "Highlight") { begin(EditMode.Ink(highlighter = true)) }
                            ToolButton(Icons.Filled.TextFields, "Text") { showText = true }
                            ToolButton(Icons.Filled.Draw, "Draw") { begin(EditMode.Ink(highlighter = false)) }
                            ToolButton(SignatureIcon, "Sign") { showSignatures = true }
                            ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = state.canUndo && !state.busy) { state.undo() }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val source = state.source
            when {
                state.locked -> LockedView(busy = state.busy) { password, onWrong -> state.unlock(password, onWrong) }
                state.unreadable -> Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("Unable to open", style = MaterialTheme.typography.titleLarge)
                    Text("This file isn't a readable PDF.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                source == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    PageList(state, source, listState)
                    Surface(shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 2.dp, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text("${currentPage + 1} of ${state.pageCount}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    if (showText) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showText = false },
            title = { Text("Add text") },
            text = {
                Column {
                    Text("Type the text, then drag it into place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Text") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    showText = false
                    begin(EditMode.Place(PdfEngine.textBitmap(text.trim()), "text"))
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showText = false }) { Text("Cancel") } }
        )
    }

    if (showSignatures) {
        ModalBottomSheet(onDismissRequest = { showSignatures = false }) {
            SignaturePicker(onPick = { image ->
                showSignatures = false
                begin(EditMode.Place(image, "signature"))
            })
        }
    }

    if (showSearch) {
        SearchDialog(state, onDismiss = { showSearch = false }) { page ->
            showSearch = false
            scope.launch { listState.scrollToItem(page) }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = label, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** All pages in a column. Pinch to zoom; when zoomed, the page scrolls sideways too. */
@Composable
private fun PageList(state: ReaderState, source: PdfSource, listState: androidx.compose.foundation.lazy.LazyListState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val baseWidthPx = constraints.maxWidth
        val baseWidth = maxWidth
        var zoom by remember { mutableFloatStateOf(1f) }
        // Pages are re-rendered at the new size a moment after the pinch settles, so text stays sharp.
        var renderZoom by remember { mutableFloatStateOf(1f) }
        LaunchedEffect(zoom) {
            delay(250)
            renderZoom = zoom
        }
        val sizes = state.sizes
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                if (change != 1f) zoom = (zoom * change).coerceIn(1f, 4f)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .horizontalScroll(rememberScrollState())
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(baseWidth * zoom).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(source.pageCount, key = { it }) { index ->
                    val size = sizes.getOrNull(index) ?: Size(612, 792)
                    val widthPx = (baseWidthPx * renderZoom).toInt().coerceIn(1, 3000)
                    val bitmap by produceState<Bitmap?>(null, index, widthPx, state.version) { value = source.render(index, widthPx) }
                    Box(Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height).background(Color.White)) {
                        bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedView(busy: Boolean, onUnlock: (String, () -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text("This document is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Enter its password to read it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; wrong = false },
            label = { Text("Password") },
            singleLine = true,
            isError = wrong,
            supportingText = { if (wrong) Text("That password didn't work. Try again.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(10.dp))
        Button(enabled = password.isNotEmpty() && !busy, onClick = { onUnlock(password) { wrong = true; password = "" } }) { Text("Unlock") }
    }
}

@Composable
private fun SearchDialog(state: ReaderState, onDismiss: () -> Unit, onOpenPage: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val hits by produceState<List<SearchHit>?>(null, query) {
        value = null
        if (query.trim().length < 2) return@produceState
        delay(350)
        searching = true
        value = state.search(query)
        searching = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search in document") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Word or phrase") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val results = hits
                when {
                    searching -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                    results == null -> Text("Type at least two letters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    results.isEmpty() -> Text("No matches for “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.height(320.dp)) {
                        items(results.size) { index ->
                            val hit = results[index]
                            Column(Modifier.fillMaxWidth().clickable { onOpenPage(hit.page) }.padding(vertical = 8.dp)) {
                                Text("Page ${hit.page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
