package io.pdfoundry.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.Closeable
import java.io.File

/**
 * Draws a page's marks (annotations) over a page that PdfRenderer rendered.
 *
 * PdfRenderer draws page content only, never annotations (checked on API 35; see AnnotationTest), so
 * the marks are rendered here with PdfBox from their appearance streams: once for marks drawn normally,
 * once for marks that multiply (highlights), which are blended onto the page pixel by pixel so the text
 * under them stays dark. Form-field widgets, links and popups are left to the reader as before.
 *
 * The document is loaded once, read-only; its pages' content is emptied in memory so only the marks are
 * drawn. Nothing is ever written back.
 */
class AnnotationLayer(private val file: File) : Closeable {
    private var document: PDDocument? = null
    private var failed = false
    private val emptied = HashSet<Int>()

    private fun document(): PDDocument? {
        if (failed) return null
        document?.let { return it }
        return try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { document = it }
        } catch (e: Throwable) {
            failed = true
            null
        }
    }

    /** True when the page has marks to draw. */
    private fun marksOn(document: PDDocument, pageIndex: Int): List<PDAnnotation> =
        runCatching { document.getPage(pageIndex).annotations }.getOrDefault(emptyList()).filter { drawn(it) }

    /** Paints the marks of [pageIndex] onto [page], a rendering of the displayed page at any size. */
    fun draw(pageIndex: Int, page: Bitmap) {
        try {
            val document = document() ?: return
            if (pageIndex !in 0 until document.numberOfPages) return
            val marks = marksOn(document, pageIndex)
            if (marks.isEmpty()) return
            if (emptied.add(pageIndex)) document.getPage(pageIndex).setContents(PDStream(document))
            val frame = PageFrame(document.getPage(pageIndex))
            val scale = page.width / frame.width
            val renderer = PDFRenderer(document)
            val multiplied = marks.filter { multiplies(it) }.toSet()
            if (marks.any { it !in multiplied }) {
                renderer.setAnnotationsFilter { it.cosObject in marks.filter { m -> m !in multiplied }.map { m -> m.cosObject } }
                val layer = renderer.renderImage(pageIndex, scale, ImageType.ARGB)
                Canvas(page).drawBitmap(layer, null, Rect(0, 0, page.width, page.height), Paint(Paint.FILTER_BITMAP_FLAG))
                layer.recycle()
            }
            if (multiplied.isNotEmpty()) {
                val cos = multiplied.map { it.cosObject }
                renderer.setAnnotationsFilter { it.cosObject in cos }
                val rendered = renderer.renderImage(pageIndex, scale, ImageType.ARGB)
                val layer = if (rendered.width == page.width && rendered.height == page.height) rendered else Bitmap.createScaledBitmap(rendered, page.width, page.height, true)
                multiply(layer, page)
                if (layer !== rendered) layer.recycle()
                rendered.recycle()
            }
        } catch (e: Throwable) {
            // A mark that can't be drawn must not keep the page from showing.
        }
    }

    override fun close() {
        runCatching { document?.close() }
        document = null
    }

    companion object {
        private val skipped = setOf("Widget", "Link", "Popup")

        fun drawn(a: PDAnnotation): Boolean =
            a.subtype !in skipped && !a.isHidden && !a.isNoView && a.normalAppearanceStream != null

        /** A highlight, or a mark whose appearance multiplies (the freehand highlighter). */
        fun multiplies(a: PDAnnotation): Boolean {
            if (a.subtype == "Highlight") return true
            val states = a.normalAppearanceStream?.resources?.cosObject?.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary ?: return false
            return states.values.any { (it.let { s -> (s as? com.tom_roush.pdfbox.cos.COSObject)?.`object` ?: s } as? COSDictionary)?.getNameAsString(COSName.BM) == "Multiply" }
        }

        /**
         * [page] = page × (1 − a) + (page × layer) × a, per channel: a multiply blend of the (unpremultiplied)
         * layer colour, weighted by its alpha.
         */
        fun multiply(layer: Bitmap, page: Bitmap) {
            val w = page.width
            val h = page.height
            val top = IntArray(w)
            val bottom = IntArray(w)
            for (y in 0 until h) {
                layer.getPixels(top, 0, w, 0, y, w, 1)
                page.getPixels(bottom, 0, w, 0, y, w, 1)
                var changed = false
                for (x in 0 until w) {
                    val s = top[x]
                    val a = s ushr 24
                    if (a == 0) continue
                    val d = bottom[x]
                    fun mix(sc: Int, dc: Int) = (dc * (255 - a) + dc * sc / 255 * a) / 255
                    val r = mix((s shr 16) and 0xFF, (d shr 16) and 0xFF)
                    val g = mix((s shr 8) and 0xFF, (d shr 8) and 0xFF)
                    val b = mix(s and 0xFF, d and 0xFF)
                    bottom[x] = (d and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                    changed = true
                }
                if (changed) page.setPixels(bottom, 0, w, 0, y, w, 1)
            }
        }
    }
}
