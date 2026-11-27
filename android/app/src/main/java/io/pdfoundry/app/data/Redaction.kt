package io.pdfoundry.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

/**
 * Redaction: every page with boxes is replaced by a picture of itself (its content, marks and form
 * fields, at 300 dpi, lossless) with the boxes painted solid black. The page's own content, resources,
 * marks and field widgets are dropped, and the whole file is written anew, so nothing that was under a
 * box survives anywhere in it. Page size, rotation and position stay; other pages are untouched.
 */
object Redaction {
    const val DPI = 300
    /** Keeps a very large page's picture within memory: about 3300 × 5100 pixels at most. */
    private const val MAX_PIXELS = 17_000_000L

    /**
     * Applies [boxes] (page index → rects in fractions of the displayed page) to [file]. With [password],
     * [file] is opened with it and saved protected with it (AES-256, as Password-protect does).
     */
    fun apply(file: File, boxes: Map<Int, List<RectF>>, password: String? = null) {
        val marked = boxes.filterValues { it.isNotEmpty() }
        if (marked.isEmpty()) return
        if (password == null) {
            applyPlain(file, marked)
            return
        }
        val work = File(file.parentFile, "${file.name}.redact.${System.nanoTime()}.pdf")
        try {
            if (PdfEngine.unlock(file, password, work, evenIfLimited = false) != UnlockResult.Full) throw PdfException("Password-protected documents are read-only.")
            applyPlain(work, marked)
            PdfEngine.protect(work, password, file)
        } finally {
            work.delete()
        }
    }

    private fun applyPlain(file: File, boxes: Map<Int, List<RectF>>) {
        // The pictures are made from the file as it is now (PdfRenderer for the content, the marks and
        // fields on top), one page at a time: each is drawn, written into the new file and freed before
        // the next, so only one page picture is ever in memory.
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                // Strict: a mark or field that can't be drawn fails the redaction, rather than being deleted unseen.
                AnnotationLayer(file, widgets = true, strict = true).use { marks ->
                    PdfEngine.edit(file) { document ->
                        val dropped = HashSet<COSDictionary>()
                        for ((index, rects) in boxes.toSortedMap()) {
                            if (index !in 0 until renderer.pageCount || index !in 0 until document.numberOfPages) continue
                            val picture = picture(renderer, marks, index, rects)
                            try {
                                replace(document, index, picture, dropped)
                            } finally {
                                picture.recycle()
                            }
                        }
                        unlink(document, dropped)
                        scrubDocument(document)
                    }
                }
            }
        }
    }

    /** Page [index] as shown (content, marks and fields) at 300 dpi, with [rects] painted black. */
    private fun picture(renderer: PdfRenderer, marks: AnnotationLayer, index: Int, rects: List<RectF>): Bitmap =
        renderer.openPage(index).use { page ->
            var scale = DPI / 72f
            val pixels = page.width.toLong() * page.height * scale * scale
            if (pixels > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
            val w = Math.round(page.width * scale).coerceAtLeast(1)
            val h = Math.round(page.height * scale).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                marks.draw(index, bitmap)
                val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                val canvas = Canvas(bitmap)
                for (r in rects) canvas.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, black)
                bitmap.setHasAlpha(false)
                bitmap
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }
        }

    /**
     * What can still hold a redacted page's words outside the page itself: the structure tree (alternate
     * and actual text, references to the page's dropped marks, fields and content streams) and outline
     * links into it, the form's calculation order and its XFA copy of every field value. The structure
     * tree goes as a whole: scrubbing it piece by piece is easy to get wrong, and a page that is now a
     * picture has no tagged content anyway. Tags on the other pages are lost with it (known limit).
     */
    private fun scrubDocument(document: PDDocument) {
        val catalog = document.documentCatalog.cosObject
        catalog.removeItem(COSName.STRUCT_TREE_ROOT)
        catalog.removeItem(COSName.MARK_INFO)
        for (page in document.pages) page.cosObject.removeItem(COSName.STRUCT_PARENTS)
        for (page in document.pages) runCatching { page.annotations }.getOrDefault(emptyList()).forEach { it.cosObject.removeItem(COSName.STRUCT_PARENT) }
        // Outline items can point at structure elements (/SE).
        val seen = HashSet<COSDictionary>()
        fun outline(item: COSDictionary?) {
            var node = item
            while (node != null && seen.add(node)) {
                node.removeItem(COSName.getPDFName("SE"))
                outline(node.getDictionaryObject(COSName.FIRST) as? COSDictionary)
                node = node.getDictionaryObject(COSName.NEXT) as? COSDictionary
            }
        }
        outline((catalog.getDictionaryObject(COSName.OUTLINES) as? COSDictionary)?.getDictionaryObject(COSName.FIRST) as? COSDictionary)
        val form = catalog.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
        // XFA holds its own copy of every value.
        form.removeItem(COSName.XFA)
        // The calculation order may list fields that were removed with the page's widgets.
        val co = form.getDictionaryObject(COSName.CO) as? COSArray ?: return
        val live = HashSet<COSDictionary>()
        fun collect(kids: COSArray?) {
            if (kids == null) return
            for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { if (live.add(it)) collect(it.getDictionaryObject(COSName.KIDS) as? COSArray) }
        }
        collect(form.getDictionaryObject(COSName.FIELDS) as? COSArray)
        val kept = COSArray()
        for (i in 0 until co.size()) (co.getObject(i) as? COSDictionary)?.takeIf { it in live }?.let { kept.add(it) }
        if (kept.size() == 0) form.removeItem(COSName.CO) else form.setItem(COSName.CO, kept)
    }

    private fun replace(document: PDDocument, index: Int, picture: Bitmap, dropped: MutableSet<COSDictionary>) {
        val page = document.getPage(index)
        val frame = PageFrame(page)
        removeWidgets(document, page)
        dropped += runCatching { page.annotations }.getOrDefault(emptyList()).map { it.cosObject }
        val dict = page.cosObject
        for (key in listOf(COSName.ANNOTS, COSName.getPDFName("Thumb"), COSName.getPDFName("PieceInfo"), COSName.METADATA, COSName.getPDFName("B"), COSName.getPDFName("AA"))) {
            dict.removeItem(key)
        }
        // A fresh, empty resource dictionary (not the old one, which other pages may share).
        page.resources = PDResources()
        val image = LosslessFactory.createFromImage(document, picture)
        // Writing a new content stream replaces /Contents entirely.
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { stream ->
            stream.transform(frame.toPage)
            stream.drawImage(image, 0f, 0f, frame.width, frame.height)
        }
    }

    /** Takes the page's widgets out of the form; a field left with none is removed with its value. */
    private fun removeWidgets(document: PDDocument, page: PDPage) {
        val form = document.documentCatalog.acroForm ?: return
        val onPage = runCatching { page.annotations }.getOrDefault(emptyList()).filter { it.subtype == "Widget" }.map { it.cosObject }.toSet()
        if (onPage.isEmpty()) return
        val fields = form.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return
        prune(fields, onPage)
    }

    /** Removes from [kids] every widget in [widgets], and every field that is left without widgets. */
    private fun prune(kids: COSArray, widgets: Set<COSDictionary>): Boolean {
        var i = 0
        while (i < kids.size()) {
            val kid = kids.getObject(i) as? COSDictionary
            if (kid == null) { i++; continue }
            val children = kid.getDictionaryObject(COSName.KIDS) as? COSArray
            val remove = when {
                kid in widgets -> true
                children != null -> {
                    prune(children, widgets)
                    children.size() == 0
                }
                else -> false
            }
            if (remove) kids.remove(i) else i++
        }
        return kids.size() == 0
    }

    /** Removes links from marks on other pages (replies, popups) to the marks dropped with a redacted page. */
    private fun unlink(document: PDDocument, dropped: Set<COSDictionary>) {
        if (dropped.isEmpty()) return
        for (page in document.pages) {
            for (annotation in runCatching { page.annotations }.getOrDefault(emptyList())) {
                val dict = annotation.cosObject
                for (key in listOf(COSName.getPDFName("IRT"), COSName.PARENT, COSName.getPDFName("Popup"))) {
                    if (dict.getDictionaryObject(key) in dropped) dict.removeItem(key)
                }
            }
        }
    }
}
