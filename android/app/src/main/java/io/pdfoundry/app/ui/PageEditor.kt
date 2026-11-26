package io.pdfoundry.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.app
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.PagePoint
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.data.TextWord
import kotlin.math.roundToInt

val SignatureIcon: ImageVector = Icons.Filled.Gesture

private class LiveStroke(val points: MutableList<Offset>, val color: Color, val width: Float, val highlighter: Boolean)

/** A pen in Draw: three inks, and the freehand highlighter (a wide, see-through marker) in four colours. */
private data class Pen(val name: String, val color: Color, val highlighter: Boolean)

private val Pens = listOf(Pen("Black pen", Color.Black, false), Pen("Blue pen", Color(0xFF0D3399), false), Pen("Red pen", Color(0xFFC62828), false))
private val Highlighters = listOf(
    Pen("Yellow highlighter", Color(0xFFFFE600), true),
    Pen("Green highlighter", Color(0xFF6BE675), true),
    Pen("Pink highlighter", Color(0xFFFF8FC0), true),
    Pen("Blue highlighter", Color(0xFF7CC4FF), true)
)

/** One page, fitted to the screen, for highlighting, drawing, or positioning a signature or text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditor(
    state: ReaderState,
    pageIndex: Int,
    mode: EditMode,
    onCancel: () -> Unit,
    onInk: (List<InkStroke>) -> Unit,
    onPlace: (Bitmap, RectF) -> Unit,
    onMarkText: (List<LineArea>) -> Unit = {}
) {
    val source = state.source ?: return
    val pageSize = state.sizes.getOrNull(pageIndex) ?: Size(612, 792)
    val aspect = pageSize.width.toFloat() / pageSize.height

    val strokes = remember { mutableStateListOf<LiveStroke>() }
    var revision by remember { mutableIntStateOf(0) }
    var pen by remember { mutableStateOf(if ((mode as? EditMode.Ink)?.highlighter == true) Highlighters.first() else Pens.first()) }
    // Words on the page, for text markup; null while they load.
    val words by produceState<List<TextWord>?>(null, pageIndex, state.version, mode is EditMode.TextMark) {
        value = if (mode is EditMode.TextMark) state.words(pageIndex) else emptyList()
    }
    // The item being placed, as fractions of the page: centre and width.
    var centerX by remember { mutableFloatStateOf(0.5f) }
    var centerY by remember { mutableFloatStateOf(0.5f) }
    var widthFraction by remember { mutableFloatStateOf(0.45f) }
    var boxSize by remember { mutableStateOf(Offset(1f, 1f)) }

    val title = when (mode) {
        is EditMode.Ink -> "Draw"
        is EditMode.Place -> "Place ${mode.label}"
        is EditMode.TextMark -> when (mode.mark) {
            TextMark.Highlight -> "Highlight"
            TextMark.Underline -> "Underline"
            TextMark.Strikethrough -> "Strikethrough"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    when (mode) {
                        is EditMode.Ink -> {
                            IconButton(enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex); revision++ }) { Icon(Icons.AutoMirrored.Filled.Undo, "Remove last stroke") }
                            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                                onInk(strokes.map { stroke ->
                                    InkStroke(
                                        points = stroke.points.map { PagePoint(it.x / boxSize.x, it.y / boxSize.y) },
                                        argb = stroke.color.toArgb(),
                                        width = stroke.width / boxSize.x,
                                        highlighter = stroke.highlighter
                                    )
                                })
                            }) { Text("Done") }
                        }
                        is EditMode.Place -> TextButton(onClick = {
                            val imageAspect = mode.image.width.toFloat() / mode.image.height
                            val heightFraction = widthFraction * boxSize.x / imageAspect / boxSize.y
                            onPlace(mode.image, RectF(centerX - widthFraction / 2, centerY - heightFraction / 2, centerX + widthFraction / 2, centerY + heightFraction / 2))
                        }) { Text("Place") }
                        // Lifting the finger applies the mark: nothing to confirm.
                        is EditMode.TextMark -> Unit
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    when (mode) {
                        is EditMode.Ink -> {
                            Pens.forEach { option -> PenSwatch(option, selected = option == pen) { pen = option } }
                            Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            Highlighters.forEach { option -> PenSwatch(option, selected = option == pen) { pen = option } }
                        }
                        is EditMode.Place -> Text("Drag to move. Use the handle to resize.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is EditMode.TextMark -> Text(
                            when {
                                words == null -> "Finding the text on this page…"
                                words?.isEmpty() == true -> "This page has no text to mark up. Use Draw instead."
                                else -> "Drag across the words, or tap one."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            // Fit the page inside the available space, keeping its shape.
            val fitByWidth = maxWidth / maxHeight < aspect
            val pageWidth = if (fitByWidth) maxWidth else maxHeight * aspect
            val pageHeight = if (fitByWidth) maxWidth / aspect else maxHeight
            val widthPx = with(LocalDensity.current) { pageWidth.toPx() }
            val heightPx = with(LocalDensity.current) { pageHeight.toPx() }
            boxSize = Offset(widthPx, heightPx)

            val bitmap by produceState<Bitmap?>(null, pageIndex, widthPx.toInt(), state.version) { value = source.render(pageIndex, widthPx.toInt().coerceIn(1, 3000)) }

            Box(Modifier.size(pageWidth, pageHeight).background(Color.White)) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }

                when (mode) {
                    is EditMode.TextMark -> words?.takeIf { it.isNotEmpty() }?.let { found ->
                        TextSelectionLayer(found, aspect, mode.mark, mode.argb, onSelect = onMarkText)
                    }
                    is EditMode.Ink -> {
                        val strokeWidth = if (pen.highlighter) widthPx * 0.028f else widthPx * 0.006f
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(pen, strokeWidth) {
                                detectDragGestures(
                                    onDragStart = { start -> strokes.add(LiveStroke(mutableListOf(start), pen.color, strokeWidth, pen.highlighter)); revision++ },
                                    onDrag = { change, _ ->
                                        strokes.lastOrNull()?.points?.add(Offset(change.position.x.coerceIn(0f, widthPx), change.position.y.coerceIn(0f, heightPx)))
                                        revision++
                                    }
                                )
                            }
                        ) {
                            revision // redraw as points arrive
                            for (stroke in strokes) {
                                val path = Path().apply {
                                    stroke.points.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
                                }
                                drawPath(
                                    path,
                                    color = stroke.color.copy(alpha = if (stroke.highlighter) 0.45f else 1f),
                                    style = Stroke(stroke.width, cap = if (stroke.highlighter) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
                                    blendMode = if (stroke.highlighter) BlendMode.Multiply else BlendMode.SrcOver
                                )
                            }
                        }
                    }
                    is EditMode.Place -> {
                        val imageAspect = mode.image.width.toFloat() / mode.image.height
                        val itemWidth = widthFraction * widthPx
                        val itemHeight = itemWidth / imageAspect
                        val left = centerX * widthPx - itemWidth / 2
                        val top = centerY * heightPx - itemHeight / 2
                        val accent = MaterialTheme.colorScheme.primary
                        val density = LocalDensity.current
                        Box(
                            Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(with(density) { itemWidth.toDp() }, with(density) { itemHeight.toDp() })
                                .pointerInput(Unit) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        centerX = (centerX + drag.x / widthPx).coerceIn(0.02f, 0.98f)
                                        centerY = (centerY + drag.y / heightPx).coerceIn(0.02f, 0.98f)
                                    }
                                }
                                .semantics { contentDescription = "Item to place. Drag to move." }
                        ) {
                            Image(mode.image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(accent, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                            }
                            Box(
                                Modifier.align(Alignment.BottomEnd).offset(18.dp, 18.dp).size(36.dp).clip(CircleShape).background(accent)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            widthFraction = (widthFraction + 2 * drag.x / widthPx).coerceIn(0.08f, 1.2f)
                                        }
                                    }
                                    .semantics { contentDescription = "Resize" },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.OpenInFull, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PenSwatch(pen: Pen, selected: Boolean, onClick: () -> Unit) {
    // Pens are round; highlighters are rounded squares, like a marker's tip.
    val shape = if (pen.highlighter) RoundedCornerShape(8.dp) else CircleShape
    Box(
        Modifier.padding(horizontal = 4.dp).size(34.dp).clip(shape).background(pen.color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClickLabel = pen.name) { onClick() }
            .semantics { contentDescription = pen.name + if (selected) ", selected" else "" }
    )
}

// region Signatures

/** Pick a saved signature or draw a new one. */
@Composable
fun SignaturePicker(onPick: (Bitmap) -> Unit) {
    val store = LocalContext.current.app.signatures
    var drawing by remember { mutableStateOf(store.signatures.isEmpty()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        if (drawing) {
            SignaturePad(onUse = { image, keep ->
                if (keep) store.add(image)
                onPick(image)
            })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signature", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { drawing = true }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            SignatureList(onPick = onPick, modifier = Modifier.height(300.dp))
        }
    }
}

/** Saved signatures. With `onPick`, tapping one chooses it. */
@Composable
fun SignatureList(modifier: Modifier = Modifier, onPick: ((Bitmap) -> Unit)? = null) {
    val store = LocalContext.current.app.signatures
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(store.signatures, key = { it.file.name }) { signature ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(enabled = onPick != null, onClickLabel = "Use this signature") { onPick?.invoke(signature.image) }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) { Image(signature.image.asImageBitmap(), "Saved signature", contentScale = ContentScale.Fit) }
                IconButton(onClick = { store.delete(signature) }) { Icon(Icons.Filled.Delete, "Delete signature") }
            }
        }
    }
}

/** A pad for drawing a signature with a finger or stylus. */
@Composable
fun SignaturePad(onUse: (Bitmap, keep: Boolean) -> Unit) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var revision by remember { mutableIntStateOf(0) }
    var blue by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(true) }
    val ink = if (blue) Color(0xFF0D3399) else Color.Black

    Column {
        Text("New signature", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Gray.copy(alpha = 0.5f)))
                Text("Sign above the line", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { strokes.add(mutableListOf(it)); revision++ },
                        onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position); revision++ }
                    )
                }.semantics { contentDescription = "Signature pad" }
            ) {
                revision
                for (points in strokes) {
                    val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                    drawPath(path, ink, style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !blue, onClick = { blue = false }, label = { Text("Black") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = blue, onClick = { blue = true }, label = { Text("Blue") })
            Spacer(Modifier.weight(1f))
            TextButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear(); revision++ }) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keep = !keep }) {
            Checkbox(checked = keep, onCheckedChange = { keep = it })
            Text("Save for next time")
        }
        Spacer(Modifier.height(6.dp))
        Button(enabled = strokes.isNotEmpty(), onClick = { signatureBitmap(strokes, ink.toArgb())?.let { onUse(it, keep) } }, modifier = Modifier.fillMaxWidth()) { Text("Use signature") }
    }
}

/** Renders the strokes onto a transparent bitmap cropped to the ink, at twice the drawn size. */
private fun signatureBitmap(strokes: List<List<Offset>>, argb: Int): Bitmap? {
    val points = strokes.flatten()
    if (points.isEmpty()) return null
    val scale = 2f
    val margin = 12f
    val left = points.minOf { it.x } - margin
    val top = points.minOf { it.y } - margin
    val width = ((points.maxOf { it.x } + margin - left) * scale).toInt().coerceAtLeast(1)
    val height = ((points.maxOf { it.y } + margin - top) * scale).toInt().coerceAtLeast(1)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = 7f * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = android.graphics.Canvas(bitmap)
        for (stroke in strokes) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { i, p ->
                val x = (p.x - left) * scale
                val y = (p.y - top) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.size == 1) path.lineTo((stroke[0].x - left) * scale + 0.5f, (stroke[0].y - top) * scale)
            canvas.drawPath(path, paint)
        }
    }
}

// endregion
