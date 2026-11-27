package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import io.pdfoundry.app.data.AnnotationLayer
import io.pdfoundry.app.data.ExportImages
import io.pdfoundry.app.data.ImageFormat
import io.pdfoundry.app.data.Ocr
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.Redaction
import io.pdfoundry.app.ui.EditMode
import io.pdfoundry.app.ui.ReaderState
import io.pdfoundry.app.ui.replaceWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** The fixes from the review of the markup and tools work. */
@RunWith(AndroidJUnit4::class)
class MarkupReviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private val secret = "Swordfish"

    @Before
    fun setUp() {
        PdfEngine.init(context)
        AnnotationLayer.failForTest = false
        folder = File(context.cacheDir, "markup-review-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        AnnotationLayer.failForTest = false
        folder.deleteRecursively()
    }

    private fun text(stream: PDPageContentStream, words: String, x: Float = 72f, y: Float = 600f) {
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 18f)
        stream.newLineAtOffset(x, y)
        stream.showText(words)
        stream.endText()
    }

    /** An appearance with [words] in Helvetica, or a blue box when [words] is null. */
    private fun appearance(document: PDDocument, w: Float, h: Float, words: String?): PDAppearanceStream =
        PDAppearanceStream(document).apply {
            bBox = PDRectangle(0f, 0f, w, h)
            resources = PDResources()
            PDPageContentStream(document, this).use { s ->
                if (words == null) { s.setNonStrokingColor(0f, 0f, 1f); s.addRect(0f, 0f, w, h); s.fill() } else text(s, words, 2f, 4f)
            }
        }

    /**
     * Two pages. Page 1 holds the secret everywhere it can hide: its content, a form XObject reached by a
     * structure MCR, a comment, a link, a filled field (value and appearance), structure text (one element
     * inheriting /Pg from its parent), the form's /CO and an XFA dataset.
     */
    private fun makeTagged(name: String): File {
        val file = File(folder, "$name.pdf")
        PDDocument().use { document ->
            val first = PDPage(PDRectangle.LETTER)
            val second = PDPage(PDRectangle.LETTER)
            document.addPage(first)
            document.addPage(second)
            val form = PDFormXObject(document).apply {
                bBox = PDRectangle(0f, 0f, 300f, 40f)
                resources = PDResources()
                PDPageContentStream(document, this, cosObject.createOutputStream(COSName.FLATE_DECODE)).use { text(it, "$secret in a form", 2f, 10f) }
            }
            PDPageContentStream(document, first).use { s ->
                text(s, "The code word is $secret")
                s.drawForm(form)
            }
            PDPageContentStream(document, second).use { text(it, "Page two stays as it is") }

            val comment = PDAnnotationText().apply {
                rectangle = PDRectangle(400f, 700f, 20f, 20f)
                contents = "$secret comment"
                appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(document, 20f, 20f, secret)) }
            }
            val link = PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 590f, 200f, 20f)
                contents = "$secret link"
            }
            val acro = PDAcroForm(document)
            document.documentCatalog.acroForm = acro
            acro.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acro.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = PDTextField(acro).apply { partialName = "Code" }
            val widget = field.widgets.first().apply {
                rectangle = PDRectangle(72f, 500f, 200f, 24f)
                page = first
            }
            acro.fields = listOf(field)
            field.setValue(secret)
            first.annotations = listOf(comment, link, widget)
            acro.cosObject.setItem(COSName.CO, COSArray().apply { add(field.cosObject) })
            val xfa = document.document.createCOSStream().apply { createOutputStream().use { it.write("<xfa:datasets><Code>$secret</Code></xfa:datasets>".toByteArray()) } }
            acro.cosObject.setItem(COSName.XFA, xfa)

            // Structure: Document > P (on page 1, with ActualText) > Span (inherits /Pg) + OBJR to each mark + MCR into the form.
            val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
            val paragraph = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setName(COSName.S, "P")
                setItem(COSName.PG, first.cosObject); setString(COSName.getPDFName("ActualText"), "$secret actual")
            }
            val span = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setName(COSName.S, "Span")
                setItem(COSName.P, paragraph); setString(COSName.getPDFName("Alt"), "$secret alt")
                setItem(COSName.K, COSInteger.get(0))
            }
            fun objr(annotation: COSDictionary) = COSDictionary().apply { setName(COSName.TYPE, "OBJR"); setItem(COSName.getPDFName("Obj"), annotation) }
            val mcr = COSDictionary().apply { setName(COSName.TYPE, "MCR"); setItem(COSName.getPDFName("Stm"), form.cosObject); setInt(COSName.getPDFName("MCID"), 0) }
            paragraph.setItem(COSName.K, COSArray().apply { add(span); add(objr(comment.cosObject)); add(objr(link.cosObject)); add(objr(widget.cosObject)); add(mcr) })
            root.setItem(COSName.K, paragraph)
            paragraph.setItem(COSName.P, root)
            document.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, root)
            document.documentCatalog.cosObject.setItem(COSName.MARK_INFO, COSDictionary().apply { setBoolean(COSName.getPDFName("Marked"), true) })
            document.save(file)
        }
        return file
    }

    /** Every place in the file (any object, decoded stream or string) that still holds the secret. */
    private fun traces(file: File): List<String> {
        val found = mutableListOf<String>()
        PDDocument.load(file).use { document ->
            val seen = HashSet<Any>()
            fun visit(base: COSBase?, where: String) {
                val value = (base as? COSObject)?.`object` ?: base
                if (value == null || !seen.add(value)) return
                when (value) {
                    is COSStream -> {
                        val decoded = runCatching { value.createInputStream().use { it.readBytes() } }.getOrDefault(ByteArray(0))
                        if (String(decoded, Charsets.ISO_8859_1).contains(secret)) found += "stream in $where"
                        value.entrySet().forEach { (k, v) -> visit(v, "$where/${k.name}") }
                    }
                    is COSDictionary -> value.entrySet().forEach { (k, v) -> visit(v, "$where/${k.name}") }
                    is COSArray -> for (i in 0 until value.size()) visit(value.get(i), "$where[$i]")
                    is COSString -> if (value.string.contains(secret)) found += "string in $where"
                    else -> Unit
                }
            }
            document.document.objects.forEach { visit(it, "object ${it.objectNumber}") }
            visit(document.document.trailer, "trailer")
        }
        if (String(file.readBytes(), Charsets.ISO_8859_1).contains(secret)) found += "raw file bytes"
        return found
    }

    // 1. Structure tree, /CO, XFA ----------------------------------------------------------------

    @Test
    fun redactionLeavesNothingInStructureFormOrXfa() {
        val file = makeTagged("Tagged")
        assertTrue("control: the secret is in many places", traces(file).size >= 5)

        Redaction.apply(file, mapOf(0 to listOf(RectF(0.1f, 0.2f, 0.6f, 0.26f))))

        assertEquals(emptyList<String>(), traces(file))
        PDDocument.load(file).use { document ->
            val form = document.documentCatalog.acroForm
            assertTrue(form.fields.isEmpty())
            assertNull("/CO lists no removed field", form.cosObject.getDictionaryObject(COSName.CO))
            assertNull(form.cosObject.getDictionaryObject(COSName.XFA))
            assertTrue(document.getPage(1).annotations.isEmpty())
        }
        assertTrue(PdfEngine.search(file, "Page two").isNotEmpty())
    }

    // 3. Strict drawing -----------------------------------------------------------------------------

    private fun stamped(name: String): File {
        val file = makeTagged(name)
        PdfEngine.stamp(file, 0, Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }, RectF(0.6f, 0.8f, 0.8f, 0.9f))
        return file
    }

    @Test
    fun redactionFailsRatherThanDropMarksItCouldNotDraw() {
        val file = stamped("Strict")
        val before = file.readBytes()
        AnnotationLayer.failForTest = true
        org.junit.Assert.assertThrows(Exception::class.java) { Redaction.apply(file, mapOf(0 to listOf(RectF(0.1f, 0.1f, 0.2f, 0.2f)))) }
        assertTrue("nothing changed", before.contentEquals(file.readBytes()))
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", done())
    }

    private fun idle(state: ReaderState) {
        Thread.sleep(150)
        waitUntil { !state.busy }
        Thread.sleep(150)
        waitUntil { !state.busy }
    }

    private fun withReader(file: File, block: (ReaderState) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default)
        val state = ReaderState(context.applicationContext, file, scope)
        try {
            state.open()
            waitUntil { state.source != null }
            idle(state)
            block(state)
        } finally {
            state.close()
            Thread.sleep(300)
            scope.cancel()
        }
    }

    // 2 + 3. Failures inside a change, out of memory included, change nothing and keep the boxes -----

    @Test
    fun aFailedRedactionKeepsTheBoxesAndTheFile() {
        val file = stamped("Kept")
        val before = file.readBytes()
        withReader(file) { state ->
            state.begin(EditMode.Redact, 0)
            state.addRedaction(0, RectF(0.1f, 0.1f, 0.2f, 0.2f))
            AnnotationLayer.failForTest = true
            state.applyRedactions()
            idle(state)
            AnnotationLayer.failForTest = false
            assertTrue(before.contentEquals(file.readBytes()))
            assertEquals("the boxes are still there", 1, state.pendingCount)
            assertEquals("back in Redact", EditMode.Redact, state.editMode)
        }
    }

    @Test
    fun runningOutOfMemoryInAChangeRestoresTheDocument() {
        val file = stamped("Memory")
        val before = file.readBytes()
        withReader(file) { state ->
            state.edit { target ->
                PdfEngine.rotate(target, listOf(0), 90)
                throw OutOfMemoryError("test")
            }
            idle(state)
            assertTrue(before.contentEquals(file.readBytes()))
            assertTrue(state.message.orEmpty().contains("memory"))
            assertTrue(state.source != null)
        }
    }

    // 9. Pending boxes never outlive a change to the pages ------------------------------------------

    @Test
    fun undoOrAnotherChangeDropsPendingBoxes() {
        val file = stamped("Pending")
        withReader(file) { state ->
            state.begin(EditMode.Redact, 0)
            state.addRedaction(0, RectF(0.1f, 0.1f, 0.2f, 0.2f))
            state.undo()
            idle(state)
            assertEquals(0, state.pendingCount)
            assertNull(state.editMode)

            state.begin(EditMode.Redact, 0)
            state.addRedaction(0, RectF(0.1f, 0.1f, 0.2f, 0.2f))
            state.edit { PdfEngine.rotate(it, listOf(0), 90) }
            idle(state)
            assertEquals(0, state.pendingCount)

            state.begin(EditMode.Redact, 0)
            state.addRedaction(0, RectF(0.1f, 0.1f, 0.2f, 0.2f))
            state.revertToOriginal()
            idle(state)
            assertEquals(0, state.pendingCount)
        }
    }

    // 4. Export draws filled fields and caps huge pages ---------------------------------------------

    @Test
    fun exportShowsFieldsAndCapsHugePages() {
        val file = File(folder, "Field.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val acro = PDAcroForm(document)
            document.documentCatalog.acroForm = acro
            val field = PDTextField(acro).apply { partialName = "Box" }
            val widget = field.widgets.first().apply {
                rectangle = PDRectangle(100f, 100f, 200f, 100f)
                this.page = page
                appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(document, 200f, 100f, null)) }
            }
            acro.fields = listOf(field)
            page.annotations = listOf(widget)
            document.addPage(PDPage(PDRectangle(3000f, 3000f)))
            document.save(file)
        }
        val out = ExportImages.run(file, listOf(0, 1), ImageFormat.Png, 300, File(folder, "out"), "Field")
        val picture = BitmapFactory.decodeFile(out[0].path)
        // The field's box: x 100..300 pt, y 100..200 pt from the bottom of a 792 pt page.
        val p = picture.getPixel((200f / 612 * picture.width).toInt(), ((792f - 150f) / 792 * picture.height).toInt())
        assertTrue("the field is drawn", Color.blue(p) > 200 && Color.red(p) < 60)
        val big = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(out[1].path, it) }
        assertTrue("capped: ${big.outWidth}×${big.outHeight}", big.outWidth.toLong() * big.outHeight <= ExportImages.MAX_PIXELS * 101 / 100)
    }

    // 6. Old marks are deleted by what they are, not their place in the list ----------------------------

    @Test
    fun deletingOldMarksOneAfterAnotherDeletesBoth() {
        val file = File(folder, "Old.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val stamps = listOf(100f, 300f).map { x ->
                com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp().apply {
                    rectangle = PDRectangle(x, 100f, 100f, 50f)
                    appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(document, 100f, 50f, null)) }
                }
            }
            page.annotations = stamps
            document.save(file)
        }
        // Both ids come from one listing, as the reader holds them while the first delete is saving.
        val marks = PdfEngine.marks(file, 0)
        assertEquals(2, marks.size)
        PdfEngine.deleteMark(file, 0, marks[0].id)
        PdfEngine.deleteMark(file, 0, marks[1].id)
        assertEquals(0, PDDocument.load(file).use { it.getPage(0).annotations.size })
    }

    // 7. OCR Cancel stops the page being recognised ---------------------------------------------------

    @Test
    fun ocrCancelStopsTheCurrentPage() {
        fun scan(): File {
            val f = File(folder, "Dense-${System.nanoTime()}.pdf")
            val picture = Bitmap.createBitmap(1700, 2200, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.BLACK }
                val canvas = android.graphics.Canvas(this)
                for (line in 0 until 70) canvas.drawText("Line $line of a dense scanned page with many words to read slowly, twice: dense scanned page with many words", 60f, 100f + line * 29f, paint)
            }
            PdfEngine.imagesToPdf(listOf(picture), f)
            return f
        }
        val full = scan()
        val start = System.currentTimeMillis()
        Ocr.run(full, listOf(0))
        val whole = System.currentTimeMillis() - start
        assumeTrue("the page takes long enough to measure ($whole ms)", whole > 1200)

        val cancelled = scan()
        val bytes = cancelled.readBytes()
        val began = System.currentTimeMillis()
        try {
            Ocr.run(cancelled, listOf(0), isCancelled = { System.currentTimeMillis() - began > 300 })
            org.junit.Assert.fail("cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        val took = System.currentTimeMillis() - began
        assertTrue("Cancel stopped the page in $took ms (a whole page takes $whole ms)", took < whole * 6 / 10)
        assertTrue(bytes.contentEquals(cancelled.readBytes()))
    }

    // 8. The mark layer reads the same version of the file as the page renderer ------------------------

    @Test
    fun marksComeFromTheSameVersionAsThePage() {
        val plain = stamped("Version").also { file ->
            // Start without the stamp: remove the annotations.
            PDDocument.load(file).use { d -> d.getPage(1).annotations = emptyList(); d.getPage(0).annotations = emptyList(); d.save(File(folder, "plain.pdf")) }
        }
        val file = File(folder, "plain.pdf")
        PdfSource(file).use { source ->
            // The file is replaced (as Undo does) while it is open.
            file.replaceWith(plain)
            val page = runBlocking { source.render(0, 612) }!!
            val p = page.getPixel((0.7f * page.width).toInt(), (0.85f * page.height).toInt())
            assertTrue("no stamp from the newer version over the older page", !(Color.blue(p) > 200 && Color.red(p) < 60))
        }
    }
}
