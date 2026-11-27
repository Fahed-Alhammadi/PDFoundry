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
        // Pictures first, from the file as it is now (PdfRenderer for the content, the marks on top).
        val pictures = HashMap<Int, Bitmap>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    AnnotationLayer(file, widgets = true).use { marks ->
                        for ((index, rects) in boxes) {
                            if (index !in 0 until renderer.pageCount) continue
                            renderer.openPage(index).use { page ->
                                var scale = DPI / 72f
                                val pixels = page.width.toLong() * page.height * scale * scale
                                if (pixels > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                marks.draw(index, bitmap)
                                val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                                val canvas = Canvas(bitmap)
                                for (r in rects) canvas.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, black)
                                bitmap.setHasAlpha(false)
                                pictures[index] = bitmap
                            }
                        }
                    }
                }
            }
            PdfEngine.edit(file) { document ->
                for ((index, picture) in pictures) replace(document, index, picture)
            }
        } finally {
            pictures.values.forEach { it.recycle() }
        }
    }

    private fun replace(document: PDDocument, index: Int, picture: Bitmap) {
        val page = document.getPage(index)
        val frame = PageFrame(page)
        removeWidgets(document, page)
        scrubStructure(document, page)
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

    /** Drops alternate text, actual text and expansions of structure elements on the page: they can hold its words. */
    private fun scrubStructure(document: PDDocument, page: PDPage) {
        val root = document.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as? COSDictionary ?: return
        val seen = HashSet<COSDictionary>()
        fun visit(node: COSBase?) {
            val value = (node as? COSObject)?.`object` ?: node
            when (value) {
                is COSArray -> for (i in 0 until value.size()) visit(value.get(i))
                is COSDictionary -> {
                    if (!seen.add(value)) return
                    if (value.getDictionaryObject(COSName.PG) == page.cosObject) {
                        for (key in listOf("Alt", "ActualText", "E", "T")) value.removeItem(COSName.getPDFName(key))
                    }
                    visit(value.getItem(COSName.K))
                }
                else -> Unit
            }
        }
        visit(root.getItem(COSName.K))
    }
}
