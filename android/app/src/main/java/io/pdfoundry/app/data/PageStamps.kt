package io.pdfoundry.app.data

import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

enum class NumberPosition(val label: String, val top: Boolean, val align: Int) {
    BottomCenter("Bottom centre", false, 0),
    BottomRight("Bottom right", false, 1),
    BottomLeft("Bottom left", false, -1),
    TopCenter("Top centre", true, 0),
    TopRight("Top right", true, 1),
    TopLeft("Top left", true, -1)
}

enum class NumberFormat(val label: String) { Plain("1"), Page("Page 1"), Of("1 of 12"), PageOf("Page 1 of 12") }

/** Page Numbers: where, how, the first number, and whether the first page goes without one. */
data class PageNumberStyle(
    val position: NumberPosition = NumberPosition.BottomCenter,
    val format: NumberFormat = NumberFormat.Plain,
    val startAt: Int = 1,
    val skipFirst: Boolean = false
) {
    /**
     * The text on page [index] of [count], or null for a skipped first page. With "Skip the first page",
     * numbering starts on page 2 with [startAt]; "of N" gives the last number used.
     */
    fun text(index: Int, count: Int): String? {
        if (skipFirst && index == 0) return null
        val number = startAt + index - (if (skipFirst) 1 else 0)
        val last = startAt + count - 1 - (if (skipFirst) 1 else 0)
        return when (format) {
            NumberFormat.Plain -> "$number"
            NumberFormat.Page -> "Page $number"
            NumberFormat.Of -> "$number of $last"
            NumberFormat.PageOf -> "Page $number of $last"
        }
    }
}

/** Watermark: the words, how strong, which way, and the colour (graphite or ember). */
data class WatermarkStyle(
    val text: String = "CONFIDENTIAL",
    val opacity: Float = 0.25f,
    val diagonal: Boolean = true,
    val argb: Int = MarkInk
)

/** The brand's ember, the watermark's second colour. */
const val Ember: Int = 0xFFE2511B.toInt()

/**
 * Page numbers and watermarks are added to each page's content (not as annotations), placed on the page
 * as the reader shows it: rotation and crop box respected.
 */
object PageStamps {
    const val NUMBER_SIZE = 10f
    const val MARGIN = 36f

    fun addPageNumbers(file: File, style: PageNumberStyle) = PdfEngine.edit(file) { document -> addPageNumbers(document, style) }

    fun addPageNumbers(document: PDDocument, style: PageNumberStyle) {
        val font = PDType1Font.HELVETICA
        val count = document.numberOfPages
        for (index in 0 until count) {
            val text = style.text(index, count) ?: continue
            overlay(document, index) { stream, width, height ->
                val textWidth = font.getStringWidth(text) / 1000f * NUMBER_SIZE
                val x = when (style.position.align) {
                    -1 -> MARGIN
                    1 -> width - MARGIN - textWidth
                    else -> (width - textWidth) / 2
                }
                // The baseline 0.5 inch from the bottom edge; at the top, the cap height sits 0.5 inch from it.
                val y = if (style.position.top) height - MARGIN - font.fontDescriptor.capHeight / 1000f * NUMBER_SIZE else MARGIN
                stream.setNonStrokingColor(Color.red(MarkInk) / 255f, Color.green(MarkInk) / 255f, Color.blue(MarkInk) / 255f)
                stream.beginText()
                stream.setFont(font, NUMBER_SIZE)
                stream.newLineAtOffset(x, y)
                stream.showText(text)
                stream.endText()
            }
        }
    }

    fun addWatermark(file: File, style: WatermarkStyle) = PdfEngine.edit(file) { document -> addWatermark(document, style) }

    /** True when Helvetica (WinAnsi) can write [text]; otherwise it is drawn by Android as an image. */
    fun encodable(text: String): Boolean = runCatching { PDType1Font.HELVETICA.encode(text); true }.getOrDefault(false)

    fun addWatermark(document: PDDocument, style: WatermarkStyle) {
        val text = style.text.trim()
        if (text.isEmpty()) throw PdfException("Type the watermark's text.")
        val font = PDType1Font.HELVETICA_BOLD
        val asText = runCatching { font.encode(text); true }.getOrDefault(false)
        // Non-Latin text (Arabic, say) is shaped by Android and placed as one image, shared by every page.
        val picture = if (asText) null else LosslessFactory.createFromImage(document, PdfEngine.textBitmap(text, style.argb or 0xFF000000.toInt()))
        for (index in 0 until document.numberOfPages) {
            overlay(document, index) { stream, width, height ->
                stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = style.opacity.coerceIn(0.05f, 1f)
                    strokingAlphaConstant = style.opacity.coerceIn(0.05f, 1f)
                })
                val angle = if (style.diagonal) kotlin.math.atan2(height, width) else 0f
                // About 60% of the page's width across, centred.
                val across = width * 0.6f
                stream.transform(Matrix.getTranslateInstance(width / 2, height / 2))
                stream.transform(Matrix.getRotateInstance(angle.toDouble(), 0f, 0f))
                if (picture == null) {
                    val size = across / (font.getStringWidth(text) / 1000f)
                    val capHeight = font.fontDescriptor.capHeight / 1000f * size
                    stream.setNonStrokingColor(Color.red(style.argb) / 255f, Color.green(style.argb) / 255f, Color.blue(style.argb) / 255f)
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(-across / 2, -capHeight / 2)
                    stream.showText(text)
                    stream.endText()
                } else {
                    val h = across * picture.height / picture.width
                    stream.drawImage(picture, -across / 2, -h / 2, across, h)
                }
            }
        }
    }

    /** A content stream on top of the page in display points (origin bottom-left of the displayed page). */
    private fun overlay(document: PDDocument, pageIndex: Int, draw: (PDPageContentStream, Float, Float) -> Unit) {
        val page = document.getPage(pageIndex)
        val frame = PageFrame(page)
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.transform(frame.toPage)
            draw(stream, frame.width, frame.height)
        }
    }
}
