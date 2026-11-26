package io.pdfoundry.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.pdfoundry.app.app
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.LibrarySort
import io.pdfoundry.app.data.PdfItem
import io.pdfoundry.app.data.Thumbnails
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpen: (PdfItem) -> Unit) {
    val context = LocalContext.current
    val store = context.app.store
    val settings = context.app.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PdfItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<PdfItem>>(emptyList()) }
    var mergeTargets by remember { mutableStateOf<List<PdfItem>?>(null) }

    val visible = store.sorted(settings.sort, query)
    val selected = visible.filter { it.file.path in selection }
    val stamp = { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()) }

    fun endSelection() {
        selecting = false
        selection = emptySet()
    }

    /** Runs a library action and reports a failure in plain words. */
    fun perform(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Something went wrong.")
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) perform {
            val added = store.import(uris)
            if (added.size < uris.size) snackbar.showSnackbar("Some files weren't PDFs and were skipped.")
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) perform { store.createFromImages(uris, "Photos ${stamp()}") }
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pdf: Uri? = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        if (result.resultCode == Activity.RESULT_OK && pdf != null) perform { store.import(listOf(pdf), "Scan ${stamp()}") }
    }

    fun scan() {
        val activity = context as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setGalleryImportAllowed(true)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { perform { snackbar.showSnackbar("The scanner isn't available on this device. It needs Google Play services.") } }
    }

    BackHandler(enabled = selecting) { endSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(if (selection.isEmpty()) "Select documents" else "${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { endSelection() }) { Icon(Icons.Filled.Close, "Cancel selection") } },
                    actions = {
                        IconButton(onClick = { mergeTargets = selected }, enabled = selected.size >= 2) { Icon(Icons.AutoMirrored.Filled.MergeType, "Merge") }
                        IconButton(onClick = { context.sharePdfs(selected.map { it.file }) }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = { deleteTargets = selected }, enabled = selected.isNotEmpty()) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Documents", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search documents") }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                LibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.title) },
                                        onClick = { settings.update(option); sortMenu = false },
                                        trailingIcon = { if (settings.sort == option) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selecting) Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Add a document") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Import PDF") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }, onClick = { addMenu = false; importer.launch(arrayOf("application/pdf")) })
                    DropdownMenuItem(text = { Text("Scan document") }, leadingIcon = { Icon(Icons.Filled.DocumentScanner, null) }, onClick = { addMenu = false; scan() })
                    DropdownMenuItem(text = { Text("Create from photos") }, leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) }, onClick = {
                        addMenu = false
                        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            if (searching && !selecting) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search documents") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            when {
                !store.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                store.items.isEmpty() -> EmptyState(
                    title = "No documents",
                    body = "Import a PDF, scan paper with the camera, or build one from your photos.",
                    action = "Import PDF"
                ) { importer.launch(arrayOf("application/pdf")) }
                visible.isEmpty() -> EmptyState("No results", "No document name contains “$query”.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(visible, key = { it.file.path }) { item ->
                        val isSelected = item.file.path in selection
                        DocumentCard(
                            item = item,
                            selecting = selecting,
                            selected = isSelected,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selecting) selection = if (isSelected) selection - item.file.path else selection + item.file.path
                                    else onOpen(item)
                                },
                                onLongClick = {
                                    selecting = true
                                    selection = selection + item.file.path
                                }
                            ),
                            onRename = { renameTarget = item },
                            onDuplicate = { perform { store.duplicate(item) } },
                            onShare = { context.sharePdfs(listOf(item.file)) },
                            onDelete = { deleteTargets = listOf(item) }
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename document") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = DocumentStore.sanitized(name).isNotEmpty(), onClick = {
                    renameTarget = null
                    perform { store.rename(target, name) }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    if (deleteTargets.isNotEmpty()) {
        val targets = deleteTargets
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text(if (targets.size == 1) "Delete “${targets[0].name}”?" else "Delete ${targets.size} documents?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargets = emptyList()
                    perform { store.delete(targets) }
                    endSelection()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel") } }
        )
    }

    mergeTargets?.let { targets ->
        MergeDialog(targets, onDismiss = { mergeTargets = null }) { ordered, name, deleteOriginals ->
            mergeTargets = null
            perform {
                val merged = store.merge(ordered, name, deleteOriginals)
                endSelection()
                store.items.firstOrNull { it.file == merged }?.let(onOpen)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DocumentCard(
    item: PdfItem,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).semantics { contentDescription = "${item.name}, ${item.detail}" }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.76f).clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            DocumentThumbnail(item, Modifier.fillMaxSize())
            if (selecting) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.2f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).background(Color.White, CircleShape)
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "More actions for ${item.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun DocumentThumbnail(item: PdfItem, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, item.file.path, item.modified) { value = Thumbnails.load(item, 360) }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Icon(if (item.locked) Icons.Filled.Lock else Icons.Outlined.Description, null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun MergeDialog(items: List<PdfItem>, onDismiss: () -> Unit, onMerge: (List<PdfItem>, String, Boolean) -> Unit) {
    val order = remember { mutableStateListOf<PdfItem>().apply { addAll(items) } }
    var name by remember { mutableStateOf("Merged Document") }
    var deleteOriginals by remember { mutableStateOf(false) }
    val totalPages = order.sumOf { it.pages }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${order.size} documents") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Order", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                order.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        DocumentThumbnail(item, Modifier.width(30.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { order.add(index - 1, order.removeAt(index)) }, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.ArrowUpward, "Move ${item.name} earlier") }
                        IconButton(onClick = { order.add(index + 1, order.removeAt(index)) }, enabled = index < order.lastIndex, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.ArrowDownward, "Move ${item.name} later") }
                    }
                }
                Text("The merged document will have $totalPages pages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Delete originals after merging", Modifier.weight(1f))
                    Switch(checked = deleteOriginals, onCheckedChange = { deleteOriginals = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = DocumentStore.sanitized(name).isNotEmpty(), onClick = { onMerge(order.toList(), name, deleteOriginals) }) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
