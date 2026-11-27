package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import io.pdfoundry.app.data.Ocr
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.UnlockResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** Make Searchable: Tesseract with the bundled English and Arabic data. */
@RunWith(AndroidJUnit4::class)
class OcrTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "ocr-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** A "scan": the words drawn into a picture at 200 dpi by Android (so Arabic is shaped), nothing else. */
    private fun scan(text: String): Bitmap = Bitmap.createBitmap(1700, 2200, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 110f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        val x = if (text.any { it in '؀'..'ۿ' }) 1700f - 200f - paint.measureText(text) else 200f
        Canvas(this).drawText(text, x, 500f, paint)
    }

    /** Page 1: a scan of "Invoice total"; page 2: real text; page 3: a scan of "فاتورة". */
    private fun makeMixed(name: String): File {
        val file = File(folder, "$name.pdf")
        PDDocument().use { document ->
            for (index in 0 until 3) {
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    when (index) {
                        1 -> {
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, 18f)
                            stream.newLineAtOffset(72f, 600f)
                            stream.showText("Typed page with real text")
                            stream.endText()
                        }
                        else -> stream.drawImage(LosslessFactory.createFromImage(document, scan(if (index == 0) "Invoice total" else "فاتورة")), 0f, 0f, 612f, 792f)
                    }
                }
            }
            document.save(file)
        }
        return file
    }

    private fun render(file: File, page: Int, password: String? = null): Bitmap {
        val view = if (password == null) file else File(folder, "view-${System.nanoTime()}.pdf").also { PdfEngine.unlock(file, password, it) }
        return PdfSource(view).use { source -> runBlocking { source.render(page, source.sizes[page].width) }!! }
    }

    private fun differentPixels(a: Bitmap, b: Bitmap): Double {
        var n = 0
        for (x in 0 until a.width) for (y in 0 until a.height) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (Math.abs(Color.red(p) - Color.red(q)) > 8 || Math.abs(Color.green(p) - Color.green(q)) > 8 || Math.abs(Color.blue(p) - Color.blue(q)) > 8) n++
        }
        return n.toDouble() / (a.width * a.height)
    }

    @Test
    fun mixedDocumentBecomesSearchable() {
        val file = makeMixed("Mixed")
        assertEquals(listOf(0, 2), Ocr.pagesNeedingText(file))
        val before = render(file, 0)
        val beforeArabic = render(file, 2)
        val steps = mutableListOf<Pair<Int, Int>>()

        val pages = Ocr.run(file, Ocr.pagesNeedingText(file), progress = { done, total -> steps += done to total })

        assertEquals(2, pages)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps)
        val english = PdfEngine.search(file, "Invoice")
        assertEquals("found on page 1: $english", listOf(0), english.map { it.page })
        val arabic = PdfEngine.search(file, "فاتورة")
        assertEquals("found on page 3: $arabic (page text: ${PdfEngine.words(file, 2).map { it.text }})", listOf(2), arabic.map { it.page })
        // The real text page is left alone and still found.
        assertEquals(listOf(1), PdfEngine.search(file, "real text").map { it.page })
        // The pages look exactly as they did.
        assertTrue(differentPixels(before, render(file, 0)) <= 0.01)
        assertTrue(differentPixels(beforeArabic, render(file, 2)) <= 0.01)
        // Now every page has text.
        assertEquals(emptyList<Int>(), Ocr.pagesNeedingText(file))
        // And the words can be selected for markup where they are.
        val invoice = PdfEngine.words(file, 0).first { it.text.contains("Invoice") }
        assertTrue("near the top of the page: ${invoice.rect}", invoice.rect.top in 0.15f..0.26f)
    }

    @Test
    fun ocrCancelChangesNothing() {
        val file = makeMixed("Cancelled")
        val bytes = file.readBytes()
        var calls = 0
        try {
            Ocr.run(file, listOf(0, 2), isCancelled = { ++calls > 1 })
            org.junit.Assert.fail("cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue("the file is byte for byte the same", bytes.contentEquals(file.readBytes()))
        assertEquals("no work files are left", listOf(file.name), folder.list()!!.toList())
    }

    @Test
    fun nothingFoundChangesNothing() {
        val file = File(folder, "Blank.pdf")
        PDDocument().use { it.addPage(PDPage(PDRectangle.LETTER)); it.save(file) }
        val bytes = file.readBytes()
        assertEquals(0, Ocr.run(file, listOf(0)))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun ocrKeepsPassword() {
        val file = File(folder, "Locked.pdf")
        PdfEngine.protect(makeMixed("Locked plain"), "pw", file)
        assertEquals(listOf(0, 2), Ocr.pagesNeedingText(file, "pw"))

        assertEquals(1, Ocr.run(file, listOf(0), password = "pw"))

        assertTrue(PdfEngine.isProtected(file))
        assertEquals(UnlockResult.Full, PdfEngine.access(file, "pw"))
        assertEquals(listOf(2), Ocr.pagesNeedingText(file, "pw"))
    }

    @Test
    fun arabicIsStoredInVisualOrder() {
        assertEquals("cba", Ocr.visualOrder("abc"))
        // A mark stays after its letter.
        assertEquals("بَا", Ocr.visualOrder("ابَ"))
    }
}
