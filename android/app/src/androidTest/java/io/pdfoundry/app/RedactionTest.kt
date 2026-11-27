package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.Redaction
import io.pdfoundry.app.ui.EditMode
import io.pdfoundry.app.ui.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Redaction removes what is under the boxes from the whole file, and nothing else. */
@RunWith(AndroidJUnit4::class)
class RedactionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private val secret = "Swordfish"

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "redaction-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** Three pages of Helvetica (so the words are plain bytes in the content streams); the secret is on page 2. */
    private fun makePdf(name: String, rotateMiddle: Int = 0, cropMiddle: Boolean = false, form: Boolean = false): File {
        val file = File(folder, "$name.pdf")
        PDDocument().use { document ->
            val lines = listOf("Page one public text", "The code word is $secret here", "Page three public text")
            lines.forEachIndexed { index, line ->
                val page = PDPage(PDRectangle.LETTER)
                if (index == 1) {
                    page.rotation = rotateMiddle
                    if (cropMiddle) page.cropBox = PDRectangle(36f, 36f, 540f, 700f)
                }
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 24f)
                    stream.newLineAtOffset(72f, 600f)
                    stream.showText(line)
                    stream.endText()
                }
            }
            if (form) {
                val acro = PDAcroForm(document)
                document.documentCatalog.acroForm = acro
                val field = PDTextField(acro)
                field.partialName = "Code"
                val widget = field.widgets.first()
                widget.rectangle = PDRectangle(72f, 500f, 200f, 24f)
                widget.page = document.getPage(1)
                document.getPage(1).annotations = listOf(widget)
                acro.fields = listOf(field)
                acro.defaultResources = com.tom_roush.pdfbox.pdmodel.PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
                acro.defaultAppearance = "/Helv 12 Tf 0 g"
                field.setValue(secret)
            }
            document.save(file)
        }
        return file
    }

    private fun text(file: File, page: Int, password: String? = null): String =
        (if (password == null) PDDocument.load(file) else PDDocument.load(file, password)).use { document ->
            PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(document)
        }

    /** Every place the word could still be: decoded streams, strings, anywhere in the file's objects. */
    private fun traces(file: File, password: String? = null): List<String> {
        val found = mutableListOf<String>()
        (if (password == null) PDDocument.load(file) else PDDocument.load(file, password)).use { document ->
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
            // Every object in the file, referenced or not.
            document.document.objects.forEach { visit(it, "object ${it.objectNumber}") }
            visit(document.document.trailer, "trailer")
        }
        if (password == null && String(file.readBytes(), Charsets.ISO_8859_1).contains(secret)) found += "raw file bytes"
        return found
    }

    private fun secretRect(file: File): RectF = PdfEngine.words(file, 1).first { it.text == secret }.rect

    @Test
    fun redactedWordIsGoneFromTheWholeFile() {
        val file = makePdf("Three pages", form = true)
        val firstBefore = text(file, 0)
        val thirdBefore = text(file, 2)
        assertTrue("control: the word is in the file before", traces(file).isNotEmpty())

        Redaction.apply(file, mapOf(1 to listOf(secretRect(file))))

        // (a) Text extraction finds nothing on the page, and no trace of the word anywhere.
        assertFalse(text(file, 1).contains(secret))
        assertEquals("the redacted page is an image: no text at all", "", text(file, 1).trim())
        PDDocument.load(file).use { assertFalse(PDFTextStripper().getText(it).contains(secret)) }
        assertTrue(PdfEngine.search(file, secret).isEmpty())
        // (b) Not in any stream, string or object of the file (the form field's value included).
        assertEquals(emptyList<String>(), traces(file))
        // (c) The other pages are untouched.
        assertEquals(firstBefore, text(file, 0))
        assertEquals(thirdBefore, text(file, 2))
        assertEquals(3, PdfEngine.pageCount(file))
        PDDocument.load(file).use { document ->
            assertTrue("the field on the redacted page is gone", document.documentCatalog.acroForm.fields.isEmpty())
            assertEquals(0, document.getPage(1).annotations.size)
        }
    }

    @Test
    fun redactedPageKeepsSizeAndRotation() {
        val file = makePdf("Turned", rotateMiddle = 90, cropMiddle = true)
        val (media, crop) = PDDocument.load(file).use { it.getPage(1).mediaBox.toString() to it.getPage(1).cropBox.toString() }
        val rect = secretRect(file)

        Redaction.apply(file, mapOf(1 to listOf(rect)))

        PDDocument.load(file).use { document ->
            val page = document.getPage(1)
            assertEquals(90, page.rotation)
            assertEquals(media, page.mediaBox.toString())
            assertEquals(crop, page.cropBox.toString())
        }
        // The black box is where the word was shown; the rest of the page is white.
        val page = PdfSource(file).use { source -> runBlocking { source.render(1, source.sizes[1].width) }!! }
        fun black(x: Float, y: Float) = page.getPixel((x * page.width).toInt(), (y * page.height).toInt()).let { Color.red(it) < 30 && Color.green(it) < 30 && Color.blue(it) < 30 }
        assertTrue("the box is black", black(rect.centerX(), rect.centerY()))
        assertFalse("away from it is not", black(0.05f, 0.05f))
        assertFalse(traces(file).isNotEmpty())
    }

    @Test
    fun marksArePressedIntoTheRedactedPage() {
        val file = makePdf("Marked")
        val blue = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        PdfEngine.stamp(file, 1, blue, RectF(0.6f, 0.8f, 0.8f, 0.9f))
        Redaction.apply(file, mapOf(1 to listOf(secretRect(file))))

        PDDocument.load(file).use { assertEquals(0, it.getPage(1).annotations.size) }
        val page = PdfSource(file).use { source -> runBlocking { source.render(1, source.sizes[1].width) }!! }
        val p = page.getPixel((0.7f * page.width).toInt(), (0.85f * page.height).toInt())
        assertTrue("the stamp is now part of the picture", Color.blue(p) > 200 && Color.red(p) < 60)
    }

    @Test
    fun redactionKeepsPassword() {
        val plain = makePdf("Plain")
        val rect = secretRect(plain)
        val file = File(folder, "Protected.pdf")
        PdfEngine.protect(plain, "pw", file)

        Redaction.apply(file, mapOf(1 to listOf(rect)), password = "pw")

        assertTrue(PdfEngine.isProtected(file))
        assertEquals(io.pdfoundry.app.data.UnlockResult.Full, PdfEngine.access(file, "pw"))
        assertFalse(text(file, 1, "pw").contains(secret))
        assertEquals(emptyList<String>(), traces(file, "pw"))
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", done())
    }

    private fun idle(state: ReaderState) {
        Thread.sleep(100)
        waitUntil { !state.busy }
        Thread.sleep(100)
        waitUntil { !state.busy }
    }

    private fun withReader(file: File, password: String? = null, block: (ReaderState) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default)
        val state = ReaderState(context.applicationContext, file, scope)
        try {
            state.open()
            waitUntil { state.source != null || state.locked }
            idle(state)
            if (password != null) {
                state.unlock(password) {}
                waitUntil { state.source != null && !state.locked }
                idle(state)
            }
            block(state)
        } finally {
            state.close()
            Thread.sleep(300)
            scope.cancel()
        }
    }

    @Test
    fun undoRedactionRestoresText() {
        val file = makePdf("Undo")
        val rect = secretRect(file)
        withReader(file) { state ->
            state.begin(EditMode.Redact, 1)
            state.addRedaction(1, rect)
            assertEquals(1, state.pendingCount)
            // Pending boxes are never written.
            assertTrue(text(file, 1).contains(secret))
            state.applyRedactions()
            idle(state)
            assertEquals(null, state.editMode)
            assertEquals(0, state.pendingCount)
            assertFalse(text(file, 1).contains(secret))
            state.undo()
            idle(state)
            assertTrue(text(file, 1).contains(secret))
        }
    }

    @Test
    fun discardingChangesNothing() {
        val file = makePdf("Discard")
        val before = file.readBytes()
        withReader(file) { state ->
            state.begin(EditMode.Redact, 1)
            state.addRedaction(1, secretRect(file))
            state.discardRedactions()
            idle(state)
            assertEquals(0, state.pendingCount)
            assertTrue(before.contentEquals(file.readBytes()))
        }
    }

    @Test
    fun redactingAnUnlockedDocumentKeepsItsPassword() {
        val plain = makePdf("Reader plain")
        val rect = secretRect(plain)
        val file = File(folder, "Reader protected.pdf")
        PdfEngine.protect(plain, "pw", file)
        withReader(file, password = "pw") { state ->
            state.begin(EditMode.Redact, 1)
            state.addRedaction(1, rect)
            state.applyRedactions()
            idle(state)
        }
        assertTrue(PdfEngine.isProtected(file))
        assertEquals(io.pdfoundry.app.data.UnlockResult.Full, PdfEngine.access(file, "pw"))
        assertFalse(text(file, 1, "pw").contains(secret))
        assertEquals(emptyList<String>(), traces(file, "pw"))
    }
}
