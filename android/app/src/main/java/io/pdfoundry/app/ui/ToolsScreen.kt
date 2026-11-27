package io.pdfoundry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.pdfoundry.app.app
import io.pdfoundry.app.data.PdfItem
import kotlinx.coroutines.launch
import java.io.File

/** Groups on the Tools tab. A section with no tools yet is not shown. */
enum class ToolSection(val title: String) {
    Create("Create"),
    Combine("Combine & split"),
    Protect("Protect & optimize"),
    Enhance("Enhance")
}

/**
 * The whole-file tools. Redact, Make Searchable, Page Numbers and Watermark open the picked document in
 * the reader with the tool on top ([readerStart]); the others make new files.
 */
enum class PdfTool(val title: String, val detail: String, val icon: ImageVector, val tint: Color, val section: ToolSection) {
    Scan("Scan Document", "Turn paper into a PDF with the camera.", Icons.Filled.DocumentScanner, Brand.Ember, ToolSection.Create),
    Photos("Create from Photos", "Make a PDF from pictures in your gallery.", Icons.Filled.PhotoLibrary, Color(0xFFD9822B), ToolSection.Create),
    ExportImages("Export as Images", "Save pages as pictures.", Icons.Filled.Image, Color(0xFF2E7D6B), ToolSection.Create),
    Merge("Merge PDFs", "Combine documents into one, in your order.", Icons.AutoMirrored.Filled.MergeType, Brand.Steel, ToolSection.Combine),
    Extract("Extract Pages", "Save chosen pages as a new document.", Icons.Filled.FileUpload, Color(0xFF5B6478), ToolSection.Combine),
    Split("Split PDF", "Make several PDFs from one.", Icons.AutoMirrored.Filled.CallSplit, Color(0xFFB8441A), ToolSection.Combine),
    Protect("Password-protect", "Lock a PDF with a password.", Icons.Filled.Lock, Brand.Graphite, ToolSection.Protect),
    Compress("Compress", "Make a PDF smaller.", Icons.Filled.Compress, Color(0xFF4A6FA5), ToolSection.Protect),
    Redact("Redact", "Black out text for good.", Icons.Filled.FormatColorFill, Color(0xFF1F2330), ToolSection.Protect),
    MakeSearchable("Make Searchable", "Find text in scanned pages.", Icons.AutoMirrored.Filled.ManageSearch, Color(0xFF7A4FB5), ToolSection.Enhance),
    PageNumbers("Page Numbers", "Number every page.", Icons.Filled.FormatListNumbered, Color(0xFFC0602A), ToolSection.Enhance),
    Watermark("Watermark", "Stamp text across pages.", Icons.Filled.BrandingWatermark, Color(0xFF8C5A3C), ToolSection.Enhance)
}

/** The reader tool a tile opens the document with, or null for a tool that makes new files. */
val PdfTool.readerStart: ReaderStart?
    get() = when (this) {
        PdfTool.Redact -> ReaderStart.Redact
        PdfTool.MakeSearchable -> ReaderStart.MakeSearchable
        PdfTool.PageNumbers -> ReaderStart.PageNumbers
        PdfTool.Watermark -> ReaderStart.Watermark
        else -> null
    }

/** The Tools tab's sections in order, each with its tools; a section with no tool yet is left out. */
fun toolCatalog(scanner: Boolean): List<Pair<ToolSection, List<PdfTool>>> {
    val tools = PdfTool.entries.filter { it != PdfTool.Scan || scanner }
    return ToolSection.entries.map { section -> section to tools.filter { it.section == section } }.filter { it.second.isNotEmpty() }
}

/** Whether Google's document scanner can run here: it lives in Google Play services and needs no permission. */
private fun scannerAvailable(context: android.content.Context): Boolean =
    runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpen: (PdfItem) -> Unit, onOpenWith: (PdfItem, ReaderStart) -> Unit = { item, _ -> onOpen(item) }) {
    val context = LocalContext.current
    val store = context.app.store
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    /** Offers to open a document a tool just made. */
    suspend fun announce(file: File, message: String) {
        // Long, not the default for a snackbar with an action (it would stay until dismissed).
        if (snackbar.showSnackbar(message, actionLabel = "Open", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            store.items.firstOrNull { it.file == file }?.let(onOpen)
        }
    }

    val actions = rememberDocumentActions(snackbar, onCreated = { file -> announce(file, "“${file.nameWithoutExtension}” is now in Documents.") })
    val catalog = remember { toolCatalog(scannerAvailable(context)) }

    var picking by remember { mutableStateOf<PdfTool?>(null) }
    /** Documents waiting in the merge sheet. Temporary copies among them are this screen's to discard. */
    var mergeTargets by remember { mutableStateOf<List<PickedDocument>?>(null) }
    /** The document whose pages are being chosen, with its password if it has one. The extract screen owns a temporary copy. */
    var extracting by remember { mutableStateOf<Pair<PickedDocument, String?>?>(null) }
    /** The document being split, with its password if it has one. The split sheet owns a temporary copy. */
    var splitting by remember { mutableStateOf<Pair<PickedDocument, String?>?>(null) }
    /** A protected document waiting for its password before Extract or Split; this screen owns its temporary copy. */
    var unlocking by remember { mutableStateOf<Pair<PdfTool, PickedDocument>?>(null) }
    /** The document Password-protect is asking a password for (never a temporary copy). */
    var protecting by remember { mutableStateOf<PickedDocument?>(null) }
    var alreadyProtected by remember { mutableStateOf<PickedDocument?>(null) }
    /** The document being compressed or exported, with its password if it has one. The sheet owns a temporary copy. */
    var compressing by remember { mutableStateOf<Pair<PickedDocument, String?>?>(null) }
    var exporting by remember { mutableStateOf<Pair<PickedDocument, String?>?>(null) }

    // Leaving the tab (or a document arriving from another app) with the merge sheet open.
    val waitingMerge by rememberUpdatedState(mergeTargets)
    DisposableEffect(Unit) {
        onDispose { waitingMerge?.let { store.release(it) } }
    }

    extracting?.let { (document, password) ->
        ExtractPagesScreen(document = document, password = password, onCancel = { extracting = null }, onExtracted = { result, pages ->
            extracting = null
            scope.launch { announce(result, "Saved ${if (pages == 1) "1 page" else "$pages pages"} as “${result.nameWithoutExtension}” in Documents.") }
        })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Tools", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            catalog.forEach { (section, inSection) ->
                item(key = "section-${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() }
                    )
                }
                items(inSection, key = { "tool-${it.name}" }) { tool ->
                    ToolTile(tool) {
                        when (tool) {
                            PdfTool.Scan -> actions.scan()
                            PdfTool.Photos -> actions.createFromPhotos()
                            else -> picking = tool
                        }
                    }
                }
            }
        }
    }

    picking?.let { tool ->
        DocumentPickerSheet(
            title = when (tool) {
                PdfTool.Merge -> "Choose documents to merge"
                PdfTool.Protect -> "Choose a document to protect"
                PdfTool.Split -> "Choose a document to split"
                PdfTool.Compress -> "Choose a document to compress"
                PdfTool.ExportImages -> "Choose a document to export"
                PdfTool.Redact -> "Choose a document to redact"
                PdfTool.MakeSearchable -> "Choose a document to make searchable"
                PdfTool.PageNumbers -> "Choose a document to number"
                PdfTool.Watermark -> "Choose a document to watermark"
                else -> "Choose a document"
            },
            multiple = tool == PdfTool.Merge,
            onDismiss = { picking = null },
            onPick = { picked ->
                picking = null
                val first = picked.firstOrNull()
                when {
                    tool == PdfTool.Merge -> mergeTargets = picked
                    first == null -> Unit
                    tool == PdfTool.Protect -> if (first.item.locked) alreadyProtected = first else protecting = first
                    // Opened in the reader, which asks for a password itself.
                    tool.readerStart != null -> onOpenWith(first.item, tool.readerStart!!)
                    first.item.locked -> unlocking = tool to first
                    tool == PdfTool.Split -> splitting = first to null
                    tool == PdfTool.Compress -> compressing = first to null
                    tool == PdfTool.ExportImages -> exporting = first to null
                    else -> extracting = first to null
                }
            },
            onError = { message -> scope.launch { snackbar.showSnackbar(message) } },
            allowLocked = tool != PdfTool.Merge,
            // Protecting (or numbering, redacting…) a copy would leave the real file as it was: the file is
            // opened where it lives.
            openInPlace = tool == PdfTool.Protect || tool.readerStart != null
        )
    }

    unlocking?.let { (tool, document) ->
        UnlockDialog(document.item.name, document.file, onDismiss = { store.release(listOf(document)); unlocking = null }) { password ->
            unlocking = null
            when (tool) {
                PdfTool.Split -> splitting = document to password
                PdfTool.Compress -> compressing = document to password
                PdfTool.ExportImages -> exporting = document to password
                else -> extracting = document to password
            }
        }
    }

    splitting?.let { (document, password) ->
        SplitSheet(document, password, onDismiss = { splitting = null }) { parts ->
            splitting = null
            val first = parts.first()
            scope.launch {
                announce(first, if (parts.size == 1) "Saved “${first.nameWithoutExtension}” in Documents." else "Saved ${parts.size} files, “${first.nameWithoutExtension}” to “${parts.last().nameWithoutExtension}”, in Documents.")
            }
        }
    }

    compressing?.let { (document, password) ->
        CompressSheet(document, password, onDismiss = { compressing = null }) { made ->
            compressing = null
            scope.launch { announce(made, "Saved “${made.nameWithoutExtension}” in Documents.") }
        }
    }

    exporting?.let { (document, password) ->
        ExportImagesSheet(document, password, onDismiss = { exporting = null }, onMessage = { scope.launch { snackbar.showSnackbar(it) } })
    }

    protecting?.let { document ->
        ProtectDialog(document.item.name, onDismiss = { protecting = null }) { password ->
            protecting = null
            // In the app's scope: the file is replaced in full even if this screen goes meanwhile.
            context.app.scope.launch {
                val message = try {
                    store.protect(document.file, password)
                    "“${document.item.name}” is now protected with a password."
                } catch (e: Exception) {
                    e.message ?: "The password could not be set."
                }
                scope.launch { snackbar.showSnackbar(message) }
            }
        }
    }

    alreadyProtected?.let { document ->
        AlertDialog(
            onDismissRequest = { alreadyProtected = null },
            title = { Text("Already protected") },
            text = { Text("“${document.item.name}” already has a password. To remove it, open the document and choose More › Remove Password.") },
            confirmButton = { TextButton(onClick = { alreadyProtected = null }) { Text("OK") } }
        )
    }

    mergeTargets?.let { targets ->
        MergeDialog(targets.map { it.item }, onDismiss = { store.release(targets); mergeTargets = null }, canDeleteOriginals = targets.none { it.temporary }) { ordered, name, deleteOriginals ->
            // The merge now owns the documents: it finishes, and cleans up, even if this screen goes.
            mergeTargets = null
            context.app.scope.launch {
                try {
                    val merged = store.merge(ordered, name, deleteOriginals)
                    store.items.firstOrNull { it.file == merged }?.let(onOpen)
                } catch (e: Exception) {
                    scope.launch { snackbar.showSnackbar(e.message ?: "Something went wrong.") }
                } finally {
                    store.release(targets)
                }
            }
        }
    }
}

/** A tool tile: a white symbol on a coloured rounded tile, the title and one line of description. */
@Composable
private fun ToolTile(tool: PdfTool, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = tool.title, onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tool.tint), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(tool.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
