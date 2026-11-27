package io.pdfoundry.app.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.PdfEngine
import kotlinx.coroutines.launch

/** Reorder, rotate, duplicate, extract, add and delete pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(state: ReaderState, onDone: () -> Unit) {
    val store = LocalContext.current.app.store
    val scope = rememberCoroutineScope()
    val source = state.source ?: return
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val count = state.pageCount
    val chosen = selection.filter { it < count }.sorted()
    val single = chosen.singleOrNull()

    /** Page actions need a selection; say so instead of doing nothing. */
    fun withSelection(action: () -> Unit) {
        if (chosen.isEmpty()) notice = "Select one or more pages first." else {
            notice = null
            action()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chosen.isEmpty()) "Pages" else "${chosen.size} selected") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Done") } },
                actions = {
                    IconButton(onClick = {
                        val after = chosen.lastOrNull() ?: (count - 1)
                        state.edit { PdfEngine.addBlankPage(it, after) }
                    }) { Icon(Icons.AutoMirrored.Filled.NoteAdd, "Add blank page") }
                    TextButton(onClick = { selection = if (chosen.size == count) emptySet() else (0 until count).toSet() }) {
                        Text(if (chosen.size == count) "None" else "All")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { withSelection { state.edit { PdfEngine.rotate(it, chosen, -90) } } }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") }
                    IconButton(onClick = { withSelection { state.edit { PdfEngine.rotate(it, chosen, 90) } } }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") }
                    IconButton(enabled = single != null && single > 0, onClick = {
                        val from = single ?: return@IconButton
                        selection = setOf(from - 1)
                        state.edit { PdfEngine.move(it, from, from - 1) }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move page earlier") }
                    IconButton(enabled = single != null && single < count - 1, onClick = {
                        val from = single ?: return@IconButton
                        selection = setOf(from + 1)
                        state.edit { PdfEngine.move(it, from, from + 1) }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move page later") }
                    IconButton(onClick = { withSelection { state.edit { PdfEngine.duplicate(it, chosen) } } }) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
                    IconButton(onClick = {
                        withSelection {
                            scope.launch {
                                notice = try {
                                    val file = state.extract(chosen.toList())
                                    "Saved ${if (chosen.size == 1) "1 page" else "${chosen.size} pages"} as “${file.nameWithoutExtension}”."
                                } catch (e: Exception) {
                                    e.message
                                }
                            }
                        }
                    }) { Icon(Icons.Filled.FileUpload, "Extract to a new document") }
                    IconButton(enabled = chosen.isNotEmpty() && chosen.size < count, onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                notice ?: "Tap to select. Select one page to move it with the arrows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(count, key = { "$it-${state.version}" }) { index ->
                        val selected = index in selection
                        val size = state.sizes.getOrNull(index) ?: Size(612, 792)
                        val bitmap by produceState<Bitmap?>(null, index, state.version) { value = source.render(index, 300) }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { selection = if (selected) selection - index else selection + index }
                                .semantics { contentDescription = "Page ${index + 1}${if (selected) ", selected" else ""}" }
                        ) {
                            Box(Modifier.height(150.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.aspectRatio(size.width.toFloat() / size.height).background(Color.White)
                                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                                    if (selected) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.White))
                                    }
                                }
                            }
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (chosen.size == 1) "Delete this page?" else "Delete ${chosen.size} pages?") },
            text = { Text("You can bring it back with Undo while this document stays open.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val targets = chosen
                    selection = emptySet()
                    state.edit { PdfEngine.delete(it, targets) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
