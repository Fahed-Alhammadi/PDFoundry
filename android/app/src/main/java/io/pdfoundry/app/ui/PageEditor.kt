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
import androidx.compose.foundation.layout.requiredSize
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
import io.pdfoundry.app.data.PdfEngine
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import io.pdfoundry.app.data.ShapeKind
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
    onMarkText: (List<LineArea>) -> Unit = {},
    onShape: (ShapeKind, PagePoint, PagePoint, Int) -> Unit = { _, _, _, _ -> },
    onComment: (PagePoint, String) -> Unit = { _, _ -> }
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
    var widthFraction by remember { mutableFloatStateOf((mode as? EditMode.Place)?.width ?: 0.45f) }
    var boxSize by remember { mutableStateOf(Offset(1f, 1f)) }
    // A shape being dragged out, in pixels on the page; a comment's spot waiting for its text.
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }
    var commentAt by remember { mutableStateOf<Offset?>(null) }
    // Where the finger went down: drag gestures report their start only after the touch slop.
    var downAt by remember { mutableStateOf<Offset?>(null) }

    val title = when (mode) {
        is EditMode.Ink -> "Draw"
        is EditMode.Place -> "Place ${mode.label}"
        is EditMode.TextMark -> when (mode.mark) {
            TextMark.Highlight -> "Highlight"
            TextMark.Underline -> "Underline"
            TextMark.Strikethrough -> "Strikethrough"
        }
        is EditMode.Shape -> mode.kind.label
        EditMode.Comment -> "Comment"
        EditMode.Redact -> "Redact"
    }
    // Redact: boxes or text, and the two questions.
    var redactText by remember { mutableStateOf(false) }
    var redactStart by remember { mutableStateOf<Offset?>(null) }
    var redactEnd by remember { mutableStateOf<Offset?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    val redactWords by produceState<List<TextWord>?>(null, pageIndex, state.version, mode is EditMode.Redact && redactText) {
        value = if (mode is EditMode.Redact && redactText) state.words(pageIndex) else emptyList()
    }
    val leave: () -> Unit = if (mode is EditMode.Redact) ({ if (state.pendingCount > 0) askLeave = true else state.discardRedactions() }) else onCancel
    if (mode is EditMode.Redact) androidx.activity.compose.BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text("Page ${pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Filled.Close, closeLabel(mode)) } },
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
                        // Each shape is added when the finger lifts; Done leaves.
                        is EditMode.Shape -> TextButton(onClick = onCancel) { Text("Done") }
                        EditMode.Comment -> Unit
                        EditMode.Redact -> TextButton(enabled = state.pendingCount > 0, onClick = { confirmApply = true }) {
                            Text(if (state.pendingCount > 0) "Apply (${state.pendingCount})" else "Apply")
                        }
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
                        is EditMode.Shape -> Text("Drag on the page to draw. Draw as many as you like, then tap Done.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        EditMode.Comment -> Text("Tap where the comment goes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EditMode.Redact -> Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !redactText, onClick = { redactText = false }, label = { Text("Boxes") })
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = redactText, onClick = { redactText = true }, label = { Text("Text") })
                            Spacer(Modifier.weight(1f))
                            IconButton(enabled = pageIndex > 0, onClick = { state.showPage(pageIndex - 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                            Text("${pageIndex + 1} of ${state.pageCount}", style = MaterialTheme.typography.labelLarge)
                            IconButton(enabled = pageIndex < state.pageCount - 1, onClick = { state.showPage(pageIndex + 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
                        }
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
                    is EditMode.Shape -> {
                        val color = Color(mode.argb).copy(alpha = 1f)
                        val strokePx = PdfEngine.SHAPE_WIDTH * widthPx / pageSize.width
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(mode) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position } }.pointerInput(mode) {
                                detectDragGestures(
                                    onDragStart = { shapeStart = downAt ?: it; shapeEnd = it },
                                    onDragEnd = {
                                        val start = shapeStart
                                        val end = shapeEnd
                                        shapeStart = null
                                        shapeEnd = null
                                        if (start != null && end != null) {
                                            onShape(mode.kind, PagePoint(start.x / boxSize.x, start.y / boxSize.y), PagePoint(end.x / boxSize.x, end.y / boxSize.y), mode.argb)
                                        }
                                    },
                                    onDragCancel = { shapeStart = null; shapeEnd = null },
                                    onDrag = { change, _ -> shapeEnd = Offset(change.position.x.coerceIn(0f, widthPx), change.position.y.coerceIn(0f, heightPx)) }
                                )
                            }.semantics { contentDescription = "Page. Drag to draw a ${mode.kind.label.lowercase()}." }
                        ) {
                            val start = shapeStart ?: return@Canvas
                            val end = shapeEnd ?: return@Canvas
                            val topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                            val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y))
                            val stroke = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            when (mode.kind) {
                                ShapeKind.Rectangle -> drawRect(color, topLeft, size, style = stroke)
                                ShapeKind.Ellipse -> drawOval(color, topLeft, size, style = stroke)
                                ShapeKind.Line, ShapeKind.Arrow -> {
                                    drawLine(color, start, end, strokePx, cap = StrokeCap.Round)
                                    if (mode.kind == ShapeKind.Arrow) {
                                        val head = 10f * widthPx / pageSize.width
                                        val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
                                        for (side in listOf(-1, 1)) {
                                            val a = angle + side * Math.toRadians(30.0).toFloat()
                                            drawLine(color, end, Offset(end.x - head * kotlin.math.cos(a), end.y - head * kotlin.math.sin(a)), strokePx, cap = StrokeCap.Round)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EditMode.Redact -> {
                        val boxes = state.pending[pageIndex].orEmpty()
                        val red = Color(0xFFD32F2F)
                        if (redactText) {
                            redactWords?.takeIf { it.isNotEmpty() }?.let { found ->
                                TextSelectionLayer(found, aspect, TextMark.Highlight, android.graphics.Color.argb(90, 211, 47, 47)) { areas ->
                                    // A little larger than the words, so nothing of a glyph is left at the edges.
                                    areas.forEach { area ->
                                        val r = area.rect
                                        val padX = 0.15f * r.height() * pageSize.height / pageSize.width
                                        state.addRedaction(pageIndex, RectF(r.left - padX * 0.3f, r.top - r.height() * 0.08f, r.right + padX * 0.3f, r.bottom + r.height() * 0.08f))
                                    }
                                }
                            }
                        }
                        Canvas(
                            Modifier.fillMaxSize().then(
                                if (redactText) Modifier else Modifier
                                    .pointerInput(pageIndex) {
                                        detectTapGestures { tap ->
                                            // Tapping a pending box removes it.
                                            val x = tap.x / size.width
                                            val y = tap.y / size.height
                                            val hit = state.pending[pageIndex].orEmpty().indexOfLast { x in it.left..it.right && y in it.top..it.bottom }
                                            if (hit >= 0) state.removeRedaction(pageIndex, hit)
                                        }
                                    }
                                    .pointerInput(pageIndex) { awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position } }
                                    .pointerInput(pageIndex) {
                                        detectDragGestures(
                                            onDragStart = { redactStart = downAt ?: it; redactEnd = it },
                                            onDragEnd = {
                                                val a = redactStart
                                                val b = redactEnd
                                                redactStart = null
                                                redactEnd = null
                                                if (a != null && b != null) state.addRedaction(pageIndex, RectF(minOf(a.x, b.x) / size.width, minOf(a.y, b.y) / size.height, maxOf(a.x, b.x) / size.width, maxOf(a.y, b.y) / size.height))
                                            },
                                            onDragCancel = { redactStart = null; redactEnd = null },
                                            onDrag = { change, _ -> redactEnd = Offset(change.position.x.coerceIn(0f, widthPx), change.position.y.coerceIn(0f, heightPx)) }
                                        )
                                    }
                            ).semantics { contentDescription = if (redactText) "Page. Drag across words to mark them." else "Page. Drag to mark an area; tap a marked area to remove it." }
                        ) {
                            for (box in boxes) {
                                val topLeft = Offset(box.left * size.width, box.top * size.height)
                                val boxSize = androidx.compose.ui.geometry.Size(box.width() * size.width, box.height() * size.height)
                                drawRect(red.copy(alpha = 0.12f), topLeft, boxSize)
                                drawRect(red, topLeft, boxSize, style = Stroke(2.dp.toPx()))
                            }
                            val a = redactStart
                            val b = redactEnd
                            if (a != null && b != null) {
                                drawRect(red, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                            }
                        }
                    }
                    EditMode.Comment -> Box(
                        Modifier.fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { commentAt = it } }
                            .semantics { contentDescription = "Page. Tap where the comment goes." }
                    ) {
                        commentAt?.let { at ->
                            val density = LocalDensity.current
                            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Color(0xFF8A6A00), modifier = Modifier.offset { IntOffset((at.x - 12 * density.density).roundToInt(), (at.y - 12 * density.density).roundToInt()) }.size(24.dp))
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
                        }
                        // The handle sits on the item's bottom-right corner, outside the item's box, so a small
                        // item (a date, initials) never squeezes it out of round.
                        val handlePx = with(density) { ResizeHandle.toPx() }
                        Box(
                            Modifier.offset { IntOffset((left + itemWidth - handlePx / 2).roundToInt(), (top + itemHeight - handlePx / 2).roundToInt()) }
                                .requiredSize(ResizeHandle).clip(CircleShape).background(accent)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            widthFraction = (widthFraction + 2 * drag.x / widthPx).coerceIn(0.03f, 1.2f)
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

    if (confirmApply) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text(RedactApplyTitle) },
            text = { Text(RedactApplyMessage) },
            confirmButton = { TextButton(onClick = { confirmApply = false; state.applyRedactions() }) { Text("Apply", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Cancel") } }
        )
    }
    if (askLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(RedactLeaveTitle) },
            confirmButton = { TextButton(onClick = { askLeave = false; state.applyRedactions() }) { Text("Apply", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false; state.discardRedactions() }) { Text("Discard") }
                    TextButton(onClick = { askLeave = false }) { Text("Cancel") }
                }
            }
        )
    }

    commentAt?.let { at ->
        var text by remember(at) { mutableStateOf("") }
        CommentDialog(title = "Add comment", text = text, onText = { text = it }, onDismiss = { commentAt = null }, onDone = {
            commentAt = null
            onComment(PagePoint(at.x / boxSize.x, at.y / boxSize.y), text)
        })
    }
}

/** Resize handles: always this size and round, however small the item is. */
val ResizeHandle = 36.dp

/**
 * What the editor's × does, for TalkBack. Shapes and Redact keep what was done (each shape is saved as it
 * is drawn), so × closes the tool; the other tools drop what was not placed yet, so × cancels.
 */
fun closeLabel(mode: EditMode): String = when (mode) {
    is EditMode.Shape -> "Close Shapes"
    EditMode.Redact -> "Close Redact"
    else -> "Cancel"
}

const val RedactApplyTitle = "Apply Redactions?"
const val RedactApplyMessage = "Redacting removes everything under the boxes for good. Each redacted page becomes an image, so its text can no longer be selected or searched, and its form fields and marks are pressed into it."
const val RedactLeaveTitle = "Apply the redactions?"

/** Typing a comment: Done saves it (disabled while empty), Cancel leaves it. [onDelete] adds a Delete button. */
@Composable
fun CommentDialog(title: String, text: String, onText: (String) -> Unit, onDismiss: () -> Unit, onDone: () -> Unit, onDelete: (() -> Unit)? = null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = onText,
                label = { Text("Comment") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = onDone) { Text("Done") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
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

/** Signatures and initials: drawn on the same pad, kept in separate lists. */
enum class SignatureKind(val title: String, val newTitle: String, val use: String, val saved: String) {
    Signature("Signature", "New signature", "Use signature", "Saved signature"),
    Initials("Initials", "New initials", "Use initials", "Saved initials")
}

@Composable
private fun storeFor(kind: SignatureKind) = LocalContext.current.app.let { if (kind == SignatureKind.Initials) it.initials else it.signatures }

/** Pick a saved signature (or initials) or draw a new one. */
@Composable
fun SignaturePicker(kind: SignatureKind = SignatureKind.Signature, onPick: (Bitmap) -> Unit) {
    val store = storeFor(kind)
    var drawing by remember { mutableStateOf(store.signatures.isEmpty()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        if (drawing) {
            SignaturePad(kind, onUse = { image, keep ->
                if (keep) store.add(image)
                onPick(image)
            })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { drawing = true }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            SignatureList(kind, onPick = onPick, modifier = Modifier.height(300.dp))
        }
    }
}

/** Saved signatures (or initials). With `onPick`, tapping one chooses it. */
@Composable
fun SignatureList(kind: SignatureKind = SignatureKind.Signature, modifier: Modifier = Modifier, onPick: ((Bitmap) -> Unit)? = null) {
    val store = storeFor(kind)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(store.signatures, key = { it.file.name }) { signature ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(enabled = onPick != null, onClickLabel = kind.use) { onPick?.invoke(signature.image) }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) { Image(signature.image.asImageBitmap(), kind.saved, contentScale = ContentScale.Fit) }
                IconButton(onClick = { store.delete(signature) }) { Icon(Icons.Filled.Delete, "Delete ${kind.title.lowercase()}") }
            }
        }
    }
}

/** A pad for drawing a signature or initials with a finger or stylus. */
@Composable
fun SignaturePad(kind: SignatureKind = SignatureKind.Signature, onUse: (Bitmap, keep: Boolean) -> Unit) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var revision by remember { mutableIntStateOf(0) }
    var blue by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(true) }
    val ink = if (blue) Color(0xFF0D3399) else Color.Black

    Column {
        Text(kind.newTitle, style = MaterialTheme.typography.titleLarge)
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
                }.semantics { contentDescription = if (kind == SignatureKind.Initials) "Initials pad" else "Signature pad" }
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
        Button(enabled = strokes.isNotEmpty(), onClick = { signatureBitmap(strokes, ink.toArgb())?.let { onUse(it, keep) } }, modifier = Modifier.fillMaxWidth()) { Text(kind.use) }
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
