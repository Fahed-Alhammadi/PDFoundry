package io.pdfoundry.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Make Searchable: says how many pages have no text (only those are processed), then recognises them
 * with progress and Cancel. Cancel changes nothing; the sheet can't be swiped away while it runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrSheet(state: ReaderState, onDismiss: () -> Unit) {
    val pages by produceState<List<Int>?>(null, state.version) { value = state.pagesNeedingText() }
    val progress = state.ocrProgress
    ModalBottomSheet(
        onDismissRequest = { if (state.ocrProgress == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { state.ocrProgress == null })
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Make Searchable", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            val todo = pages
            Text(
                when {
                    todo == null -> "Looking for pages without text…"
                    todo.isEmpty() -> "Every page already has text."
                    todo.size == state.pageCount -> if (todo.size == 1) "The page has no text. It will be recognised (English and Arabic)." else "None of the ${todo.size} pages has text. They will be recognised (English and Arabic)."
                    else -> "${todo.size} of ${state.pageCount} pages have no text and will be recognised (English and Arabic). Pages that already have text are skipped."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("The pages look the same afterwards; their words can be searched, selected and copied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            if (progress != null) {
                val (done, total) = progress
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text(if (done < total) "Recognising page ${done + 1} of $total…" else "Adding the text…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { state.cancelOcr() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            } else {
                Button(enabled = !todo.isNullOrEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    state.makeSearchable(todo.orEmpty()) { found -> if (found != null) onDismiss() }
                }) { Text("Make Searchable") }
            }
        }
    }
}
