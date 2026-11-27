package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.pdfoundry.app.data.Compress
import io.pdfoundry.app.data.CompressLevel
import io.pdfoundry.app.data.ExportImages
import io.pdfoundry.app.data.ImageFormat
import io.pdfoundry.app.data.NumberFormat
import io.pdfoundry.app.data.NumberPosition
import io.pdfoundry.app.data.PageNumberStyle
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.UnlockResult
import io.pdfoundry.app.data.WatermarkStyle
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
import java.security.MessageDigest

/** Page Numbers, Watermark, Compress and Export as Images. */
@RunWith(AndroidJUnit4::class)
class PageToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "page-tools-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, pages: Int = 3, rotateFirst: Int = 0, text: Boolean = true, photo: Bitmap? = null): File {
        val file = File(folder, "$name.pdf")
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.LETTER)
                if (index == 0) page.rotation = rotateFirst
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    if (photo != null && index == 0) stream.drawImage(LosslessFactory.createFromImage(document, photo), 72f, 300f, 400f, 400f)
                    if (text) {
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 18f)
                        stream.newLineAtOffset(72f, 200f)
                        stream.showText("Body text on page ${index + 1}")
                        stream.endText()
                    }
                }
            }
            document.save(file)
        }
        return file
    }

    private fun text(file: File, page: Int, password: String? = null): String =
        (if (password == null) PDDocument.load(file) else PDDocument.load(file, password)).use { document ->
            PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(document)
        }

    private fun render(file: File, page: Int = 0): Bitmap = PdfSource(file).use { source -> runBlocking { source.render(page, source.sizes[page].width) }!! }

    private fun Bitmap.count(x0: Float, y0: Float, x1: Float, y1: Float, match: (Int) -> Boolean): Int {
        var n = 0
        for (x in (x0 * width).toInt() until (x1 * width).toInt()) for (y in (y0 * height).toInt() until (y1 * height).toInt()) if (match(getPixel(x, y))) n++
        return n
    }

    private fun dark(p: Int) = Color.red(p) < 110 && Color.green(p) < 110 && Color.blue(p) < 110
    private fun md5(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // Page numbers ------------------------------------------------------------------------------

    @Test
    fun numbersOnRotatedPageWhereReaderSeesThem() {
        val file = makePdf("Numbers", rotateFirst = 90, text = false)
        PdfEngine.addPageNumbers(file, PageNumberStyle(NumberPosition.BottomCenter, NumberFormat.PageOf))

        assertTrue(text(file, 0).contains("Page 1 of 3"))
        assertTrue(text(file, 2).contains("Page 3 of 3"))
        // The first page is shown 792 wide and 612 tall: the number sits at the bottom of that, centred.
        val page = render(file)
        assertTrue(page.width > page.height)
        val bottom = page.count(0.35f, 0.85f, 0.65f, 1f, ::dark)
        assertTrue("dark pixels in the bottom band ($bottom)", bottom > 20)
        assertEquals("nothing elsewhere", 0, page.count(0f, 0f, 1f, 0.8f, ::dark))
        assertEquals("nothing in the bottom corners", 0, page.count(0f, 0.85f, 0.3f, 1f, ::dark) + page.count(0.7f, 0.85f, 1f, 1f, ::dark))
    }

    @Test
    fun numbersTopRightOnAnUprightPage() {
        val file = makePdf("Top right", text = false)
        PdfEngine.addPageNumbers(file, PageNumberStyle(NumberPosition.TopRight, NumberFormat.Plain, startAt = 7))
        assertEquals("7", text(file, 0).trim())
        val page = render(file)
        assertTrue(page.count(0.8f, 0f, 1f, 0.1f, ::dark) > 5)
        assertEquals(0, page.count(0f, 0.15f, 1f, 1f, ::dark))
    }

    @Test
    fun skipFirstStartsOnPageTwo() {
        val file = makePdf("Skip", text = false)
        PdfEngine.addPageNumbers(file, PageNumberStyle(format = NumberFormat.Page, skipFirst = true))
        assertEquals("", text(file, 0).trim())
        assertEquals("Page 1", text(file, 1).trim())
        assertEquals("Page 2", text(file, 2).trim())
        assertEquals("2 of 2", PageNumberStyle(format = NumberFormat.Of, skipFirst = true).text(2, 3))
        assertEquals(null, PageNumberStyle(skipFirst = true).text(0, 3))
    }

    // Watermark ----------------------------------------------------------------------------------

    @Test
    fun watermarkIsDiagonalTextOnTop() {
        val file = makePdf("Latin", pages = 1, photo = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        PdfEngine.addWatermark(file, WatermarkStyle())
        assertTrue(text(file, 0), text(file, 0).replace(Regex("\\s"), "").contains("CONFIDENTIAL"))
        val page = render(file)
        fun grey(p: Int) = Color.red(p) in 150..230 && Math.abs(Color.red(p) - Color.blue(p)) < 30
        // Diagonal: present near the centre, and towards the bottom-left and top-right, not the other corners.
        assertTrue("centre ${page.count(0.45f, 0.45f, 0.55f, 0.55f, ::grey)} lower ${page.count(0.37f, 0.55f, 0.45f, 0.63f, ::grey)}", page.count(0.45f, 0.45f, 0.55f, 0.55f, ::grey) > 10)
        assertTrue(page.count(0.37f, 0.55f, 0.45f, 0.63f, ::grey) > 10)
        assertEquals(0, page.count(0.37f, 0.33f, 0.43f, 0.39f, ::grey))
        assertEquals(0, page.count(0.15f, 0.15f, 0.3f, 0.3f, ::grey))
    }

    @Test
    fun watermarkArabicIsDrawn() {
        val file = makePdf("Arabic", pages = 1, text = false)
        PdfEngine.addWatermark(file, WatermarkStyle(text = "سري للغاية", diagonal = false, argb = io.pdfoundry.app.data.Ember, opacity = 0.5f))
        // Drawn by Android as an image (Helvetica can't write Arabic), shaped as Android shapes it.
        PDDocument.load(file).use { document ->
            val images = document.getPage(0).resources.xObjectNames.mapNotNull { document.getPage(0).resources.getXObject(it) as? PDImageXObject }
            assertEquals(1, images.size)
        }
        val page = render(file)
        fun tinted(p: Int) = Color.red(p) > 200 && Color.green(p) < 200 && Color.blue(p) < 190
        val centre = page.count(0.2f, 0.45f, 0.8f, 0.55f, ::tinted)
        assertTrue("the Arabic watermark is drawn across the middle ($centre)", centre > 200)
        assertEquals(0, page.count(0f, 0f, 1f, 0.3f, ::tinted))
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", done())
    }

    @Test
    fun watermarkAndNumbersKeepPassword() {
        val file = File(folder, "Protected.pdf")
        PdfEngine.protect(makePdf("Plain"), "pw", file)
        val scope = CoroutineScope(Dispatchers.Default)
        val state = ReaderState(context.applicationContext, file, scope)
        try {
            state.open()
            waitUntil { state.locked }
            state.unlock("pw") {}
            waitUntil { state.source != null && !state.busy }
            state.edit { PdfEngine.addPageNumbers(it, PageNumberStyle()) }
            state.edit { PdfEngine.addWatermark(it, WatermarkStyle(text = "DRAFT")) }
            Thread.sleep(200)
            waitUntil { !state.busy }
        } finally {
            state.close()
            Thread.sleep(300)
            scope.cancel()
        }
        assertTrue(PdfEngine.isProtected(file))
        assertEquals(UnlockResult.Full, PdfEngine.access(file, "pw"))
        val first = text(file, 0, "pw")
        assertTrue(first.contains("DRAFT"))
        assertTrue(first.contains("1"))
    }

    // Compress -----------------------------------------------------------------------------------

    /** A noisy "photo" that compresses badly losslessly. */
    private fun photo(side: Int = 1600): Bitmap {
        val random = java.util.Random(7)
        val pixels = IntArray(side * side) { i ->
            val x = i % side; val y = i / side
            Color.rgb((x * 255 / side + random.nextInt(40)).coerceAtMost(255), (y * 255 / side + random.nextInt(40)).coerceAtMost(255), random.nextInt(256))
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun compressSmallerKeepsTextSourceUnchanged() {
        val source = makePdf("Photo", photo = photo())
        val before = md5(source)
        val out = File(folder, "Photo (compressed).pdf")

        assertTrue(Compress.run(source, CompressLevel.Smaller, null, out))

        assertEquals("the source is never written", before, md5(source))
        assertTrue("smaller: ${out.length()} vs ${source.length()}", out.length() < source.length() * 0.95)
        assertTrue(text(out, 0).contains("Body text on page 1"))
        assertEquals(3, PdfEngine.pageCount(out))
        PDDocument.load(out).use { document ->
            val resources = document.getPage(0).resources
            val image = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            // Drawn 400 pt wide: 400 / 72 × 150 dpi ≈ 833 pixels.
            assertEquals(833f, image.width.toFloat(), 3f)
            assertEquals(COSName.DCT_DECODE, image.cosObject.getDictionaryObject(COSName.FILTER))
        }
        val smallest = File(folder, "Photo (smallest).pdf")
        assertTrue(Compress.run(source, CompressLevel.Smallest, null, smallest))
        assertTrue(smallest.length() < out.length())
    }

    @Test
    fun compressNoGainReturnsNil() {
        val source = makePdf("Text only")
        val out = File(folder, "Text only (compressed).pdf")
        assertFalse(Compress.run(source, CompressLevel.Smallest, null, out))
        assertFalse("nothing is kept", out.exists())
    }

    @Test
    fun compressKeepsPasswordAndTransparency() {
        val see = photo(800).copy(Bitmap.Config.ARGB_8888, true).apply { for (x in 0 until 400) for (y in 0 until 800) setPixel(x, y, Color.TRANSPARENT) }
        val plain = makePdf("Clear", photo = see)
        val source = File(folder, "Locked.pdf")
        PdfEngine.protect(plain, "pw", source)
        val out = File(folder, "Locked (compressed).pdf")

        assertTrue(Compress.run(source, CompressLevel.Smaller, "pw", out))

        assertTrue(PdfEngine.isProtected(out))
        assertEquals(UnlockResult.Full, PdfEngine.access(out, "pw"))
        PDDocument.load(out, "pw").use { document ->
            val resources = document.getPage(0).resources
            val image = resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            assertTrue("the soft mask stays", image.softMask != null)
        }
    }

    // Export as Images ---------------------------------------------------------------------------

    @Test
    fun exportNamesAndCount() {
        val source = makePdf("Report", pages = 12)
        val out = File(folder, "export")
        val files = ExportImages.run(source, (0 until 12).toList(), ImageFormat.Png, 300, out, "Report")

        assertEquals((1..12).map { "Report page %02d.png".format(it) }, files.map { it.name })
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(files.first().path, it) }
        assertEquals(2550, size.outWidth)
        assertEquals(3300, size.outHeight)
        assertEquals("image/png", size.outMimeType)

        val few = ExportImages.run(makePdf("Short"), listOf(0, 2), ImageFormat.Jpeg, 150, File(folder, "short"), "Short")
        assertEquals(listOf("Short page 1.jpg", "Short page 3.jpg"), few.map { it.name })
        val jpeg = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(few.first().path, it) }
        assertEquals(1275, jpeg.outWidth)
        assertEquals("image/jpeg", jpeg.outMimeType)
        assertEquals("Long page 007.png", ExportImages.fileName("Long", 6, 120, ImageFormat.Png))
    }

    @Test
    fun exportOfAProtectedFileUsesItsPassword() {
        val source = File(folder, "Secret.pdf")
        PdfEngine.protect(makePdf("Secret plain", pages = 2), "pw", source)
        val before = md5(source)
        val out = File(folder, "secret-export")
        val files = ExportImages.run(source, listOf(0, 1), ImageFormat.Png, 150, out, "Secret", password = "pw")
        assertEquals(2, files.size)
        assertEquals(before, md5(source))
        assertEquals("no unlocked copy is left behind", 2, out.listFiles()!!.size)
    }
}
