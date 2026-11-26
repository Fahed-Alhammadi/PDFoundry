package io.pdfoundry.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.PdfItem
import io.pdfoundry.app.data.Skipped
import kotlinx.coroutines.launch
import java.io.File

/** A document chosen in the picker. [item] is null for a temporary copy of a file picked from Files. */
data class PickedDocument(val file: File, val item: PdfItem?)

/**
 * Chooses documents for a tool: "Open from Files" first, then the document list. With [multiple],
 * documents are ticked and confirmed with Continue (two or more); otherwise one tap picks one.
 * With [listPickedFiles] a file picked from Files is opened in place (and listed, as Open PDF does);
 * without it, it is read from a temporary copy and not listed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPickerSheet(
    title: String,
    multiple: Boolean,
    listPickedFiles: Boolean,
    onDismiss: () -> Unit,
    onPick: (List<PickedDocument>) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val store = context.app.store
    val settings = context.app.settings
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(listOf<String>()) }
    var working by remember { mutableStateOf(false) }
    val items = store.sorted(settings.sort, "")

    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            working = true
            try {
                if (listPickedFiles) {
                    val skipped = mutableListOf<Skipped>()
                    val files = store.open(uris, skipped)
                    if (skipped.isNotEmpty()) onError(if (skipped.all { it == Skipped.NotPdf }) "That file isn't a PDF." else "A file couldn't be read right now.")
                    if (multiple) {
                        chosen = (chosen + files.map { it.path }).distinct()
                    } else {
                        files.firstOrNull()?.let { file -> onPick(listOf(PickedDocument(file, store.items.firstOrNull { it.file == file }))) }
                    }
                } else {
                    val copy = store.temporaryCopy(uris.first())
                    if (copy == null) onError("That file isn't a PDF, or it couldn't be read.") else onPick(listOf(PickedDocument(copy, null)))
                }
            } finally {
                working = false
            }
        }
    }

    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked(listOfNotNull(uri)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !working, role = Role.Button) {
                    if (multiple) many.launch(arrayOf("application/pdf")) else one.launch(arrayOf("application/pdf"))
                }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FolderOpen, null, tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Text("Open from Files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            HorizontalDivider()
            if (items.isEmpty()) {
                Text(
                    "No documents yet. Open one from Files.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(items, key = { it.file.path }) { item ->
                    val isChosen = item.file.path in chosen
                    val enabled = !item.locked && !working
                    val rowModifier = if (multiple) {
                        Modifier.toggleable(value = isChosen, enabled = enabled, role = Role.Checkbox) { on ->
                            chosen = if (on) chosen + item.file.path else chosen - item.file.path
                        }
                    } else {
                        Modifier.clickable(enabled = enabled, role = Role.Button) { onPick(listOf(PickedDocument(item.file, item))) }
                    }
                    Row(rowModifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DocumentThumbnail(item, Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            Text(item.name, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(listOfNotNull(item.detail, item.location).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (multiple) Checkbox(checked = isChosen, onCheckedChange = null, enabled = enabled)
                    }
                }
            }
            if (multiple) {
                // In the order they were ticked; the merge sheet can reorder them.
                val ready = chosen.mapNotNull { path -> items.firstOrNull { it.file.path == path } }
                Button(
                    enabled = ready.size >= 2 && !working,
                    onClick = { onPick(ready.map { PickedDocument(it.file, it) }) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                ) { Text(if (ready.size < 2) "Choose two or more" else "Continue with ${ready.size}") }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
