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
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Restore
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
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.SearchHit
import io.pdfoundry.app.data.sameContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import java.io.File

/** What the page editor is doing. */
sealed interface EditMode {
    data class Ink(val highlighter: Boolean) : EditMode
    /** An image (signature or typed text) being positioned before it is placed. */
    data class Place(val image: Bitmap, val label: String) : EditMode
    /** Words are being selected for a highlight, underline or strikethrough. */
    data class TextMark(val mark: io.pdfoundry.app.data.TextMark, val argb: Int) : EditMode
}

/** Which set of tools the reader's bottom bar shows. The reader always opens in [None], the reading bar. */
enum class ToolMode { None, Markup, FillSign }

/** State and editing actions for one open document. */
class ReaderState(private val context: Context, val file: File, private val scope: CoroutineScope, private val store: DocumentStore = context.app.store) {
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
    /** Why the document is read-only, in words. */
    var notice by mutableStateOf("Password-protected documents are read-only.")
        private set
    /** True while the file differs from how it was when it was opened. */
    var canRevert by mutableStateOf(false)
        private set

    /** The bar's mode. Using a tool and finishing or cancelling it returns to this mode, not to reading. */
    var toolMode by mutableStateOf(ToolMode.None)
    /** The tool in use on [editPage], if any. */
    var editMode by mutableStateOf<EditMode?>(null)
        private set
    var editPage by mutableIntStateOf(0)
        private set

    private var viewFile = file
    private val undoFolder = File(context.cacheDir, "undo/${file.path.hashCode()}").apply { deleteRecursively(); mkdirs() }
    private val undoStack = ArrayDeque<File>()
    /** The file exactly as it was when opened, kept until it is closed, for Revert to Original. */
    private val original = File(undoFolder, "original")

    val pageCount: Int get() = source?.pageCount ?: 0
    val sizes: List<Size> get() = source?.sizes ?: emptyList()

    fun open() {
        scope.launch {
            // A file opened where it lives may have been changed by another app: show it as it is now.
            if (!store.sync(file)) {
                readOnly = true
                notice = "This file can't be reached right now. It may have been moved or deleted. It is shown as last seen."
            }
            withContext(Dispatchers.IO) { runCatching { file.copyTo(original, overwrite = true) } }
            reopen()
        }
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
            val copy = File(context.cacheDir, "unlocked-${file.path.hashCode()}.pdf")
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

    /**
     * Runs one change to the file and saves it at once; a file opened where it lives is written back
     * there. A copy is kept first so the change can be undone.
     */
    fun edit(change: (File) -> Unit) {
        if (readOnly) return
        queue {
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = File(undoFolder, "${System.nanoTime()}.pdf")
                    file.copyTo(snapshot, overwrite = true)
                    try {
                        change(file)
                        store.push(file)
                    } catch (e: Exception) {
                        // Nothing was saved: go back to the file as it was.
                        snapshot.copyTo(file, overwrite = true)
                        snapshot.delete()
                        throw e
                    }
                    undoStack.addLast(snapshot)
                    while (undoStack.size > 10) undoStack.removeFirst().delete()
                }
                canUndo = true
                settle()
            } catch (e: Exception) {
                message = e.message ?: "The change could not be saved."
                reopen()
            }
        }
    }

    // Changes are saved one after another, in the order they were asked for: one asked for while
    // another is still saving (a slow cloud file) waits its turn instead of being dropped.
    private val saving = Mutex()
    private val queued = AtomicInteger(0)

    private fun queue(work: suspend () -> Unit) {
        queued.incrementAndGet()
        busy = true
        scope.launch {
            try {
                saving.withLock { work() }
            } finally {
                if (queued.decrementAndGet() == 0) busy = false
            }
        }
    }

    fun undo() = queue {
        val snapshot = undoStack.removeLastOrNull() ?: return@queue
        try {
            withContext(Dispatchers.IO) {
                val current = File(undoFolder, "current")
                file.copyTo(current, overwrite = true)
                try {
                    snapshot.copyTo(file, overwrite = true)
                    store.push(file)
                } catch (e: Exception) {
                    // Nothing was saved: the document stays as it was, and the step can be undone later.
                    current.copyTo(file, overwrite = true)
                    throw e
                } finally {
                    current.delete()
                }
                snapshot.delete()
            }
        } catch (e: Exception) {
            undoStack.addLast(snapshot)
            message = e.message ?: "The change could not be saved."
        }
        canUndo = undoStack.isNotEmpty()
        settle()
    }

    // Tools ---------------------------------------------------------------------------------

    fun begin(mode: EditMode, page: Int) {
        if (readOnly) return
        editPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        editMode = mode
    }

    /** Leaves the tool without changing anything; the bar stays in its mode. */
    fun cancelEdit() {
        editMode = null
    }

    fun finishInk(strokes: List<InkStroke>) {
        val page = editPage
        editMode = null
        if (strokes.isNotEmpty()) edit { PdfEngine.ink(it, page, strokes) }
    }

    fun place(image: Bitmap, rect: RectF) {
        val page = editPage
        editMode = null
        edit { PdfEngine.stamp(it, page, image, rect) }
    }

    /** `lower` is, per rect, the side below its text (see [io.pdfoundry.app.data.TextWord.lower]). */
    fun markText(rects: List<RectF>, mark: io.pdfoundry.app.data.TextMark, argb: Int, lower: List<io.pdfoundry.app.data.Edge>? = null) {
        val page = editPage
        editMode = null
        if (rects.isNotEmpty()) edit { PdfEngine.markText(it, page, rects, mark, argb, lower) }
    }

    /** Puts the file back exactly as it was when it was opened. Undo brings the changes back. */
    fun revertToOriginal() = edit { original.copyTo(it, overwrite = true) }

    private suspend fun settle() {
        canRevert = withContext(Dispatchers.IO) { original.exists() && !sameContents(file, original) }
        reopen()
    }

    /** The words on a page, for selecting text to mark up. Empty for a page without text (a scan). */
    suspend fun words(page: Int): List<io.pdfoundry.app.data.TextWord> = withContext(Dispatchers.IO) {
        runCatching { PdfEngine.words(viewFile, page) }.getOrDefault(emptyList())
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

    var showPages by remember { mutableStateOf(false) }
    var showSignatures by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }
    val store = context.app.store
    val location = store.location(file)

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

    fun begin(newMode: EditMode) = state.begin(newMode, currentPage)

    val editing = state.editMode
    if (editing != null && state.source != null) {
        BackHandler { state.cancelEdit() }
        PageEditor(
            state = state,
            pageIndex = state.editPage,
            mode = editing,
            onCancel = { state.cancelEdit() },
            onInk = { strokes: List<InkStroke> -> state.finishInk(strokes) },
            onPlace = { image: Bitmap, rect: RectF -> state.place(image, rect) },
            onMarkText = { areas -> if (editing is EditMode.TextMark) state.markText(areas.map { it.rect }, editing.mark, editing.argb, areas.map { it.lower }) }
        )
        return
    }
    if (showPages && state.source != null) {
        BackHandler { showPages = false }
        PagesScreen(state = state, onDone = { showPages = false })
        return
    }
    // Back closes a mode before it closes the document.
    BackHandler(enabled = state.toolMode != ToolMode.None) { state.toolMode = ToolMode.None }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (location != null) Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.source != null) {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search in document") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; context.sharePdfs(listOf(file)) })
                                DropdownMenuItem(text = { Text("Print") }, leadingIcon = { Icon(Icons.Filled.Print, null) }, onClick = { menu = false; context.printPdf(file) })
                                if (location != null) {
                                    DropdownMenuItem(text = { Text("Save a copy in PDFoundry") }, leadingIcon = { Icon(Icons.Filled.FileCopy, null) }, onClick = {
                                        menu = false
                                        scope.launch {
                                            val saved = runCatching { store.saveCopy(file) }.isSuccess
                                            snackbar.showSnackbar(if (saved) "A copy is now in your documents." else "The copy could not be saved.")
                                        }
                                    })
                                }
                                if (!state.readOnly) {
                                    DropdownMenuItem(text = { Text("Revert to original") }, leadingIcon = { Icon(Icons.Filled.Restore, null) }, enabled = state.canRevert && !state.busy, onClick = { menu = false; confirmRevert = true })
                                }
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
                            Text(state.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    ModeBar(
                        mode = state.toolMode,
                        canUndo = state.canUndo && !state.busy,
                        onMode = { state.toolMode = it },
                        onPages = { showPages = true },
                        onUndo = { state.undo() },
                        onTextMark = { mark, argb -> begin(EditMode.TextMark(mark, argb)) },
                        onText = { showText = true },
                        onDraw = { begin(EditMode.Ink(highlighter = false)) },
                        onSignature = { showSignatures = true }
                    )
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

    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("Revert to original?") },
            text = { Text(if (location != null) "Every change made since you opened this document is removed, and the file in $location goes back to how it was." else "Every change made since you opened this document is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevert = false
                    state.revertToOriginal()
                }) { Text("Revert", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text("Cancel") } }
        )
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
