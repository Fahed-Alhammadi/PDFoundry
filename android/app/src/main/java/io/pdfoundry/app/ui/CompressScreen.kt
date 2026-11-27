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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.CompressLevel
import io.pdfoundry.app.data.formatSize
import kotlinx.coroutines.launch
import java.io.File

/** "This file is already as small as it can be." */
const val CompressNoGain = "This file is already as small as it can be."

/**
 * Compress from the Tools tab: Smaller or Smallest, then a smaller copy, "<name> (compressed)", goes into
 * Documents; the source is only read and not listed. Shows the size before, and after it ran. A
 * protected source comes with its [password] and the copy keeps it. Owns a temporary copy like Split.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressSheet(document: PickedDocument, password: String?, onDismiss: () -> Unit, onDone: (File) -> Unit) {
    val app = LocalContext.current.app
    val store = app.store
    val file = document.file
    var level by remember { mutableStateOf(CompressLevel.Smaller) }
    var working by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<File?>(null) }
    val sheet = remember { object { var gone = false } }
    DisposableEffect(document) {
        onDispose {
            sheet.gone = true
            if (!started) store.release(listOf(document))
        }
    }
    val before = remember(file) { file.length() }

    ModalBottomSheet(onDismissRequest = { if (!working) { made?.let(onDone) ?: onDismiss() } }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Compress", style = MaterialTheme.typography.titleLarge)
            Text("${document.item.name} · ${formatSize(before)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val result = made
            if (result != null) {
                Text("Saved “${result.nameWithoutExtension}” in Documents: ${formatSize(result.length())}, was ${formatSize(before)}.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onDone(result) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }
            Column(Modifier.selectableGroup()) {
                CompressLevel.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = level == option, role = Role.RadioButton, enabled = !working) { level = option; problem = null }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = level == option, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (option == CompressLevel.Smaller) "Pictures at 150 dpi. Good for reading on screen." else "Pictures at 100 dpi, lower quality. The smallest file.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Text("Text, drawings, links, form fields and marks stay as they are. The original is not changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(14.dp))
            Button(enabled = !working, modifier = Modifier.fillMaxWidth(), onClick = {
                working = true
                started = true
                problem = null
                app.scope.launch {
                    try {
                        val out = store.compress(file, level, password, document.item.name)
                        if (out == null) {
                            problem = CompressNoGain
                            started = false
                            if (sheet.gone) store.release(listOf(document))
                        } else {
                            store.release(listOf(document))
                            made = out
                        }
                    } catch (e: Exception) {
                        if (sheet.gone) store.release(listOf(document))
                        started = false
                        problem = e.message ?: "The file could not be compressed."
                    } finally {
                        working = false
                    }
                }
            }) {
                if (working) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Text("Compress")
            }
        }
    }
}
