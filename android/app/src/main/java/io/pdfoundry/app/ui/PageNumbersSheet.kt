package io.pdfoundry.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.data.NumberFormat
import io.pdfoundry.app.data.NumberPosition
import io.pdfoundry.app.data.PageNumberStyle
import io.pdfoundry.app.data.PageStamps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page Numbers: position, format, the first number and "Skip the first page", with a live preview of
 * the first numbered page. Add applies them as one change (Undo removes them).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageNumbersSheet(state: ReaderState, onDismiss: () -> Unit) {
    var position by remember { mutableStateOf(NumberPosition.BottomCenter) }
    var format by remember { mutableStateOf(NumberFormat.Plain) }
    var startAt by remember { mutableStateOf("1") }
    var skipFirst by remember { mutableStateOf(false) }
    val start = startAt.toIntOrNull()?.takeIf { it in 0..99_999 }
    val style = PageNumberStyle(position, format, start ?: 1, skipFirst)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Page Numbers", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val previewPage = if (skipFirst && state.pageCount > 1) 1 else 0
            SheetPreview(state, previewPage, style) { document, count -> PageStamps.addPageNumbers(document, style, count, first = previewPage) }
            Spacer(Modifier.height(12.dp))
            Text("Position", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberPosition.entries.forEach { option -> FilterChip(selected = position == option, onClick = { position = option }, label = { Text(option.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Format", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberFormat.entries.forEach { option -> FilterChip(selected = format == option, onClick = { format = option }, label = { Text(option.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    startAt, { startAt = it.filter(Char::isDigit).take(5) }, label = { Text("Start at") }, singleLine = true,
                    isError = start == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.widthIn(max = 140.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text("Skip the first page", Modifier.weight(1f))
                Switch(checked = skipFirst, onCheckedChange = { skipFirst = it })
            }
            Spacer(Modifier.height(16.dp))
            Button(enabled = start != null, modifier = Modifier.fillMaxWidth(), onClick = {
                onDismiss()
                state.addPageNumbers(style)
            }) { Text("Add Page Numbers") }
        }
    }
}

/** A live preview of one page with a tool's change, redrawn a moment after the choices settle. */
@Composable
fun SheetPreview(state: ReaderState, pageIndex: Int, key: Any, apply: (com.tom_roush.pdfbox.pdmodel.PDDocument, Int) -> Unit) {
    val context = LocalContext.current
    val size = state.sizes.getOrNull(pageIndex)
    val aspect = size?.let { it.width.toFloat() / it.height } ?: (612f / 792f)
    val picture by produceState<Bitmap?>(null, key, pageIndex, state.version) {
        delay(250)
        value = withContext(Dispatchers.IO) {
            val scratch = File(context.cacheDir, "preview").apply { mkdirs() }
            PageStamps.preview(state.viewFile, pageIndex, 600, scratch, apply)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(240.dp).aspectRatio(aspect).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center
        ) {
            val shown = picture
            if (shown == null) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
            else Image(shown.asImageBitmap(), "Preview of page ${pageIndex + 1}", Modifier.aspectRatio(aspect), contentScale = ContentScale.FillBounds)
        }
    }
}
