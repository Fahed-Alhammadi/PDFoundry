package io.pdfoundry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException

/** A point on a page as fractions of the displayed page: (0, 0) is the top-left corner, (1, 1) the bottom-right. */
data class PagePoint(val x: Float, val y: Float)

/** One freehand stroke. `width` is a fraction of the displayed page width. */
data class InkStroke(val points: List<PagePoint>, val argb: Int, val width: Float, val highlighter: Boolean)

data class SearchHit(val page: Int, val snippet: String)

/** One word on a page. `rect` is in fractions of the displayed page from the top-left (rotation and crop applied). */
data class TextWord(val text: String, val rect: RectF)

/** A mark drawn over selected text. */
enum class TextMark { Highlight, Underline, Strikethrough }

class PdfException(message: String) : IOException(message)

/**
 * Every change to a PDF goes through here. Positions are given the way the user sees the page
 * (fractions from the top-left corner); the engine converts them to PDF space, including rotated pages.
 * All functions block and belong on a background dispatcher.
 */
object PdfEngine {
    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)

    private fun memory() = MemoryUsageSetting.setupTempFileOnly()

    /** Loads `file`, applies `block`, and replaces the file only after the new copy is written in full. */
    private fun edit(file: File, block: (PDDocument) -> Unit) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            PDDocument.load(file, memory()).use { document ->
                if (document.isEncrypted) throw PdfException("Password-protected documents can't be changed.")
                block(document)
                document.save(temp)
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    // region Whole documents

    fun merge(sources: List<File>, destination: File) {
        if (sources.isEmpty()) throw PdfException("There are no pages to save.")
        try {
            PDFMergerUtility().apply {
                sources.forEach { addSource(it) }
                destinationFileName = destination.path
                mergeDocuments(memory())
            }
        } catch (e: InvalidPasswordException) {
            destination.delete()
            throw PdfException("One of the documents is password protected. Remove it from the merge.")
        } catch (e: IOException) {
            destination.delete()
            throw PdfException("One of the documents could not be read. It may be damaged.")
        }
    }

    fun extract(source: File, pages: List<Int>, destination: File) {
        PDDocument.load(source, memory()).use { document ->
            PDDocument().use { result ->
                pages.sorted().filter { it in 0 until document.numberOfPages }.forEach { result.importPage(document.getPage(it)) }
                if (result.numberOfPages == 0) throw PdfException("There are no pages to save.")
                // Saved while the source is still open: imported pages read their content from it.
                result.save(destination)
            }
        }
    }

    /** One page per image, sized to the image with A4 width. */
    fun imagesToPdf(images: List<Bitmap>, destination: File) {
        if (images.isEmpty()) throw PdfException("There are no pages to save.")
        PDDocument().use { document ->
            for (image in images) {
                val width = PDRectangle.A4.width
                val height = width * image.height / image.width
                val page = PDPage(PDRectangle(width, height))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(JPEGFactory.createFromImage(document, image, 0.85f), 0f, 0f, width, height)
                }
            }
            document.save(destination)
        }
    }

    /** Writes an unprotected copy of a password-protected file, for viewing. Returns false if the password is wrong. */
    fun unlock(source: File, password: String, destination: File): Boolean = try {
        PDDocument.load(source, password, memory()).use { document ->
            document.isAllSecurityToBeRemoved = true
            document.save(destination)
        }
        true
    } catch (e: InvalidPasswordException) {
        false
    }

    fun search(file: File, query: String): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        PDDocument.load(file, memory()).use { document ->
            val stripper = PDFTextStripper()
            for (index in 0 until document.numberOfPages) {
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                val text = stripper.getText(document).replace(Regex("\\s+"), " ")
                var from = text.indexOf(needle, ignoreCase = true)
                while (from >= 0 && hits.size < 200) {
                    val start = (from - 40).coerceAtLeast(0)
                    val end = (from + needle.length + 60).coerceAtMost(text.length)
                    hits += SearchHit(index, (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else ""))
                    from = text.indexOf(needle, from + needle.length, ignoreCase = true)
                }
            }
        }
        return hits
    }

    // endregion
    // region Pages

    fun rotate(file: File, pages: Collection<Int>, degrees: Int) = edit(file) { document ->
        for (index in pages) {
            val page = document.getPage(index)
            page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
        }
    }

    fun delete(file: File, pages: Collection<Int>) = edit(file) { document ->
        if (pages.size >= document.numberOfPages) throw PdfException("A document needs at least one page.")
        pages.sortedDescending().forEach { document.removePage(it) }
    }

    fun duplicate(file: File, pages: Collection<Int>) = edit(file) { document ->
        for (index in pages.sortedDescending()) {
            val original = document.getPage(index)
            val copy = document.importPage(original)
            // importPage appends; move the copy to sit right after its original.
            document.pages.remove(copy)
            document.pages.insertAfter(copy, original)
        }
    }

    fun move(file: File, from: Int, to: Int) = edit(file) { document ->
        if (from == to || from !in 0 until document.numberOfPages || to !in 0 until document.numberOfPages) return@edit
        val page = document.getPage(from)
        val target = document.getPage(to)
        document.pages.remove(page)
        if (to > from) document.pages.insertAfter(page, target) else document.pages.insertBefore(page, target)
    }

    fun addBlankPage(file: File, after: Int) = edit(file) { document ->
        val neighbor = document.getPage(after.coerceIn(0, document.numberOfPages - 1))
        document.pages.insertAfter(PDPage(neighbor.mediaBox), neighbor)
    }

    // endregion
    // region Drawing onto a page

    /**
     * Opens a content stream whose coordinates match what the user sees: origin at the bottom-left
     * of the displayed page, in points, whatever the page's rotation or crop box.
     */
    private fun PDDocument.overlay(pageIndex: Int, draw: (PDPageContentStream, width: Float, height: Float) -> Unit) {
        val page = getPage(pageIndex)
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val sideways = rotation == 90 || rotation == 270
        val width = if (sideways) box.height else box.width
        val height = if (sideways) box.width else box.height
        val toPage = when (rotation) {
            90 -> Matrix(0f, 1f, -1f, 0f, box.lowerLeftX + box.width, box.lowerLeftY)
            180 -> Matrix(-1f, 0f, 0f, -1f, box.lowerLeftX + box.width, box.lowerLeftY + box.height)
            270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, box.lowerLeftY + box.height)
            else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
        }
        PDPageContentStream(this, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.transform(toPage)
            draw(stream, width, height)
        }
    }

    /** Draws an image (a signature, or typed text rendered to a bitmap). `rect` is in page fractions. */
    fun stamp(file: File, pageIndex: Int, image: Bitmap, rect: RectF) = edit(file) { document ->
        document.overlay(pageIndex) { stream, width, height ->
            val picture = LosslessFactory.createFromImage(document, image)
            stream.drawImage(picture, rect.left * width, height - rect.bottom * height, rect.width() * width, rect.height() * height)
        }
    }

    fun ink(file: File, pageIndex: Int, strokes: List<InkStroke>) = edit(file) { document ->
        document.overlay(pageIndex) { stream, width, height ->
            for (stroke in strokes) {
                if (stroke.points.isEmpty()) continue
                stream.saveGraphicsState()
                if (stroke.highlighter) {
                    stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                        strokingAlphaConstant = 0.45f
                        blendMode = BlendMode.MULTIPLY
                    })
                }
                stream.setStrokingColor(Color.red(stroke.argb) / 255f, Color.green(stroke.argb) / 255f, Color.blue(stroke.argb) / 255f)
                stream.setLineWidth(stroke.width * width)
                stream.setLineCapStyle(if (stroke.highlighter) 0 else 1)
                stream.setLineJoinStyle(1)
                val first = stroke.points.first()
                stream.moveTo(first.x * width, height - first.y * height)
                // A tap leaves a single point; draw a dot-sized segment so it still shows.
                val rest = if (stroke.points.size == 1) listOf(PagePoint(first.x + 0.0005f, first.y)) else stroke.points.drop(1)
                rest.forEach { stream.lineTo(it.x * width, height - it.y * height) }
                stream.stroke()
                stream.restoreGraphicsState()
            }
        }
    }

    // endregion
    // region Text markup

    /**
     * The words on a page, in reading order, with where each one is on the displayed page.
     * A page without text (a scan) has none.
     */
    fun words(file: File, pageIndex: Int): List<TextWord> = PDDocument.load(file, memory()).use { document ->
        if (pageIndex !in 0 until document.numberOfPages) return@use emptyList()
        val page = document.getPage(pageIndex)
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val found = mutableListOf<TextWord>()
        val stripper = object : PDFTextStripper() {
            // Called with a run of text (often a whole line); split into words at spaces and gaps.
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                var current = mutableListOf<TextPosition>()
                fun flush() {
                    if (current.isNotEmpty()) word(current)?.let { found += it }
                    current = mutableListOf()
                }
                for (position in positions) {
                    val glyph = position.unicode.orEmpty()
                    if (glyph.isBlank()) {
                        flush()
                        continue
                    }
                    val previous = current.lastOrNull()
                    if (previous != null) {
                        val gap = position.xDirAdj - (previous.xDirAdj + previous.widthDirAdj)
                        val size = previous.fontSizeInPt.takeIf { it > 0f } ?: previous.heightDir
                        val newLine = kotlin.math.abs(position.yDirAdj - previous.yDirAdj) > size * 0.5f
                        if (newLine || gap > size * 0.18f || gap < -size) flush()
                    }
                    current += position
                }
                flush()
            }

            private fun word(positions: List<TextPosition>): TextWord? {
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (position in positions) {
                    // Direction-adjusted values are in the unrotated crop box, measured from its top-left.
                    val size = position.fontSizeInPt.takeIf { it > 0f } ?: position.heightDir
                    val baseline = position.yDirAdj
                    left = minOf(left, position.xDirAdj)
                    right = maxOf(right, position.xDirAdj + position.widthDirAdj)
                    top = minOf(top, baseline - maxOf(position.heightDir, size * 0.78f))
                    bottom = maxOf(bottom, baseline + size * 0.22f)
                }
                if (right <= left || bottom <= top) return null
                return TextWord(positions.joinToString("") { it.unicode.orEmpty() }, displayed(RectF(left, top, right, bottom), box.width, box.height, rotation))
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(document)
        found
    }

    /** A rect in the unrotated crop box (points from its top-left) as fractions of the page as displayed. */
    private fun displayed(rect: RectF, width: Float, height: Float, rotation: Int): RectF {
        fun point(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
            90 -> (height - v) / height to u / width
            180 -> (width - u) / width to (height - v) / height
            270 -> v / height to (width - u) / width
            else -> u / width to v / height
        }
        val (x1, y1) = point(rect.left, rect.top)
        val (x2, y2) = point(rect.right, rect.bottom)
        return RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /**
     * Marks text on a page: a highlight fills each rect with a multiply blend (so the text stays
     * readable); underline and strikethrough draw a line across it. `rects` are page fractions.
     */
    fun markText(file: File, pageIndex: Int, rects: List<RectF>, mark: TextMark, argb: Int) = edit(file) { document ->
        if (rects.isEmpty()) return@edit
        // On a turned page the text runs down (90°), upside down (180°) or up (270°) the display:
        // lines follow the text, on its lower side for an underline.
        val rotation = ((document.getPage(pageIndex).rotation % 360) + 360) % 360
        document.overlay(pageIndex) { stream, width, height ->
            stream.saveGraphicsState()
            val alpha = Color.alpha(argb) / 255f
            stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                if (mark == TextMark.Highlight) {
                    nonStrokingAlphaConstant = alpha
                    blendMode = BlendMode.MULTIPLY
                } else {
                    strokingAlphaConstant = alpha
                }
            })
            val red = Color.red(argb) / 255f
            val green = Color.green(argb) / 255f
            val blue = Color.blue(argb) / 255f
            for (rect in rects) {
                val left = rect.left * width
                val right = rect.right * width
                val top = height - rect.top * height
                val rectHeight = rect.height() * height
                when (mark) {
                    TextMark.Highlight -> {
                        stream.setNonStrokingColor(red, green, blue)
                        stream.addRect(left, top - rectHeight, right - left, rectHeight)
                        stream.fill()
                    }
                    TextMark.Underline, TextMark.Strikethrough -> {
                        val along = if (mark == TextMark.Underline) 0.92f else 0.5f
                        val bottom = top - rectHeight
                        val rectWidth = right - left
                        stream.setStrokingColor(red, green, blue)
                        stream.setLineCapStyle(0)
                        // In display points from the bottom-left: across for 0/180°, up and down for 90/270°.
                        when (rotation) {
                            90 -> {
                                val x = right - rectWidth * along
                                stream.setLineWidth((rectWidth * 0.07f).coerceAtLeast(0.5f))
                                stream.moveTo(x, bottom)
                                stream.lineTo(x, top)
                            }
                            270 -> {
                                val x = left + rectWidth * along
                                stream.setLineWidth((rectWidth * 0.07f).coerceAtLeast(0.5f))
                                stream.moveTo(x, bottom)
                                stream.lineTo(x, top)
                            }
                            180 -> {
                                val y = bottom + rectHeight * along
                                stream.setLineWidth((rectHeight * 0.07f).coerceAtLeast(0.5f))
                                stream.moveTo(left, y)
                                stream.lineTo(right, y)
                            }
                            else -> {
                                val y = top - rectHeight * along
                                stream.setLineWidth((rectHeight * 0.07f).coerceAtLeast(0.5f))
                                stream.moveTo(left, y)
                                stream.lineTo(right, y)
                            }
                        }
                        stream.stroke()
                    }
                }
            }
            stream.restoreGraphicsState()
        }
    }

    // endregion
    // region Bitmaps

    /** Typed text as a transparent bitmap. Drawn by Android, so any script (Arabic included) is shaped correctly. */
    fun textBitmap(text: String, argb: Int = Color.BLACK): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val padding = 12f
        val metrics = paint.fontMetrics
        val width = (paint.measureText(text) + padding * 2).toInt().coerceAtLeast(1)
        val height = (metrics.descent - metrics.ascent + padding * 2).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText(text, padding, padding - metrics.ascent, paint)
        }
    }

    /** The short guide placed in the library on first launch. */
    fun writeWelcomeGuide(destination: File) {
        val ink = Color.rgb(18, 20, 28)
        val ember = Color.rgb(226, 81, 27)
        val document = PdfDocument()
        fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun Canvas.paragraph(text: String, x: Float, top: Float, maxWidth: Float, paint: Paint): Float {
            var y = top
            var line = ""
            for (word in text.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    drawText(line, x, y, paint)
                    y += paint.textSize * 1.4f
                    line = word
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) drawText(line, x, y, paint)
            return y + paint.textSize * 1.4f
        }

        val first = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        first.canvas.apply {
            drawRect(0f, 0f, 612f, 210f, Paint().apply { color = ink })
            drawRect(0f, 210f, 612f, 216f, Paint().apply { color = ember })
            drawText("Welcome to PDFoundry", 56f, 120f, paint(38f, Color.WHITE, bold = true))
            drawText("Read, merge, edit and sign PDF documents.", 56f, 156f, paint(17f, Color.argb(205, 255, 255, 255)))
            var y = 280f
            listOf(
                "Read" to "Open any PDF from Files, scan paper with the camera, or build a PDF from your photos. Search the text and jump between pages.",
                "Merge" to "Press and hold a document in the library, choose two or more, then tap Merge. Put the files into the order you want.",
                "Edit" to "In Markup, highlight, underline or strike through text, add typed text and draw. Pages reorders, rotates, duplicates or deletes pages.",
                "Sign" to "In Fill & Sign, draw your signature once and reuse it. Drag it into place, resize it, and tap Place.",
                "Private" to "Everything stays on this device. PDFoundry has no account, no ads and no tracking."
            ).forEach { (title, body) ->
                drawText(title, 56f, y, paint(19f, ember, bold = true))
                y = paragraph(body, 56f, y + 24f, 500f, paint(14f, ink)) + 26f
            }
        }
        document.finishPage(first)

        val second = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 2).create())
        second.canvas.apply {
            drawText("Try it on this page", 56f, 100f, paint(30f, ink, bold = true))
            var y = paragraph("Tap Markup, then Highlight, and drag across this sentence to highlight it.", 56f, 156f, 500f, paint(16f, ink)) + 24f
            y = paragraph("In Markup, tap Draw to draw anywhere on the page, or Text to type a note.", 56f, y, 500f, paint(16f, ink)) + 24f
            paragraph("Tap Fill & Sign, then Signature, and place your signature on the line below.", 56f, y, 500f, paint(16f, ink))
            val rule = Paint().apply { color = Color.rgb(140, 140, 140) }
            drawRect(56f, 560f, 356f, 561f, rule)
            drawText("Signature", 56f, 580f, paint(12f, Color.rgb(115, 115, 115)))
            drawRect(396f, 560f, 556f, 561f, rule)
            drawText("Date", 396f, 580f, paint(12f, Color.rgb(115, 115, 115)))
        }
        document.finishPage(second)

        destination.outputStream().use { document.writeTo(it) }
        document.close()
    }

    // endregion
}
