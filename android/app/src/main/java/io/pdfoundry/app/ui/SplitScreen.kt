package io.pdfoundry.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.PageRanges
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.RangeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How Split PDF divides a document. */
enum class SplitMode(val title: String) { EveryPage("Every page"), EveryN("Every N pages"), Custom("Custom ranges") }

/** The parts Split would make from these choices, or why it can't. */
fun splitParts(mode: SplitMode, every: String, ranges: String, pageCount: Int): RangeResult = when (mode) {
    SplitMode.EveryPage -> RangeResult.Ok(PageRanges.every(1, pageCount))
    SplitMode.EveryN -> every.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { RangeResult.Ok(PageRanges.every(it, pageCount)) }
        ?: RangeResult.Bad("Enter how many pages go in each file, such as 2.")
    SplitMode.Custom -> PageRanges.parse(ranges, pageCount)
}

/** The line under the choices: how many files, or what is wrong. */
fun splitSummary(result: RangeResult): String = when (result) {
    is RangeResult.Ok -> if (result.parts.size == 1) "Makes 1 file." else "Makes ${result.parts.size} files."
    is RangeResult.Bad -> result.message
}

/**
 * Split PDF from the Tools tab: choose how to divide [document], then Split. The parts go into the
 * library as "<name> (part 1)"…; the source is only read. A protected source comes with its [password]
 * and every part keeps it. This sheet owns a [PickedDocument.temporary] copy: it is discarded when the
 * sheet goes or the split has finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(document: PickedDocument, password: String?, onDismiss: () -> Unit, onSplit: (List<File>) -> Unit) {
    val app = LocalContext.current.app
    val store = app.store
    val file = document.file
    var mode by remember { mutableStateOf(SplitMode.EveryPage) }
    var every by remember { mutableStateOf("2") }
    var ranges by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val sheet = remember { object { var gone = false } }
    DisposableEffect(document) {
        onDispose {
            sheet.gone = true
            if (!started) store.release(listOf(document))
        }
    }
    // Null while counting; 0 when the file can't be read.
    val pageCount by produceState<Int?>(null, file) {
        value = withContext(Dispatchers.IO) {
            if (!document.temporary) store.syncForReading(file)
            runCatching { PdfEngine.pageCount(file, password) }.getOrDefault(0)
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Split PDF", style = MaterialTheme.typography.titleLarge)
            val count = pageCount
            Text(
                when (count) {
                    null -> file.nameWithoutExtension
                    0 -> "This file isn't a readable PDF."
                    1 -> "${file.nameWithoutExtension} · 1 page"
                    else -> "${file.nameWithoutExtension} · $count pages"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                SplitMode.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == option, role = Role.RadioButton, enabled = !working) { mode = option }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == option, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            when (mode) {
                SplitMode.EveryN -> OutlinedTextField(
                    every, { every = it.filter(Char::isDigit).take(4) }, label = { Text("Pages in each file") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                SplitMode.Custom -> OutlinedTextField(
                    ranges, { ranges = it }, label = { Text("Page ranges") }, placeholder = { Text("1-3, 4-6, 9") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth()
                )
                SplitMode.EveryPage -> Unit
            }
            Spacer(Modifier.height(10.dp))
            val result = count?.takeIf { it > 0 }?.let { splitParts(mode, every, ranges, it) }
            // Custom ranges not typed yet: say what to type, without the red.
            val untouched = mode == SplitMode.Custom && ranges.isBlank()
            if (result != null) {
                Text(
                    problem ?: splitSummary(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem != null || (result is RangeResult.Bad && !untouched)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = result is RangeResult.Ok && !working,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val parts = (result as? RangeResult.Ok)?.parts ?: return@Button
                    working = true
                    started = true
                    problem = null
                    // In the app's scope: finishes, and cleans up, even if the sheet goes meanwhile.
                    app.scope.launch {
                        try {
                            val made = store.split(file, parts, password)
                            store.release(listOf(document))
                            onSplit(made)
                        } catch (e: Exception) {
                            if (sheet.gone) store.release(listOf(document))
                            started = false
                            problem = e.message ?: "The files could not be saved."
                        } finally {
                            working = false
                        }
                    }
                }
            ) {
                if (working) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Text("Split")
            }
        }
    }
}
