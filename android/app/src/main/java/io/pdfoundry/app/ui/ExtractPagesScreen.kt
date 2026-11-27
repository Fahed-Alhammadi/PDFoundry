package io.pdfoundry.app.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extract Pages from the Tools tab: tick pages, then Extract. Only reads the document (nothing here can
 * change it: no delete, rotate or reorder), and the new document goes into the library. A file opened
 * in place is first brought up to date, so the pages shown are the pages extracted. This screen owns a
 * [PickedDocument.temporary] copy: it is discarded when the screen goes, or, once Extract was tapped,
 * when the extraction has finished. A protected document comes with its [password]: its pages are shown
 * from an unlocked copy in the private cache (deleted when the screen goes) and the new document keeps
 * the password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractPagesScreen(document: PickedDocument, password: String? = null, onCancel: () -> Unit, onExtracted: (File, Int) -> Unit) {
    val app = LocalContext.current.app
    val store = app.store
    val file = document.file
    var chosen by remember(file) { mutableStateOf(setOf<Int>()) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val screen = remember { object { var gone = false } }
    DisposableEffect(document) {
        onDispose {
            screen.gone = true
            if (!started) store.release(listOf(document))
        }
    }
    // While the pages are being saved there is no way back: the result is always reported.
    BackHandler { if (!working) onCancel() }

    // null while opening; failure leaves it null and says why. Closed when the screen goes, even mid-open.
    val source by produceState<PdfSource?>(null, file) {
        var opened: PdfSource? = null
        val unlocked = password?.let { File(DocumentStore.unlockedDirectory(app), "${java.util.UUID.randomUUID()}.pdf") }
        try {
            withContext(Dispatchers.IO) {
                if (!document.temporary && !store.syncForReading(file)) notice = "This file can't be reached right now. It is shown as last seen."
                try {
                    if (unlocked != null) {
                        unlocked.parentFile?.mkdirs()
                        if (PdfEngine.unlock(file, password, unlocked) == UnlockResult.Wrong) throw SecurityException()
                    }
                    opened = PdfSource(unlocked ?: file)
                } catch (e: Exception) {
                    problem = if (e is SecurityException) "Pages can't be extracted from a password-protected document." else "This file isn't a readable PDF."
                }
            }
            value = opened
            awaitCancellation()
        } finally {
            val toClose = opened
            Thread { toClose?.close(); unlocked?.delete() }.start()
        }
    }
    val count = source?.pageCount ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Extract Pages")
                        Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onCancel, enabled = !working) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    if (count > 0) TextButton(onClick = { chosen = if (chosen.size == count) emptySet() else (0 until count).toSet() }) {
                        Text(if (chosen.size == count) "None" else "All")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        enabled = chosen.isNotEmpty() && !working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            working = true
                            started = true
                            val pages = chosen.sorted()
                            // In the app's scope: finishes, and cleans up, even if the screen goes meanwhile.
                            app.scope.launch {
                                try {
                                    val result = store.extractPages(file, pages, password)
                                    store.release(listOf(document))
                                    onExtracted(result, pages.size)
                                } catch (e: Exception) {
                                    if (screen.gone) {
                                        store.release(listOf(document))
                                    } else {
                                        // Still on screen: the user can try again or cancel (which discards the copy).
                                        started = false
                                    }
                                    problem = e.message ?: "The pages could not be saved."
                                } finally {
                                    working = false
                                }
                            }
                        }
                    ) {
                        Text(
                            when (chosen.size) {
                                0 -> "Extract"
                                1 -> "Extract 1 page"
                                else -> "Extract ${chosen.size} pages"
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                problem ?: notice ?: "Tap the pages to copy into a new document. The original stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Box(Modifier.fillMaxSize()) {
                val opened = source
                if (opened == null) {
                    if (problem == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(count, key = { it }) { index ->
                            val selected = index in chosen
                            val size = opened.sizes.getOrNull(index) ?: Size(612, 792)
                            val bitmap by produceState<Bitmap?>(null, index) { value = opened.render(index, 300) }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.toggleable(value = selected, role = Role.Checkbox) { on -> chosen = if (on) chosen + index else chosen - index }
                                    .semantics { contentDescription = "Page ${index + 1}" }
                            ) {
                                Box(Modifier.height(150.dp), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.aspectRatio(size.width.toFloat() / size.height).background(Color.White)
                                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                                        Icon(
                                            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                                            tint = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.White)
                                        )
                                    }
                                }
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                if (working) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}
