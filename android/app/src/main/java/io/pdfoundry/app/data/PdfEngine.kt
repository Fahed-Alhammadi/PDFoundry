package io.pdfoundry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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

/** A side of a rect on the displayed page. */
enum class Edge { Bottom, Left, Top, Right }

/**
 * One word on a page. `rect` is in fractions of the displayed page from the top-left (rotation and crop
 * applied); `lower` is the side of it below the text (Bottom for upright text, Left or Right for text
 * running down or up the screen).
 */
data class TextWord(val text: String, val rect: RectF, val lower: Edge = Edge.Bottom)

/** A mark drawn over selected text. */
enum class TextMark { Highlight, Underline, Strikethrough }

class PdfException(message: String) : IOException(message)

/** What a password opens. [Full]: it is the owner password (or user and owner are the same), so the file can be changed. */
enum class UnlockResult { Full, Limited, Wrong }

/**
 * Every change to a PDF goes through here. Positions are given the way the user sees the page
 * (fractions from the top-left corner); the engine converts them to PDF space, including rotated pages.
 * All functions block and belong on a background dispatcher.
 */
object PdfEngine {
    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
        Ocr.init(context)
    }

    private fun memory() = MemoryUsageSetting.setupTempFileOnly()

    /** Loads `file`, applies `block`, and replaces the file only after the new copy is written in full. */
    internal fun edit(file: File, block: (PDDocument) -> Unit) {
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
            throw PdfException("One of the documents is password protected. Use More › Remove Password first.")
        } catch (e: IOException) {
            destination.delete()
            throw PdfException("One of the documents could not be read. It may be damaged.")
        }
    }

    /** Copies [pages] of [source] into a new file. With [password], the source is opened with it and the new file keeps it. */
    fun extract(source: File, pages: List<Int>, destination: File, password: String? = null) {
        load(source, password).use { document ->
            requireFullAccess(document)
            writePart(document, pages.sorted(), destination, password)
        }
    }

    /**
     * Writes each list of [parts] (0-based pages, in order) to the matching file in [destinations]. The
     * source is only read. With [password], the source is opened with it and every part keeps it.
     */
    fun split(source: File, parts: List<List<Int>>, destinations: List<File>, password: String? = null) {
        require(parts.size == destinations.size)
        load(source, password).use { document ->
            requireFullAccess(document)
            parts.zip(destinations).forEach { (pages, destination) -> writePart(document, pages, destination, password) }
        }
    }

    private fun writePart(document: PDDocument, pages: List<Int>, destination: File, password: String?) {
        PDDocument().use { result ->
            pages.filter { it in 0 until document.numberOfPages }.forEach { result.importPage(document.getPage(it)) }
            if (result.numberOfPages == 0) throw PdfException("There are no pages to save.")
            if (password != null) result.protect(policy(password))
            // Saved while the source is still open: imported pages read their content from it.
            try {
                result.save(destination)
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
    }

    /**
     * Copying pages out of a file whose protection can't be carried over (restrictions set by its author,
     * or a password that only opens it) would drop that protection: refused, as editing it is.
     */
    private fun requireFullAccess(document: PDDocument) {
        if (document.isEncrypted && !document.currentAccessPermission.isOwnerPermission) throw PdfException("Password-protected documents are read-only.")
    }

    private fun load(file: File, password: String?): PDDocument =
        if (password == null) PDDocument.load(file, memory()) else PDDocument.load(file, password, memory())

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

    /**
     * Writes an unprotected copy of a password-protected file. [UnlockResult.Full] when the password may
     * change the file, [UnlockResult.Limited] when it only opens it (nothing is written then unless
     * [evenIfLimited]), [UnlockResult.Wrong] when it opens nothing.
     */
    fun unlock(source: File, password: String, destination: File, evenIfLimited: Boolean = true): UnlockResult = try {
        PDDocument.load(source, password, memory()).use { document ->
            val result = if (document.currentAccessPermission.isOwnerPermission) UnlockResult.Full else UnlockResult.Limited
            if (result == UnlockResult.Full || evenIfLimited) {
                document.isAllSecurityToBeRemoved = true
                saveReplacing(document, destination)
            }
            result
        }
    } catch (e: InvalidPasswordException) {
        UnlockResult.Wrong
    }

    /** What [password] opens in [file], without writing anything. An unprotected file is [UnlockResult.Full]. */
    fun access(file: File, password: String): UnlockResult = try {
        PDDocument.load(file, password, memory()).use { document ->
            if (!document.isEncrypted || document.currentAccessPermission.isOwnerPermission) UnlockResult.Full else UnlockResult.Limited
        }
    } catch (e: InvalidPasswordException) {
        UnlockResult.Wrong
    }

    /**
     * True when [password] is also the password that opens [file] (its user password). False when it
     * is only the owner password: saving with it as both then changes the password that opens the file.
     */
    fun opensWith(file: File, password: String): Boolean = try {
        PDDocument.load(file, password, memory()).use { document ->
            val encryption = document.encryption ?: return@use true
            val id = (document.document.documentID?.get(0) as? com.tom_roush.pdfbox.cos.COSString)?.bytes ?: ByteArray(0)
            com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler().isUserPassword(
                password, encryption.userKey, encryption.ownerKey, encryption.permissions, id,
                encryption.revision, encryption.length / 8, encryption.isEncryptMetaData
            )
        }
    } catch (e: Exception) {
        true
    }

    /** True when the file is encrypted: it needs a password to open, or carries restrictions. */
    fun isProtected(file: File): Boolean = try {
        PDDocument.load(file, memory()).use { it.isEncrypted }
    } catch (e: InvalidPasswordException) {
        true
    }

    fun pageCount(file: File, password: String? = null): Int = load(file, password).use { it.numberOfPages }

    /**
     * Writes [source] (an unprotected PDF) to [destination] protected with [password]: AES-256, the same
     * password to open it and to change it, nothing restricted. [destination] is replaced only once the
     * protected copy is complete; it may be [source] itself.
     */
    fun protect(source: File, password: String, destination: File) {
        if (password.isEmpty()) throw PdfException("Enter a password.")
        PDDocument.load(source, memory()).use { document ->
            if (document.isEncrypted) throw PdfException("This document is already protected with a password.")
            document.protect(policy(password))
            saveReplacing(document, destination)
        }
    }

    private fun policy(password: String) = StandardProtectionPolicy(password, password, AccessPermission()).apply {
        encryptionKeyLength = 256
    }

    /** Saves to a temporary file next to [destination], then puts it in place: [destination] is never half-written. */
    private fun saveReplacing(document: PDDocument, destination: File) {
        val temp = File(destination.parentFile, "${destination.name}.${System.nanoTime()}.tmp")
        try {
            document.save(temp)
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
            }
        } finally {
            temp.delete()
        }
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

    /** Adds page numbers to every page's content (one change). */
    fun addPageNumbers(file: File, style: PageNumberStyle) = PageStamps.addPageNumbers(file, style)

    /** Adds a watermark on top of every page's content (one change). */
    fun addWatermark(file: File, style: WatermarkStyle) = PageStamps.addWatermark(file, style)

    /**
     * Places an image (a signature, initials, a mark, or typed text rendered to a bitmap) as a `Stamp`
     * annotation whose appearance is the image. `rect` is in page fractions.
     */
    fun stamp(file: File, pageIndex: Int, image: Bitmap, rect: RectF) = edit(file) { document ->
        val frame = PageFrame(document.getPage(pageIndex))
        val w = rect.width() * frame.width
        val h = rect.height() * frame.height
        val picture = LosslessFactory.createFromImage(document, image)
        AnnotationWriter.add(document, pageIndex, "Stamp", rect.left * frame.width, frame.height - rect.bottom * frame.height, w, h, configure = { dict, _ ->
            dict.setName(COSName.NAME, "PDFoundry")
        }) { stream ->
            stream.drawImage(picture, 0f, 0f, w, h)
        }
    }

    /**
     * One drawing becomes `Ink` annotations: strokes drawn one after another with the same pen share one
     * annotation, so a drawing is selected, moved and deleted as a whole.
     */
    fun ink(file: File, pageIndex: Int, strokes: List<InkStroke>) = edit(file) { document ->
        val frame = PageFrame(document.getPage(pageIndex))
        val runs = mutableListOf<MutableList<InkStroke>>()
        for (stroke in strokes.filter { it.points.isNotEmpty() }) {
            val last = runs.lastOrNull()?.last()
            if (last != null && last.argb == stroke.argb && last.width == stroke.width && last.highlighter == stroke.highlighter) runs.last() += stroke else runs += mutableListOf(stroke)
        }
        for (run in runs) {
            val pen = run.first()
            // A tap leaves a single point; draw a dot-sized segment so it still shows.
            val paths = run.map { stroke ->
                val points = if (stroke.points.size == 1) stroke.points + PagePoint(stroke.points[0].x + 0.0005f, stroke.points[0].y) else stroke.points
                points.map { frame.point(it) }
            }
            val lineWidth = pen.width * frame.width
            val pad = lineWidth / 2 + 1f
            val all = paths.flatten()
            val left = all.minOf { it[0] } - pad
            val bottom = all.minOf { it[1] } - pad
            val right = all.maxOf { it[0] } + pad
            val top = all.maxOf { it[1] } + pad
            AnnotationWriter.add(document, pageIndex, "Ink", left, bottom, right - left, top - bottom, configure = { dict, f ->
                val list = COSArray()
                paths.forEach { path -> list.add(COSArray().apply { path.forEach { p -> f.user(p[0], p[1]).let { u -> add(COSFloat(u[0])); add(COSFloat(u[1])) } } }) }
                dict.setItem(COSName.getPDFName("InkList"), list)
                dict.setItem(COSName.C, rgb(pen.argb).toCOSArray())
                dict.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, lineWidth); setName(COSName.S, "S") })
            }) { stream ->
                if (pen.highlighter) {
                    stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                        strokingAlphaConstant = 0.45f
                        blendMode = BlendMode.MULTIPLY
                    })
                }
                stream.setStrokingColor(Color.red(pen.argb) / 255f, Color.green(pen.argb) / 255f, Color.blue(pen.argb) / 255f)
                stream.setLineWidth(lineWidth)
                stream.setLineCapStyle(if (pen.highlighter) 0 else 1)
                stream.setLineJoinStyle(1)
                for (path in paths) {
                    stream.moveTo(path[0][0] - left, path[0][1] - bottom)
                    path.drop(1).forEach { stream.lineTo(it[0] - left, it[1] - bottom) }
                    stream.stroke()
                }
            }
        }
    }

    // endregion
    // region Text markup

    /**
     * The words on a page, in the order the document writes them (so a column is read to its end
     * before the next one starts), with where each one is on the displayed page. A page without text
     * (a scan) has none.
     *
     * Each glyph's box is built in PDF space from its text matrix, so text drawn turned (a rotated table
     * header, a landscape scan with /Rotate and counter-rotated content) lands where it is shown, and
     * [TextWord.lower] says which side of the rect is below the text.
     */
    fun words(file: File, pageIndex: Int): List<TextWord> = PDDocument.load(file, memory()).use { document ->
        if (pageIndex !in 0 until document.numberOfPages) return@use emptyList()
        val page = document.getPage(pageIndex)
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val found = mutableListOf<TextWord>()
        val stripper = object : PDFTextStripper() {
            // The word being collected. It carries over between calls: the stripper may hand over a whole
            // line or (on a page with /Rotate) a single glyph at a time, so words are split by geometry only.
            var current = mutableListOf<TextPosition>()

            fun flush() {
                if (current.isNotEmpty()) word(current)?.let { found += it }
                current = mutableListOf()
            }

            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                for (position in positions) {
                    if (position.unicode.orEmpty().isBlank()) {
                        flush()
                        continue
                    }
                    val previous = current.lastOrNull()
                    if (previous != null && !sameWord(previous, position)) flush()
                    current += position
                }
            }

            /**
             * True when [next] carries on the word [previous] is in: same direction, same line, and
             * no more than a small gap after it. Measured in PDF space along the text's own direction,
             * so it works for text drawn turned and on pages with /Rotate.
             */
            private fun sameWord(previous: TextPosition, next: TextPosition): Boolean {
                val a = previous.textMatrix
                val b = next.textMatrix
                val size = kotlin.math.hypot(a.shearX, a.scaleY)
                val nextSize = kotlin.math.hypot(b.shearX, b.scaleY)
                if (size <= 0f || nextSize <= 0f) return false
                val upX = a.shearX / size
                val upY = a.scaleY / size
                // Turned differently: a new word.
                if (upX * b.shearX / nextSize + upY * b.scaleY / nextSize < 0.95f) return false
                // Along the line is "up" turned a quarter clockwise.
                val alongX = upY
                val alongY = -upX
                val fromEndX = b.translateX - previous.endX
                val fromEndY = b.translateY - previous.endY
                val gap = fromEndX * alongX + fromEndY * alongY
                val offLine = kotlin.math.abs((b.translateX - a.translateX) * upX + (b.translateY - a.translateY) * upY)
                return offLine <= size * 0.5f && gap <= size * 0.18f && gap >= -size
            }

            private fun word(positions: List<TextPosition>): TextWord? {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                var downX = 0f
                var downY = 0f
                for (position in positions) {
                    // The text rendering matrix, in PDF space measured from the crop box's lower-left corner.
                    val matrix = position.textMatrix
                    val originX = matrix.translateX
                    val originY = matrix.translateY
                    // "Up" for this glyph; its length is the font size in points.
                    var upX = matrix.shearX
                    var upY = matrix.scaleY
                    val size = kotlin.math.hypot(upX, upY)
                    if (size <= 0f) continue
                    upX /= size
                    upY /= size
                    val advanceX = position.endX - originX
                    val advanceY = position.endY - originY
                    val ascent = size * 0.78f
                    val descent = size * 0.22f
                    for ((x, y) in listOf(
                        originX - upX * descent to originY - upY * descent,
                        originX + upX * ascent to originY + upY * ascent,
                        originX + advanceX - upX * descent to originY + advanceY - upY * descent,
                        originX + advanceX + upX * ascent to originY + advanceY + upY * ascent
                    )) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                    downX -= upX
                    downY -= upY
                }
                if (maxX <= minX || maxY <= minY) return null
                // To the unrotated crop box measured from its top-left, then to the page as displayed.
                val rect = displayed(RectF(minX, box.height - maxY, maxX, box.height - minY), box.width, box.height, rotation)
                return TextWord(positions.joinToString("") { it.unicode.orEmpty() }, rect, lowerEdge(downX, -downY, rotation))
            }
        }
        // Keep the document's own order: sorting by position would read across the gutter of a two-column page.
        stripper.sortByPosition = false
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(document)
        stripper.flush()
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

    /** Which side of the displayed rect a "down" direction (unrotated crop box, y pointing down) faces. */
    private fun lowerEdge(downU: Float, downV: Float, rotation: Int): Edge {
        val (x, y) = when (rotation) {
            90 -> -downV to downU
            180 -> -downU to -downV
            270 -> downV to -downU
            else -> downU to downV
        }
        return if (kotlin.math.abs(x) > kotlin.math.abs(y)) (if (x > 0) Edge.Right else Edge.Left) else (if (y < 0) Edge.Top else Edge.Bottom)
    }

    /** The text's lower side for text drawn upright on a page with this rotation. */
    private fun defaultLower(rotation: Int) = when (rotation) {
        90 -> Edge.Left
        180 -> Edge.Top
        270 -> Edge.Right
        else -> Edge.Bottom
    }

    /**
     * Marks text on a page: a highlight fills each rect with a multiply blend (so the text stays
     * readable); underline and strikethrough draw a line along the text. `rects` are page fractions;
     * `lower` gives, per rect, the side below its text (see [TextWord.lower]); without it, text is taken
     * to be upright on the page.
     */
    fun markText(file: File, pageIndex: Int, rects: List<RectF>, mark: TextMark, argb: Int, lower: List<Edge>? = null) = edit(file) { document ->
        if (rects.isEmpty()) return@edit
        val frame = PageFrame(document.getPage(pageIndex))
        val fallback = defaultLower(frame.rotation)
        val width = frame.width
        val height = frame.height
        // Display points, origin at the bottom-left: left, bottom, right, top.
        val boxes = rects.map { floatArrayOf(it.left * width, height - it.bottom * height, it.right * width, height - it.top * height) }
        val left = boxes.minOf { it[0] }
        val bottom = boxes.minOf { it[1] }
        val right = boxes.maxOf { it[2] }
        val top = boxes.maxOf { it[3] }
        val subtype = when (mark) {
            TextMark.Highlight -> "Highlight"
            TextMark.Underline -> "Underline"
            TextMark.Strikethrough -> "StrikeOut"
        }
        AnnotationWriter.add(document, pageIndex, subtype, left, bottom, right - left, top - bottom, configure = { dict, f ->
            // QuadPoints in user space: upper-left, upper-right, lower-left, lower-right of each run of text,
            // "upper" meaning the side away from the text's lower side.
            val quads = COSArray()
            boxes.forEachIndexed { index, box ->
                val l = box[0]; val b = box[1]; val r = box[2]; val t = box[3]
                val corners = when (lower?.getOrNull(index) ?: fallback) {
                    Edge.Bottom -> listOf(l to t, r to t, l to b, r to b)
                    Edge.Top -> listOf(r to b, l to b, r to t, l to t)
                    Edge.Left -> listOf(r to t, r to b, l to t, l to b)
                    Edge.Right -> listOf(l to b, l to t, r to b, r to t)
                }
                corners.forEach { (x, y) -> f.user(x, y).let { u -> quads.add(COSFloat(u[0])); quads.add(COSFloat(u[1])) } }
            }
            dict.setItem(COSName.getPDFName("QuadPoints"), quads)
            dict.setItem(COSName.C, rgb(argb).toCOSArray())
        }) { stream ->
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
            boxes.forEachIndexed { index, box ->
                val l = box[0] - left
                val b = box[1] - bottom
                val r = box[2] - left
                val t = box[3] - bottom
                val rectWidth = r - l
                val rectHeight = t - b
                when (mark) {
                    TextMark.Highlight -> {
                        stream.setNonStrokingColor(red, green, blue)
                        stream.addRect(l, b, rectWidth, rectHeight)
                        stream.fill()
                    }
                    TextMark.Underline, TextMark.Strikethrough -> {
                        // How far from the text's top edge towards its lower side.
                        val along = if (mark == TextMark.Underline) 0.92f else 0.5f
                        stream.setStrokingColor(red, green, blue)
                        stream.setLineCapStyle(0)
                        when (lower?.getOrNull(index) ?: fallback) {
                            Edge.Bottom -> line(stream, l, t - rectHeight * along, r, t - rectHeight * along, rectHeight)
                            Edge.Top -> line(stream, l, b + rectHeight * along, r, b + rectHeight * along, rectHeight)
                            Edge.Left -> line(stream, r - rectWidth * along, b, r - rectWidth * along, t, rectWidth)
                            Edge.Right -> line(stream, l + rectWidth * along, b, l + rectWidth * along, t, rectWidth)
                        }
                    }
                }
            }
        }
    }

    /** A line about 7% as thick as the text is tall. */
    private fun line(stream: PDPageContentStream, x1: Float, y1: Float, x2: Float, y2: Float, textHeight: Float) {
        stream.setLineWidth((textHeight * 0.07f).coerceAtLeast(0.5f))
        stream.moveTo(x1, y1)
        stream.lineTo(x2, y2)
        stream.stroke()
    }

    // endregion
    // region Marks (annotations)

    /** The stroke of shapes, in points. */
    const val SHAPE_WIDTH = 2f
    private const val ARROW_HEAD = 10f

    /**
     * Draws a shape from [from] to [to] (page fractions) in [argb] (its alpha is ignored), 2 pt wide, no
     * fill: a `Square` or `Circle` filling the rect between the points, or a `Line` between them (an
     * arrow has an open arrowhead at [to]). Nothing is added when the points are (almost) the same.
     */
    fun shape(file: File, pageIndex: Int, kind: ShapeKind, from: PagePoint, to: PagePoint, argb: Int) = edit(file) { document ->
        val frame = PageFrame(document.getPage(pageIndex))
        val a = frame.point(from)
        val b = frame.point(to)
        if (kotlin.math.hypot(b[0] - a[0], b[1] - a[1]) < 3f) return@edit
        val red = Color.red(argb) / 255f
        val green = Color.green(argb) / 255f
        val blue = Color.blue(argb) / 255f
        val border = COSDictionary().apply { setFloat(COSName.W, SHAPE_WIDTH); setName(COSName.S, "S") }
        when (kind) {
            ShapeKind.Rectangle, ShapeKind.Ellipse -> {
                val left = minOf(a[0], b[0]); val right = maxOf(a[0], b[0])
                val bottom = minOf(a[1], b[1]); val top = maxOf(a[1], b[1])
                val w = maxOf(right - left, SHAPE_WIDTH * 2)
                val h = maxOf(top - bottom, SHAPE_WIDTH * 2)
                AnnotationWriter.add(document, pageIndex, if (kind == ShapeKind.Rectangle) "Square" else "Circle", left, bottom, w, h, configure = { dict, _ ->
                    dict.setItem(COSName.C, rgb(argb).toCOSArray())
                    dict.setItem(COSName.BS, border)
                }) { stream ->
                    stream.setStrokingColor(red, green, blue)
                    stream.setLineWidth(SHAPE_WIDTH)
                    val inset = SHAPE_WIDTH / 2
                    if (kind == ShapeKind.Rectangle) {
                        stream.addRect(inset, inset, w - SHAPE_WIDTH, h - SHAPE_WIDTH)
                    } else {
                        ellipse(stream, w / 2, h / 2, w / 2 - inset, h / 2 - inset)
                    }
                    stream.stroke()
                }
            }
            ShapeKind.Line, ShapeKind.Arrow -> {
                val pad = SHAPE_WIDTH + if (kind == ShapeKind.Arrow) ARROW_HEAD else 0f
                val left = minOf(a[0], b[0]) - pad
                val bottom = minOf(a[1], b[1]) - pad
                val right = maxOf(a[0], b[0]) + pad
                val top = maxOf(a[1], b[1]) + pad
                AnnotationWriter.add(document, pageIndex, "Line", left, bottom, right - left, top - bottom, configure = { dict, f ->
                    val start = f.user(a[0], a[1])
                    val end = f.user(b[0], b[1])
                    dict.setItem(COSName.L, floats(start[0], start[1], end[0], end[1]))
                    dict.setItem(COSName.LE, COSArray().apply { add(COSName.getPDFName("None")); add(COSName.getPDFName(if (kind == ShapeKind.Arrow) "OpenArrow" else "None")) })
                    dict.setItem(COSName.C, rgb(argb).toCOSArray())
                    dict.setItem(COSName.BS, border)
                }) { stream ->
                    stream.setStrokingColor(red, green, blue)
                    stream.setLineWidth(SHAPE_WIDTH)
                    stream.setLineCapStyle(1)
                    stream.setLineJoinStyle(1)
                    val x1 = a[0] - left; val y1 = a[1] - bottom
                    val x2 = b[0] - left; val y2 = b[1] - bottom
                    stream.moveTo(x1, y1)
                    stream.lineTo(x2, y2)
                    stream.stroke()
                    if (kind == ShapeKind.Arrow) {
                        val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
                        val spread = Math.toRadians(30.0).toFloat()
                        stream.moveTo(x2 - ARROW_HEAD * kotlin.math.cos(angle - spread), y2 - ARROW_HEAD * kotlin.math.sin(angle - spread))
                        stream.lineTo(x2, y2)
                        stream.lineTo(x2 - ARROW_HEAD * kotlin.math.cos(angle + spread), y2 - ARROW_HEAD * kotlin.math.sin(angle + spread))
                        stream.stroke()
                    }
                }
            }
        }
    }

    /** An ellipse centred at ([cx], [cy]) from four Bézier curves. */
    private fun ellipse(stream: PDPageContentStream, cx: Float, cy: Float, rx: Float, ry: Float) {
        val k = 0.5523f
        stream.moveTo(cx + rx, cy)
        stream.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        stream.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        stream.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        stream.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        stream.closePath()
    }

    /** The side of a comment's note icon, in points. */
    private const val NOTE = 22f

    /**
     * Places a comment (a `Text` annotation, the sticky note other apps show with its text in a popup)
     * centred at [at] (page fractions). Returns its id; an empty [text] creates nothing and returns null.
     */
    fun comment(file: File, pageIndex: Int, at: PagePoint, text: String): String? {
        val words = text.trim()
        if (words.isEmpty()) return null
        var id: String? = null
        edit(file) { document ->
            val frame = PageFrame(document.getPage(pageIndex))
            val p = frame.point(at)
            val left = (p[0] - NOTE / 2).coerceIn(0f, frame.width - NOTE)
            val bottom = (p[1] - NOTE / 2).coerceIn(0f, frame.height - NOTE)
            id = AnnotationWriter.add(document, pageIndex, "Text", left, bottom, NOTE, NOTE, configure = { dict, _ ->
                dict.setString(COSName.CONTENTS, words)
                dict.setName(COSName.NAME, "Comment")
                dict.setItem(COSName.C, floats(1f, 0.8f, 0.2f))
                dict.setBoolean(COSName.getPDFName("Open"), false)
            }) { stream -> noteIcon(stream) }.annotationName
        }
        return id
    }

    /** A yellow speech bubble with three lines of "text", 22 pt square. */
    private fun noteIcon(stream: PDPageContentStream) {
        stream.setNonStrokingColor(1f, 0.8f, 0.2f)
        stream.setStrokingColor(0.42f, 0.32f, 0.05f)
        stream.setLineWidth(1f)
        stream.setLineJoinStyle(1)
        stream.moveTo(2.5f, 19.5f)
        stream.lineTo(19.5f, 19.5f)
        stream.lineTo(19.5f, 6.5f)
        stream.lineTo(10f, 6.5f)
        stream.lineTo(5f, 2f)
        stream.lineTo(6f, 6.5f)
        stream.lineTo(2.5f, 6.5f)
        stream.closePath()
        stream.fillAndStroke()
        stream.setLineWidth(1.2f)
        for (y in listOf(15.5f, 12.5f, 9.5f)) {
            stream.moveTo(5.5f, y)
            stream.lineTo(16.5f, y)
        }
        stream.stroke()
    }

    /** Changes a comment's text; empty text deletes the comment. */
    fun updateComment(file: File, pageIndex: Int, id: String, text: String) {
        val words = text.trim()
        if (words.isEmpty()) return deleteMark(file, pageIndex, id)
        edit(file) { document ->
            val annotation = document.getPage(pageIndex).annotation(id) ?: throw PdfException("This comment is no longer in the document.")
            if (Annotations.kind(annotation) != MarkKind.Comment) throw PdfException("This is not a comment.")
            annotation.contents = words
            annotation.setModifiedDate(java.util.Calendar.getInstance())
        }
    }

    fun commentText(file: File, pageIndex: Int, id: String): String = PDDocument.load(file, memory()).use { document ->
        document.getPage(pageIndex).annotation(id)?.contents.orEmpty()
    }

    /** The marks on a page that PDFoundry can select: every annotation of a kind it makes, its own or not. */
    fun marks(file: File, pageIndex: Int): List<MarkInfo> = PDDocument.load(file, memory()).use { document ->
        if (pageIndex !in 0 until document.numberOfPages) return@use emptyList()
        marksOf(document.getPage(pageIndex))
    }

    private fun marksOf(page: PDPage): List<MarkInfo> {
        val frame = PageFrame(page)
        return runCatching { page.annotations }.getOrDefault(emptyList()).mapIndexedNotNull { index, annotation ->
            val kind = Annotations.kind(annotation)
            val rect = annotation.rectangle
            if (kind == MarkKind.Other || rect == null || annotation.isHidden || annotation.isNoView) null
            else MarkInfo(idOf(annotation, index), kind, frame.fractions(rect), Annotations.isMovable(annotation))
        }
    }

    /** Every page's marks, by page index (pages without marks are left out). */
    fun allMarks(file: File): Map<Int, List<MarkInfo>> = PDDocument.load(file, memory()).use { document ->
        (0 until document.numberOfPages).mapNotNull { index -> marksOf(document.getPage(index)).takeIf { it.isNotEmpty() }?.let { index to it } }.toMap()
    }

    /** The topmost mark at [x], [y] (page fractions), if any. */
    fun markAt(file: File, pageIndex: Int, x: Float, y: Float): MarkInfo? = hit(marks(file, pageIndex), x, y)

    /** The topmost of [marks] under the point, with a little slack for thin marks. */
    fun hit(marks: List<MarkInfo>, x: Float, y: Float, slack: Float = 0.01f): MarkInfo? =
        marks.lastOrNull { x in it.rect.left - slack..it.rect.right + slack && y in it.rect.top - slack..it.rect.bottom + slack }

    /**
     * Moves or resizes one of PDFoundry's marks to [rect] (page fractions). Only `/Rect` and the mark's
     * geometry (ink paths, line ends) change: the appearance is kept as it is and fitted to the new rect
     * by every viewer, so an image is never redrawn.
     */
    fun moveMark(file: File, pageIndex: Int, id: String, rect: RectF) = edit(file) { document ->
        val page = document.getPage(pageIndex)
        val annotation = page.annotation(id) ?: throw PdfException("This mark is no longer in the document.")
        if (!Annotations.isMovable(annotation)) throw PdfException("This mark can't be moved.")
        val frame = PageFrame(page)
        val old = annotation.rectangle ?: throw PdfException("This mark can't be moved.")
        val new = frame.userRect(rect)
        if (new.width < 1f || new.height < 1f) throw PdfException("That is too small.")
        val sx = if (old.width > 0f) new.width / old.width else 1f
        val sy = if (old.height > 0f) new.height / old.height else 1f
        fun mapped(values: FloatArray) = COSArray().apply {
            values.forEachIndexed { i, v -> add(COSFloat(if (i % 2 == 0) new.lowerLeftX + (v - old.lowerLeftX) * sx else new.lowerLeftY + (v - old.lowerLeftY) * sy)) }
        }
        val dict = annotation.cosObject
        val inkList = COSName.getPDFName("InkList")
        (dict.getDictionaryObject(inkList) as? COSArray)?.let { list ->
            val moved = COSArray()
            for (i in 0 until list.size()) (list.getObject(i) as? COSArray)?.let { moved.add(mapped(it.toFloatArray())) }
            dict.setItem(inkList, moved)
        }
        (dict.getDictionaryObject(COSName.L) as? COSArray)?.let { dict.setItem(COSName.L, mapped(it.toFloatArray())) }
        annotation.rectangle = new
        annotation.setModifiedDate(java.util.Calendar.getInstance())
    }

    /** Deletes a mark (any kind PDFoundry knows, made here or not), and its popup. */
    fun deleteMark(file: File, pageIndex: Int, id: String) = edit(file) { document ->
        val page = document.getPage(pageIndex)
        val annotation = page.annotation(id) ?: throw PdfException("This mark is no longer in the document.")
        if (!Annotations.isDeletable(annotation)) throw PdfException("This mark can't be deleted here.")
        val popup = annotation.cosObject.getDictionaryObject(COSName.getPDFName("Popup"))
        // A new array: PdfBox's list view of /Annots does not always remove from the array behind it.
        val kept = page.annotations.filterNot { it.cosObject == annotation.cosObject || (popup != null && it.cosObject == popup) }
        page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { kept.forEach { add(it.cosObject) } })
    }

    // endregion
    // region Form fields

    /**
     * The form fields on every page, one entry per widget, with where each is on the displayed page.
     * A document without a form, or with an XFA-only (dynamic) form, has none.
     */
    fun fields(file: File): List<FormField> = PDDocument.load(file, memory()).use { document ->
        val form = document.documentCatalog.acroForm ?: return@use emptyList()
        if (form.xfaIsDynamic()) return@use emptyList()
        // Which page each widget is on: a widget's own /P entry is optional, so look at every page.
        val pageOf = HashMap<Any, Int>()
        document.pages.forEachIndexed { index, page ->
            runCatching { page.annotations }.getOrDefault(emptyList()).forEach { pageOf[it.cosObject] = index }
        }
        val found = mutableListOf<FormField>()
        for (field in form.fieldTree) {
            val kind = kindOf(field) ?: continue
            val terminal = field as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField ?: continue
            val value = runCatching { valueOf(field) }.getOrDefault("")
            val choice = field as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
            val options = choice?.let { runCatching { it.optionsExportValues }.getOrNull() }.orEmpty()
            val labels = choice?.let { runCatching { it.optionsDisplayValues }.getOrNull() }?.takeIf { it.size == options.size } ?: options
            terminal.widgets.forEachIndexed { index, widget ->
                val pageIndex = pageOf[widget.cosObject] ?: widget.page?.let { document.pages.indexOf(it) }?.takeIf { it >= 0 } ?: return@forEachIndexed
                if (widget.isHidden || widget.isNoView) return@forEachIndexed
                val r = widget.rectangle ?: return@forEachIndexed
                if (r.width <= 0f || r.height <= 0f) return@forEachIndexed
                val page = document.getPage(pageIndex)
                val box = page.cropBox
                val rotation = ((page.rotation % 360) + 360) % 360
                // To the unrotated crop box measured from its top-left, then to the page as displayed.
                val unrotated = RectF(r.lowerLeftX - box.lowerLeftX, box.upperRightY - r.upperRightY, r.upperRightX - box.lowerLeftX, box.upperRightY - r.lowerLeftY)
                found += FormField(
                    name = field.fullyQualifiedName,
                    kind = kind,
                    page = pageIndex,
                    rect = displayed(unrotated, box.width, box.height, rotation),
                    value = value,
                    options = options,
                    onValue = (field as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton)?.let { onValueOf(it, index) },
                    readOnly = field.isReadOnly,
                    widget = index,
                    labels = labels,
                    checked = (kind == FieldKind.Checkbox || kind == FieldKind.Radio) && checkedOf(widget)
                )
            }
        }
        found
    }

    /**
     * Sets a form field's value and saves it as a field value (nothing is flattened), with appearances
     * so viewers that don't draw their own show it. Text: the new text. Checkbox: its on name to tick
     * it, "Off" (or empty) to clear it. Radio: the on name of the button chosen. Choice: an option.
     * A text value the field's font can't show (Arabic in Helvetica) gets an appearance drawn by Android.
     */
    fun setField(file: File, name: String, value: String, widgetIndex: Int = 0) = edit(file) { document ->
        val form = document.documentCatalog.acroForm ?: throw PdfException("This document has no form.")
        val field = form.getField(name) ?: throw PdfException("This field is no longer in the document.")
        if (field.isReadOnly) throw PdfException("This field can't be changed.")
        when (field) {
            is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField -> {
                try {
                    field.setValue(value)
                } catch (e: Exception) {
                    // The field's font can't encode the text: keep it as the field's value, and draw it ourselves.
                    field.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, value)
                    field.widgets.forEach { imageAppearance(document, it, value, field.isMultiline) }
                }
            }
            is com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox, is com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton -> {
                // Set directly, by widget: PdfBox's own setValue expects /Opt export values and appearance
                // names to line up in ways real forms often don't.
                val button = field as com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
                setButton(button, widgetIndex, on = value.isNotEmpty() && value != "Off")
            }
            is com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice -> {
                // /V is always the option's export value; the box shows its label.
                val exports = runCatching { field.optionsExportValues }.getOrDefault(emptyList())
                val labels = runCatching { field.optionsDisplayValues }.getOrDefault(emptyList())
                val label = labels.getOrNull(exports.indexOf(value)) ?: value
                val editable = field is com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox && field.isEdit
                if (exports.isNotEmpty() && value !in exports && !editable) throw PdfException("That isn't one of this list's options.")
                val drawn = try {
                    field.setValue(value)
                    label == value
                } catch (e: Exception) {
                    // The field's font can't show it (a non-Latin option): keep the value, draw it ourselves.
                    field.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, value)
                    false
                }
                if (!drawn) field.widgets.forEach { imageAppearance(document, it, label, multiline = false) }
            }
            else -> throw PdfException("This field can't be filled in here.")
        }
    }

    // endregion
    // region Bitmaps

    /** Text wrapped to [widthPx] on as many lines as it needs, at [textBitmap]'s size; right-to-left text flows from the right. */
    fun textBlockBitmap(text: String, widthPx: Int, argb: Int = Color.BLACK): Bitmap {
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx).build()
        return Bitmap.createBitmap(widthPx, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { layout.draw(Canvas(it)) }
    }

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
