package io.pdfoundry.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.data.TextWord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Selecting words on one page for a text mark. Drag across the text (or tap a word): the words from
 * where the finger went down to where it is now, in reading order, are tinted, and lifting the finger
 * hands their rects (one per line) to [onSelect]. Positions are page fractions, like [TextWord.rect].
 */
@Composable
fun TextSelectionLayer(words: List<TextWord>, mark: TextMark, argb: Int, onSelect: (List<RectF>) -> Unit) {
    var anchor by remember(words) { mutableStateOf<Int?>(null) }
    var focus by remember(words) { mutableStateOf<Int?>(null) }
    val selected = selectedWords(words, anchor, focus)
    val tint = Color(argb)

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(words) {
                detectTapGestures { point ->
                    val index = nearestWord(words, point.x / size.width, point.y / size.height) ?: return@detectTapGestures
                    onSelect(lineRects(listOf(words[index])))
                }
            }
            .pointerInput(words) {
                detectDragGestures(
                    onDragStart = { start ->
                        anchor = nearestWord(words, start.x / size.width, start.y / size.height)
                        focus = anchor
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        focus = nearestWord(words, change.position.x / size.width, change.position.y / size.height) ?: focus
                    },
                    onDragEnd = {
                        val chosen = selectedWords(words, anchor, focus)
                        anchor = null
                        focus = null
                        if (chosen.isNotEmpty()) onSelect(lineRects(chosen))
                    },
                    onDragCancel = { anchor = null; focus = null }
                )
            }
            .semantics { contentDescription = "Page text. Drag across words to select them." }
    ) {
        for (rect in lineRects(selected)) {
            val topLeft = Offset(rect.left * size.width, rect.top * size.height)
            val box = Size(rect.width() * size.width, rect.height() * size.height)
            when (mark) {
                TextMark.Highlight -> drawRect(tint.copy(alpha = max(tint.alpha, 0.35f)), topLeft, box, blendMode = BlendMode.Multiply)
                // Underline and strikethrough: a plain selection tint; the line is drawn on lift.
                else -> drawRect(Color(0x552F7CF6), topLeft, box)
            }
        }
    }
}

/** The words between two indices, inclusive, in reading order. */
fun selectedWords(words: List<TextWord>, anchor: Int?, focus: Int?): List<TextWord> {
    if (anchor == null || focus == null || words.isEmpty()) return emptyList()
    val from = min(anchor, focus).coerceIn(0, words.lastIndex)
    val to = max(anchor, focus).coerceIn(0, words.lastIndex)
    return words.subList(from, to + 1)
}

/** The word under a point, or failing that the nearest one: first by line, then along the line. */
fun nearestWord(words: List<TextWord>, x: Float, y: Float): Int? {
    if (words.isEmpty()) return null
    words.indexOfFirst { it.rect.contains(x, y) }.takeIf { it >= 0 }?.let { return it }
    fun distance(rect: RectF, value: Float, low: Float, high: Float) = when {
        value < low -> low - value
        value > high -> value - high
        else -> 0f
    }
    return words.indices.minByOrNull { index ->
        val rect = words[index].rect
        val dx = distance(rect, x, rect.left, rect.right)
        val dy = distance(rect, y, rect.top, rect.bottom)
        // Being off the line counts more than being off along it, so a drag in a margin stays on its line.
        dx * dx + 9f * dy * dy
    }
}

/**
 * Joins neighbouring words on the same line into one rect, so a highlight covers the spaces between
 * them as on iOS. Works for lines running across (normal pages) and down (pages turned sideways).
 */
fun lineRects(words: List<TextWord>): List<RectF> {
    val lines = mutableListOf<RectF>()
    for (word in words) {
        val last = lines.lastOrNull()
        if (last != null && sameLine(last, word.rect)) last.union(word.rect) else lines += RectF(word.rect)
    }
    return lines
}

private fun overlap(aLow: Float, aHigh: Float, bLow: Float, bHigh: Float): Float {
    val shared = min(aHigh, bHigh) - max(aLow, bLow)
    val smaller = min(aHigh - aLow, bHigh - bLow)
    return if (smaller <= 0f) 0f else shared / smaller
}

private fun sameLine(a: RectF, b: RectF): Boolean {
    val vertical = overlap(a.top, a.bottom, b.top, b.bottom)
    val horizontal = overlap(a.left, a.right, b.left, b.right)
    // Across: they share the line's height and sit side by side, close together.
    if (vertical > 0.5f && horizontal < 0.5f) return abs(min(abs(b.left - a.right), abs(a.left - b.right))) < a.height() * 3
    // Down (a sideways page): they share the line's width and sit one above the other.
    if (horizontal > 0.5f && vertical < 0.5f) return abs(min(abs(b.top - a.bottom), abs(a.top - b.bottom))) < a.width() * 3
    return false
}
