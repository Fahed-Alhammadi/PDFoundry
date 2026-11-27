package io.pdfoundry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.data.Ember
import io.pdfoundry.app.data.MarkInk
import io.pdfoundry.app.data.PageStamps
import io.pdfoundry.app.data.WatermarkStyle
import kotlin.math.roundToInt

/**
 * Watermark: the text (CONFIDENTIAL, DRAFT and COPY to pick from), opacity, diagonal or horizontal, and
 * graphite or ember, on every page, with a live preview of the first page. Add applies it as one change.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatermarkSheet(state: ReaderState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    var diagonal by remember { mutableStateOf(true) }
    var ember by remember { mutableStateOf(false) }
    val style = WatermarkStyle(text.trim(), opacity, diagonal, if (ember) Ember else MarkInk)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Watermark", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            SheetPreview(state, 0, style) { document, _ -> if (style.text.isNotEmpty()) PageStamps.addWatermark(document, style) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(text, { text = it.take(60) }, label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("CONFIDENTIAL", "DRAFT", "COPY").forEach { choice -> FilterChip(selected = text == choice, onClick = { text = choice }, label = { Text(choice) }) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Opacity: ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = opacity, onValueChange = { opacity = (it * 20).roundToInt() / 20f }, valueRange = 0.1f..1f, modifier = Modifier.semantics { contentDescription = "Opacity" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = diagonal, onClick = { diagonal = true }, label = { Text("Diagonal") })
                FilterChip(selected = !diagonal, onClick = { diagonal = false }, label = { Text("Horizontal") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !ember, onClick = { ember = false }, label = { Text("Graphite") }, leadingIcon = { Swatch(Color(MarkInk)) })
                FilterChip(selected = ember, onClick = { ember = true }, label = { Text("Ember") }, leadingIcon = { Swatch(Color(Ember)) })
            }
            Spacer(Modifier.height(16.dp))
            Button(enabled = style.text.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                onDismiss()
                state.addWatermark(style)
            }) { Text("Add Watermark") }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(color))
}
