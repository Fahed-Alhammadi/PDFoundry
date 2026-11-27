package io.pdfoundry.app.ui

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.data.MarkInfo
import io.pdfoundry.app.data.MarkKind
import kotlin.math.roundToInt

/** How a mark is named to the user (and to TalkBack). */
fun MarkKind.label(): String = when (this) {
    MarkKind.Highlight -> "Highlight"
    MarkKind.Underline -> "Underline"
    MarkKind.Strikethrough -> "Strikethrough"
    MarkKind.Ink -> "Drawing"
    MarkKind.Stamp -> "Stamp"
    MarkKind.FreeText -> "Text"
    MarkKind.Rectangle -> "Rectangle"
    MarkKind.Ellipse -> "Ellipse"
    MarkKind.Line -> "Line"
    MarkKind.Arrow -> "Arrow"
    MarkKind.Comment -> "Comment"
    MarkKind.Other -> "Mark"
}

/**
 * Lets the marks on one page be tapped to select them. A selected mark gets a box with corner handles
 * (when it can be moved) and a small menu: Delete, and Open for a comment. Dragging inside the box moves
 * it, a corner resizes it (stamps keep their shape); the change is applied when the finger lifts.
 * Tapping anywhere else on the page deselects. Only the marks themselves take taps, so form fields and
 * scrolling work as before.
 */
@Composable
fun MarkLayer(
    marks: List<MarkInfo>,
    selected: MarkInfo?,
    enabled: Boolean,
    onSelect: (MarkInfo) -> Unit,
    onDeselect: () -> Unit,
    onMove: (RectF) -> Unit,
    onDelete: () -> Unit,
    onOpen: (MarkInfo) -> Unit
) {
    if (!enabled || (marks.isEmpty() && selected == null)) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val minTarget = with(density) { 28.dp.toPx() }
        if (selected != null) {
            Box(Modifier.fillMaxSize().pointerInput(selected.id) { detectTapGestures { onDeselect() } })
        }
        for (mark in marks) {
            if (mark.id == selected?.id) continue
            // At least a finger's width, centred on the mark, so thin lines can be tapped.
            val width = maxOf(mark.rect.width() * w, minTarget)
            val height = maxOf(mark.rect.height() * h, minTarget)
            val left = mark.rect.centerX() * w - width / 2
            val top = mark.rect.centerY() * h - height / 2
            Box(
                Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Select ${mark.kind.label().lowercase()}") {
                        onSelect(mark)
                        if (mark.kind == MarkKind.Comment) onOpen(mark)
                    }
                    .semantics { contentDescription = mark.kind.label() }
            )
        }
        if (selected != null) SelectedMark(selected, w, h, onMove, onDelete, onOpen)
    }
}

internal enum class Corner { TopLeft, TopRight, BottomLeft, BottomRight }

@Composable
private fun SelectedMark(mark: MarkInfo, w: Float, h: Float, onMove: (RectF) -> Unit, onDelete: () -> Unit, onOpen: (MarkInfo) -> Unit) {
    val density = LocalDensity.current
    // The rect while a finger moves it; applied when the finger lifts.
    var live by remember(mark) { mutableStateOf(RectF(mark.rect)) }
    val accent = MaterialTheme.colorScheme.primary
    val keepShape = mark.kind == MarkKind.Stamp || mark.kind == MarkKind.FreeText
    val resizable = mark.movable && mark.kind != MarkKind.Comment
    val left = live.left * w
    val top = live.top * h
    val width = live.width() * w
    val height = live.height() * h

    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .border(2.dp, accent)
            .then(
                if (mark.movable) Modifier.pointerInput(mark) {
                    detectDragGestures(
                        onDragEnd = { onMove(RectF(live)) },
                        onDrag = { change, drag ->
                            change.consume()
                            val dx = (drag.x / w).coerceIn(-live.left, 1f - live.right)
                            val dy = (drag.y / h).coerceIn(-live.top, 1f - live.bottom)
                            live = RectF(live.left + dx, live.top + dy, live.right + dx, live.bottom + dy)
                        }
                    )
                } else Modifier
            )
            .semantics { contentDescription = "Selected ${mark.kind.label().lowercase()}" + if (mark.movable) ". Drag to move." else "" }
    )
    if (resizable) {
        val handle = 26.dp
        val handlePx = with(density) { handle.toPx() }
        for (corner in Corner.entries) {
            val x = if (corner == Corner.TopLeft || corner == Corner.BottomLeft) left else left + width
            val y = if (corner == Corner.TopLeft || corner == Corner.TopRight) top else top + height
            Box(
                Modifier.offset { IntOffset((x - handlePx / 2).roundToInt(), (y - handlePx / 2).roundToInt()) }
                    .size(handle).clip(CircleShape).background(Color.White).border(3.dp, accent, CircleShape)
                    .pointerInput(mark, corner) {
                        detectDragGestures(
                            onDragEnd = { onMove(RectF(live)) },
                            onDrag = { change, drag ->
                                change.consume()
                                live = resized(live, corner, drag.x / w, drag.y / h, keepShape)
                            }
                        )
                    }
                    .semantics { contentDescription = "Resize" }
            )
        }
    }
    // The menu sits above the mark, or below it near the top of the page.
    val menuHeight = with(density) { 48.dp.toPx() }
    val menuY = if (top > menuHeight + 8f) top - menuHeight - with(density) { 14.dp.toPx() } else top + height + with(density) { 14.dp.toPx() }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.offset { IntOffset(left.roundToInt().coerceAtLeast(0), menuY.roundToInt()) }
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mark.kind == MarkKind.Comment) {
                TextButton(onClick = { onOpen(mark) }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                    Text("Open", Modifier.padding(start = 6.dp))
                }
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text("Delete", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * [rect] with [corner] dragged by ([dx], [dy]) (page fractions). With [keepShape], the rect keeps its
 * proportions (the larger of the two movements wins).
 * Never smaller than 2% of the page, never off the page.
 */
internal fun resized(rect: RectF, corner: Corner, dx: Float, dy: Float, keepShape: Boolean): RectF {
    val min = 0.02f
    var l = rect.left; var t = rect.top; var r = rect.right; var b = rect.bottom
    when (corner) {
        Corner.TopLeft -> { l += dx; t += dy }
        Corner.TopRight -> { r += dx; t += dy }
        Corner.BottomLeft -> { l += dx; b += dy }
        Corner.BottomRight -> { r += dx; b += dy }
    }
    if (keepShape && rect.width() > 0f && rect.height() > 0f) {
        // Scale both sides by the same factor, anchored at the opposite corner.
        val scale = maxOf((r - l) / rect.width(), (b - t) / rect.height()).coerceAtLeast(min / minOf(rect.width(), rect.height()))
        val nw = rect.width() * scale
        val nh = rect.height() * scale
        when (corner) {
            Corner.TopLeft -> { l = rect.right - nw; t = rect.bottom - nh; r = rect.right; b = rect.bottom }
            Corner.TopRight -> { l = rect.left; t = rect.bottom - nh; r = rect.left + nw; b = rect.bottom }
            Corner.BottomLeft -> { l = rect.right - nw; t = rect.top; r = rect.right; b = rect.top + nh }
            Corner.BottomRight -> { l = rect.left; t = rect.top; r = rect.left + nw; b = rect.top + nh }
        }
        if (l < 0f || t < 0f || r > 1f || b > 1f) return rect
        return RectF(l, t, r, b)
    }
    if (r - l < min) { if (corner == Corner.TopLeft || corner == Corner.BottomLeft) l = r - min else r = l + min }
    if (b - t < min) { if (corner == Corner.TopLeft || corner == Corner.TopRight) t = b - min else b = t + min }
    return RectF(l.coerceAtLeast(0f), t.coerceAtLeast(0f), r.coerceAtMost(1f), b.coerceAtMost(1f))
}
