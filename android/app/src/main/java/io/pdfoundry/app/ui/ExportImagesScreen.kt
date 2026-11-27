package io.pdfoundry.app.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.ExportImages
import io.pdfoundry.app.data.ExportQuality
import io.pdfoundry.app.data.ImageFormat
import io.pdfoundry.app.data.PageRanges
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.RangeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where Export as Images puts its pictures: the share sheet, or a folder the user picks. */
private enum class ExportTarget { Share, Folder }

/**
 * Export as Images from the Tools tab: All pages or Custom ranges, PNG or JPEG, Screen (150 dpi) or
 * Print (300 dpi). Share hands the pictures to the share sheet; Save to Folder writes them into a folder
 * picked in the system's folder picker (no permission needed). The source is only read. Owns a
 * temporary copy like Split.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportImagesSheet(document: PickedDocument, password: String?, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val store = app.store
    val file = document.file
    var custom by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ImageFormat.Png) }
    var quality by remember { mutableStateOf(ExportQuality.Screen) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val sheet = remember { object { var gone = false; var cancelled = false } }
    DisposableEffect(document) {
        onDispose {
            sheet.gone = true
            sheet.cancelled = true
            store.release(listOf(document))
        }
    }
    val pageCount by produceState<Int?>(null, file) {
        value = withContext(Dispatchers.IO) {
            if (!document.temporary) store.syncForReading(file)
            runCatching { PdfEngine.pageCount(file, password) }.getOrDefault(0)
        }
    }
    val count = pageCount ?: 0
    val chosen: RangeResult? = when {
        count == 0 -> null
        !custom -> RangeResult.Ok(listOf((0 until count).toList()))
        else -> PageRanges.parse(ranges, count)
    }
    val pages = (chosen as? RangeResult.Ok)?.parts?.flatten()?.distinct()?.sorted().orEmpty()

    /** Renders the pictures into a fresh private folder, then hands them on. */
    fun export(target: ExportTarget, tree: Uri? = null) {
        if (pages.isEmpty() || progress != null) return
        problem = null
        progress = 0 to pages.size
        sheet.cancelled = false
        app.scope.launch {
            val folder = File(context.cacheDir, "exports/${System.nanoTime()}")
            try {
                val made = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "exports").listFiles()?.forEach { it.deleteRecursively() }
                    ExportImages.run(file, pages, format, quality.dpi, folder, document.item.name, password, progress = { done, total -> progress = done to total }, isCancelled = { sheet.cancelled })
                }
                when (target) {
                    ExportTarget.Share -> context.shareImages(made, format.mime)
                    ExportTarget.Folder -> {
                        val saved = withContext(Dispatchers.IO) { tree?.let { saveToTree(context, it, made, format.mime) } ?: 0 }
                        onMessage(if (saved == 1) "Saved 1 picture." else "Saved $saved pictures.")
                        folder.deleteRecursively()
                    }
                }
                if (!sheet.gone) onDismiss()
            } catch (e: InterruptedException) {
                folder.deleteRecursively()
            } catch (e: Exception) {
                folder.deleteRecursively()
                problem = e.message ?: "The pictures could not be saved."
            } finally {
                progress = null
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if (tree != null) export(ExportTarget.Folder, tree) }

    ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Export as Images", style = MaterialTheme.typography.titleLarge)
            Text(
                when (pageCount) {
                    null -> document.item.name
                    0 -> "This file isn't a readable PDF."
                    1 -> "${document.item.name} · 1 page"
                    else -> "${document.item.name} · $pageCount pages"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text("Pages", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !custom, onClick = { custom = false }, label = { Text("All") })
                FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom ranges") })
            }
            if (custom) {
                OutlinedTextField(
                    ranges, { ranges = it }, label = { Text("Page ranges") }, placeholder = { Text("1-3, 5") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth()
                )
                (chosen as? RangeResult.Bad)?.takeIf { ranges.isNotBlank() }?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Format", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageFormat.entries.forEach { option -> FilterChip(selected = format == option, onClick = { format = option }, label = { Text(option.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportQuality.entries.forEach { option -> FilterChip(selected = quality == option, onClick = { quality = option }, label = { Text("${option.label} (${option.dpi} dpi)") }) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            val running = progress
            if (running != null) {
                LinearProgressIndicator(progress = { if (running.second == 0) 0f else running.first.toFloat() / running.second }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Page ${minOf(running.first + 1, running.second)} of ${running.second}…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { sheet.cancelled = true }) { Text("Cancel") }
                }
            } else {
                Row {
                    Button(enabled = pages.isNotEmpty(), onClick = { export(ExportTarget.Share) }, modifier = Modifier.weight(1f)) { Text("Share") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(enabled = pages.isNotEmpty(), onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) { Text("Save to Folder") }
                }
            }
        }
    }
}

/** Writes [files] into the folder [tree] picked by the user; returns how many were saved. */
private fun saveToTree(context: android.content.Context, tree: Uri, files: List<File>, mime: String): Int {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    var saved = 0
    for (file in files) {
        // The provider adds " (1)" itself when the name is taken.
        val target = DocumentsContract.createDocument(resolver, parent, mime, file.name) ?: continue
        resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: continue
        saved++
    }
    return saved
}
