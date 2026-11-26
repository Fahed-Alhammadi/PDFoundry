package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.PagePoint
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfException
import io.pdfoundry.app.data.PdfSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "engine-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** A plain PDF whose pages say "<name> page N". */
    private fun makePdf(name: String, pages: Int, width: Int = 612, height: Int = 792): File {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            page.canvas.drawText("$name page ${index + 1}", 72f, 96f, paint)
            document.finishPage(page)
        }
        return File(folder, "$name.pdf").also { file ->
            file.outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    private fun pageCount(file: File) = PdfSource(file).use { it.pageCount }

    /** Renders a page at one pixel per point and returns the pixel at (x, y) measured from the top-left. */
    private fun pixel(file: File, page: Int, x: Int, y: Int): Int = PdfSource(file).use { source ->
        val width = source.sizes[page].width
        runBlocking { source.render(page, width) }!!.getPixel(x, y)
    }

    @Test
    fun mergeKeepsEveryPageInOrder() {
        val merged = File(folder, "merged.pdf")
        PdfEngine.merge(listOf(makePdf("Alpha", 2), makePdf("Beta", 3)), merged)

        assertEquals(5, pageCount(merged))
        assertEquals(listOf(0), PdfEngine.search(merged, "Alpha page 1").map { it.page })
        assertEquals(listOf(2), PdfEngine.search(merged, "Beta page 1").map { it.page })
        assertEquals(listOf(4), PdfEngine.search(merged, "Beta page 3").map { it.page })
    }

    @Test
    fun mergeRejectsUnreadableFiles() {
        val broken = File(folder, "broken.pdf").apply { writeText("not a pdf") }
        val destination = File(folder, "out.pdf")

        assertThrows(PdfException::class.java) { PdfEngine.merge(listOf(broken), destination) }
        assertFalse(destination.exists())
    }

    @Test
    fun extractCopiesOnlyChosenPages() {
        val source = makePdf("Source", 4)
        val extracted = File(folder, "extract.pdf")
        PdfEngine.extract(source, listOf(3, 1), extracted)

        assertEquals(2, pageCount(extracted))
        assertEquals(listOf(0), PdfEngine.search(extracted, "page 2").map { it.page })
        assertEquals(listOf(1), PdfEngine.search(extracted, "page 4").map { it.page })
        assertEquals(4, pageCount(source))
    }

    @Test
    fun pageOperationsChangeTheDocument() {
        val file = makePdf("Pages", 3)

        PdfEngine.move(file, 0, 2)
        assertEquals(listOf(2), PdfEngine.search(file, "page 1").map { it.page })

        PdfEngine.duplicate(file, listOf(0))
        assertEquals(4, pageCount(file))
        assertEquals(listOf(0, 1), PdfEngine.search(file, "page 2").map { it.page })

        PdfEngine.addBlankPage(file, 3)
        assertEquals(5, pageCount(file))

        PdfEngine.delete(file, listOf(4, 1))
        assertEquals(3, pageCount(file))

        PdfEngine.rotate(file, listOf(0), 90)
        PdfSource(file).use { assertTrue("A rotated page is displayed sideways", it.sizes[0].width > it.sizes[0].height) }

        assertThrows(PdfException::class.java) { PdfEngine.delete(file, listOf(0, 1, 2)) }
    }

    @Test
    fun signatureIsPaintedWhereTheUserPlacedIt() {
        val file = makePdf("Contract", 1)
        val signature = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

        // Lower-left quarter of the page, in fractions from the top-left corner.
        PdfEngine.stamp(file, 0, signature, RectF(0.2f, 0.7f, 0.5f, 0.8f))

        val inside = pixel(file, 0, (0.35f * 612).toInt(), (0.75f * 792).toInt())
        val outside = pixel(file, 0, (0.8f * 612).toInt(), (0.2f * 792).toInt())
        assertTrue("The stamp should be red where it was placed", Color.red(inside) > 200 && Color.green(inside) < 80)
        assertEquals(Color.WHITE, outside)
    }

    @Test
    fun signatureLandsInTheSamePlaceOnARotatedPage() {
        val file = makePdf("Sideways", 1)
        PdfEngine.rotate(file, listOf(0), 90)
        val signature = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        // The rotated page is displayed 792 wide and 612 tall. Place the stamp in its top-right corner.
        PdfEngine.stamp(file, 0, signature, RectF(0.8f, 0.05f, 0.95f, 0.25f))

        val inside = pixel(file, 0, (0.875f * 792).toInt(), (0.15f * 612).toInt())
        val oppositeCorner = pixel(file, 0, (0.125f * 792).toInt(), (0.85f * 612).toInt())
        assertTrue("The stamp should appear where the user saw it", Color.blue(inside) > 200 && Color.red(inside) < 80)
        assertEquals(Color.WHITE, oppositeCorner)
    }

    @Test
    fun drawingIsSavedIntoThePage() {
        val file = makePdf("Sketch", 1)
        val stroke = InkStroke(listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), Color.BLACK, 0.02f, highlighter = false)

        PdfEngine.ink(file, 0, listOf(stroke))

        val onLine = pixel(file, 0, 306, 396)
        assertTrue("The line should be dark", Color.red(onLine) < 60)
        assertEquals(Color.WHITE, pixel(file, 0, 306, 600))
    }

    @Test
    fun typedTextBecomesAVisibleImage() {
        val bitmap = PdfEngine.textBitmap("Approved")
        val canvas = Canvas(bitmap)
        assertTrue(bitmap.width > bitmap.height && canvas.width > 0)
        val hasInk = (0 until bitmap.width step 4).any { x -> (0 until bitmap.height step 4).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 } }
        assertTrue("The text should be drawn", hasInk)
    }

    @Test
    fun openingTheSameFileAgainDoesNotAddItAgain() = runBlocking {
        val store = DocumentStore(context)
        val before = store.directory.listFiles()!!.toSet()
        val report = makePdf("Duplicate check ${System.nanoTime()}", 2)
        val added = mutableListOf<File>()
        try {
            val first = store.import(listOf(Uri.fromFile(report)), "Duplicate check")
            added += first
            assertEquals(1, first.size)
            assertEquals(first, store.import(listOf(Uri.fromFile(report)), "Duplicate check"))

            // The same contents arriving under another name are still the same document.
            val copy = File(folder, "From another app.pdf").also { report.copyTo(it) }
            assertEquals(first, store.import(listOf(Uri.fromFile(copy), Uri.fromFile(report))))
            assertEquals(1, (store.directory.listFiles()!!.toSet() - before).size)

            // A different document with the same name is added beside it.
            val other = store.import(listOf(Uri.fromFile(makePdf("Other ${System.nanoTime()}", 3))), "Duplicate check")
            added += other
            assertEquals("Duplicate check 2.pdf", other.single().name)
        } finally {
            added.forEach { it.delete() }
        }
    }

    @Test
    fun libraryNamesNeverCollideOrEscapeTheFolder() {
        val store = DocumentStore(context)
        val first = store.uniqueFile("Engine Test Report")
        first.writeText("x")
        try {
            assertEquals("Engine Test Report 2.pdf", store.uniqueFile("Engine Test Report").name)
            assertEquals(store.directory, store.uniqueFile("../../etc/passwd").parentFile)
            assertEquals("Invoice", DocumentStore.sanitized("  Invoice.pdf "))
        } finally {
            first.delete()
        }
    }
}
