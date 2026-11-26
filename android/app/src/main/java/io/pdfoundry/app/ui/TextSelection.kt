package io.pdfoundry.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.pdfoundry.app.data.Edge
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.data.TextWord
import kotlin.math.max
import kotlin.math.min

/** Part of one line of selected text, and the side of it below the text (where an underline goes). */
data class LineArea(val rect: RectF, val lower: Edge)

/**
 * Selecting words on one page for a text mark. Drag across the text (or tap a word): the words from
 * where the finger went down to where it is now, in reading order, are tinted, and lifting the finger
 * hands them, joined into one area per line, to [onSelect]. Positions are page fractions, like
 * [TextWord.rect]; [aspect] is the displayed page's width divided by its height.
 */
@Composable
fun TextSelectionLayer(words: List<TextWord>, aspect: Float, mark: TextMark, argb: Int, onSelect: (List<LineArea>) -> Unit) {
    var anchor by remember(words) { mutableStateOf<Int?>(null) }
    var focus by remember(words) { mutableStateOf<Int?>(null) }
    val selected = selectedWords(words, anchor, focus)
    val tint = Color(argb)
    // detectDragGestures reports where the finger is once it has moved past the touch slop, not where it went
    // down, so the word under the first touch is remembered here; otherwise a drag would skip the first word.
    var downAt by remember(words) { mutableStateOf<Offset?>(null) }

    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(words) {
                awaitEachGesture {
                    downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                }
            }
            .pointerInput(words) {
                detectTapGestures { point ->
                    // A tap must land on a word (or right next to it): a tap in the margin marks nothing.
                    val index = wordAt(words, point.x / size.width, point.y / size.height, aspect) ?: return@detectTapGestures
                    onSelect(lineAreas(listOf(words[index]), aspect))
                }
            }
            .pointerInput(words) {
                detectDragGestures(
                    onDragStart = { slopPosition ->
                        // A drag starts on (or beside) the word first touched; after that it follows the finger to the nearest word.
                        val start = downAt ?: slopPosition
                        anchor = wordAt(words, start.x / size.width, start.y / size.height, aspect, slop = 1f)
                        focus = anchor
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (anchor != null) focus = nearestWord(words, change.position.x / size.width, change.position.y / size.height, aspect) ?: focus
                    },
                    onDragEnd = {
                        val chosen = selectedWords(words, anchor, focus)
                        anchor = null
                        focus = null
                        if (chosen.isNotEmpty()) onSelect(lineAreas(chosen, aspect))
                    },
                    onDragCancel = { anchor = null; focus = null }
                )
            }
            .semantics { contentDescription = "Page text. Drag across words to select them." }
    ) {
        for (area in lineAreas(selected, aspect)) {
            val rect = area.rect
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

/** Lines run across the screen unless the text's lower side is to its left or right. */
private val TextWord.sideways: Boolean get() = lower == Edge.Left || lower == Edge.Right

/** A rect's size across its line (the text's height), in units where the page is 1 tall and [aspect] wide. */
private fun thickness(rect: RectF, sideways: Boolean, aspect: Float) = if (sideways) rect.width() * aspect else rect.height()

/**
 * The word under a point, allowing [slop] times the word's text height around it; null when the point
 * is in a margin or between lines. For taps, and for where a drag starts.
 */
fun wordAt(words: List<TextWord>, x: Float, y: Float, aspect: Float, slop: Float = 0.5f): Int? {
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    words.forEachIndexed { index, word ->
        val rect = word.rect
        val margin = thickness(rect, word.sideways, aspect) * slop
        // The margin is in page-height units: across the page it is divided by the aspect.
        val dx = distance(x, rect.left, rect.right) * aspect
        val dy = distance(y, rect.top, rect.bottom)
        if (dx <= margin && dy <= margin) {
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
    }
    return best
}

/**
 * The nearest word to a point, however far: for following a drag. Being off a word's line counts nine
 * times more than being off along it, so a drag through a margin stays on its line; on a sideways page
 * lines run down the screen, so "off the line" is sideways.
 */
fun nearestWord(words: List<TextWord>, x: Float, y: Float, aspect: Float): Int? {
    if (words.isEmpty()) return null
    wordAt(words, x, y, aspect, slop = 0f)?.let { return it }
    return words.indices.minByOrNull { index ->
        val word = words[index]
        val dx = distance(x, word.rect.left, word.rect.right) * aspect
        val dy = distance(y, word.rect.top, word.rect.bottom)
        if (word.sideways) 9f * dx * dx + dy * dy else dx * dx + 9f * dy * dy
    }
}

private fun distance(value: Float, low: Float, high: Float) = when {
    value < low -> low - value
    value > high -> value - high
    else -> 0f
}

/**
 * Joins neighbouring words on the same line into one area, so a highlight covers the spaces between
 * them as on iOS. Words are joined only when the space between them is at most a little more than the
 * line's height: never across a column gutter.
 */
fun lineAreas(words: List<TextWord>, aspect: Float): List<LineArea> {
    val areas = mutableListOf<LineArea>()
    for (word in words) {
        val last = areas.lastOrNull()
        if (last != null && last.lower == word.lower && sameLine(last.rect, word.rect, word.sideways, aspect)) {
            last.rect.union(word.rect)
        } else {
            areas += LineArea(RectF(word.rect), word.lower)
        }
    }
    return areas
}

private fun overlap(aLow: Float, aHigh: Float, bLow: Float, bHigh: Float): Float {
    val shared = min(aHigh, bHigh) - max(aLow, bLow)
    val smaller = min(aHigh - aLow, bHigh - bLow)
    return if (smaller <= 0f) 0f else shared / smaller
}

private fun sameLine(a: RectF, b: RectF, sideways: Boolean, aspect: Float): Boolean {
    val lineHeight = max(thickness(a, sideways, aspect), thickness(b, sideways, aspect))
    return if (!sideways) {
        val gap = max(b.left - a.right, a.left - b.right) * aspect
        overlap(a.top, a.bottom, b.top, b.bottom) > 0.5f && gap <= lineHeight * 1.2f
    } else {
        val gap = max(b.top - a.bottom, a.top - b.bottom)
        overlap(a.left, a.right, b.left, b.right) > 0.5f && gap <= lineHeight * 1.2f
    }
}
