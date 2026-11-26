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
import androidx.compose.material.icons.automirrored.filled.MergeType
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

/** The whole-file tools. Later features (split, compress, password, OCR…) join a section here. */
enum class PdfTool(val title: String, val detail: String, val icon: ImageVector, val tint: Color, val section: ToolSection) {
    Scan("Scan Document", "Turn paper into a PDF with the camera.", Icons.Filled.DocumentScanner, Brand.Ember, ToolSection.Create),
    Photos("Create from Photos", "Make a PDF from pictures in your gallery.", Icons.Filled.PhotoLibrary, Color(0xFFD9822B), ToolSection.Create),
    Merge("Merge PDFs", "Combine documents into one, in your order.", Icons.AutoMirrored.Filled.MergeType, Brand.Steel, ToolSection.Combine),
    Extract("Extract Pages", "Save chosen pages as a new document.", Icons.Filled.FileUpload, Color(0xFF5B6478), ToolSection.Combine)
}

/** Whether Google's document scanner can run here: it lives in Google Play services and needs no permission. */
private fun scannerAvailable(context: android.content.Context): Boolean =
    runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpen: (PdfItem) -> Unit) {
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

    val actions = rememberDocumentActions(snackbar) { file -> announce(file, "“${file.nameWithoutExtension}” is now in Documents.") }
    val tools = remember { PdfTool.entries.filter { it != PdfTool.Scan || scannerAvailable(context) } }

    var picking by remember { mutableStateOf<PdfTool?>(null) }
    /** Documents waiting in the merge sheet. Temporary copies among them are this screen's to discard. */
    var mergeTargets by remember { mutableStateOf<List<PickedDocument>?>(null) }
    /** The document whose pages are being chosen. The extract screen owns a temporary copy. */
    var extracting by remember { mutableStateOf<PickedDocument?>(null) }

    // Leaving the tab (or a document arriving from another app) with the merge sheet open.
    val waitingMerge by rememberUpdatedState(mergeTargets)
    DisposableEffect(Unit) {
        onDispose { waitingMerge?.let { store.release(it) } }
    }

    extracting?.let { document ->
        ExtractPagesScreen(document = document, onCancel = { extracting = null }, onExtracted = { result, pages ->
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
            ToolSection.entries.forEach { section ->
                val inSection = tools.filter { it.section == section }
                if (inSection.isEmpty()) return@forEach
                item(key = section.name, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() }
                    )
                }
                items(inSection, key = { it.name }) { tool ->
                    ToolTile(tool) {
                        when (tool) {
                            PdfTool.Scan -> actions.scan()
                            PdfTool.Photos -> actions.createFromPhotos()
                            PdfTool.Merge, PdfTool.Extract -> picking = tool
                        }
                    }
                }
            }
        }
    }

    picking?.let { tool ->
        DocumentPickerSheet(
            title = if (tool == PdfTool.Merge) "Choose documents to merge" else "Choose a document",
            multiple = tool == PdfTool.Merge,
            onDismiss = { picking = null },
            onPick = { picked ->
                picking = null
                when (tool) {
                    PdfTool.Merge -> mergeTargets = picked
                    else -> extracting = picked.firstOrNull()
                }
            },
            onError = { message -> scope.launch { snackbar.showSnackbar(message) } }
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
