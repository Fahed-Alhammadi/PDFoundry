package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.pdfoundry.app.data.Edge
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.data.TextWord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Text geometry from the page and the marks drawn on it (highlight, underline, strikethrough). */
@RunWith(AndroidJUnit4::class)
class TextMarkupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    private val yellow = Color.argb(128, 255, 204, 0)
    private val red = Color.rgb(255, 59, 48)

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "markup-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** A letter-size page with one line of large text at (x, baseline) in points from the top-left. */
    private fun makePdf(name: String, text: String = "Alpha Beta Gamma", x: Float = 72f, baseline: Float = 160f): File {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; color = Color.BLACK }
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText(text, x, baseline, paint)
        document.finishPage(page)
        return File(folder, "$name.pdf").also { file ->
            file.outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    /** The page rendered at one pixel per point, as displayed (rotation and crop applied). */
    private fun render(file: File): Bitmap = PdfSource(file).use { source ->
        runBlocking { source.render(0, source.sizes[0].width) }!!
    }

    private fun Bitmap.pixels(rect: RectF): List<Int> {
        val left = (rect.left * width).toInt().coerceIn(0, width - 1)
        val right = (rect.right * width).toInt().coerceIn(left + 1, width)
        val top = (rect.top * height).toInt().coerceIn(0, height - 1)
        val bottom = (rect.bottom * height).toInt().coerceIn(top + 1, height)
        return (left until right).flatMap { x -> (top until bottom).map { y -> getPixel(x, y) } }
    }

    private fun isDark(pixel: Int) = Color.red(pixel) < 90 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
    /** Yellow on white after a multiply: red and green stay high, blue drops. */
    private fun isTinted(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) > 140 && Color.blue(pixel) < 170

    private fun word(words: List<TextWord>, text: String) = words.first { it.text == text }

    /** The word's rect covers its ink, and a highlight over it tints that place and nowhere else. */
    private fun assertHighlightLandsOn(file: File, target: String) {
        val words = PdfEngine.words(file, 0)
        val rect = word(words, target).rect
        val before = render(file)
        assertTrue("the word's rect should cover the word's ink", before.pixels(rect).count(::isDark) > 30)
        assertEquals("nothing is tinted before the highlight", 0, before.pixels(rect).count(::isTinted))

        PdfEngine.markText(file, 0, listOf(rect), TextMark.Highlight, yellow)

        val after = render(file)
        val area = after.pixels(rect)
        assertTrue("the highlight should tint the word (${area.count(::isTinted)} of ${area.size})", area.count(::isTinted) > area.size / 3)
        // The rest of the page is untouched: nothing tinted anywhere outside the rect (with a small margin).
        val tintedOutside = (0 until after.width step 3).sumOf { x ->
            (0 until after.height step 3).count { y ->
                val fx = x.toFloat() / after.width
                val fy = y.toFloat() / after.height
                val near = fx in rect.left - 0.01f..rect.right + 0.01f && fy in rect.top - 0.01f..rect.bottom + 0.01f
                !near && isTinted(after.getPixel(x, y))
            }
        }
        assertEquals("nothing outside the word should be tinted", 0, tintedOutside)
    }

    @Test
    fun wordsAreFoundOnAPlainPage() {
        val file = makePdf("Plain")

        val words = PdfEngine.words(file, 0)

        assertEquals(listOf("Alpha", "Beta", "Gamma"), words.map { it.text })
        val alpha = word(words, "Alpha").rect
        val beta = word(words, "Beta").rect
        // Reading order and position: Alpha starts near x = 72 pt, Beta sits to its right on the same line.
        assertEquals(72f / 612, alpha.left, 0.01f)
        assertTrue(alpha.right <= beta.left + 0.005f)
        assertEquals(alpha.centerY(), beta.centerY(), 0.01f)
        // The line's baseline (160 pt from the top) lies inside the rect, near its bottom.
        assertTrue(160f / 792 in alpha.top..alpha.bottom)
    }

    @Test
    fun wordsLandOnTheTextOnAPlainPage() = assertHighlightLandsOn(makePdf("Plain highlight"), "Beta")

    @Test
    fun wordsLandOnTheTextOnARotatedPage() {
        val file = makePdf("Sideways")
        PdfEngine.rotate(file, listOf(0), 90)

        val rect = word(PdfEngine.words(file, 0), "Beta").rect
        // Displayed sideways, the line runs from top to bottom: the rect is taller than it is wide.
        assertTrue("a word on a page turned 90° is upright in the display: $rect", rect.height() * 792 > rect.width() * 612)

        assertHighlightLandsOn(file, "Beta")
    }

    @Test
    fun wordsLandOnTheTextOnAPageTurnedUpsideDown() {
        val file = makePdf("Upside down")
        PdfEngine.rotate(file, listOf(0), 180)
        assertHighlightLandsOn(file, "Gamma")
    }

    @Test
    fun wordsLandOnTheTextOnAPageTurnedLeft() {
        val file = makePdf("Turned left")
        PdfEngine.rotate(file, listOf(0), 270)
        assertHighlightLandsOn(file, "Alpha")
    }

    @Test
    fun wordsLandOnTheTextOnACroppedPage() {
        val file = makePdf("Cropped", x = 160f, baseline = 300f)
        PDDocument.load(file).use { document ->
            // Keep x 100…500 and y 100…700 (PDF space, from the bottom-left).
            document.getPage(0).cropBox = PDRectangle(100f, 100f, 400f, 600f)
            document.save(File(folder, "cropped-out.pdf"))
        }
        File(folder, "cropped-out.pdf").renameTo(file)

        val rect = word(PdfEngine.words(file, 0), "Alpha").rect
        // 160 pt from the page's left edge is 60 pt into the 400 pt crop box.
        assertEquals(60f / 400, rect.left, 0.015f)

        assertHighlightLandsOn(file, "Alpha")
    }

    @Test
    fun aScannedPageHasNoWords() {
        val scan = Bitmap.createBitmap(850, 1100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            android.graphics.Canvas(this).drawText("Printed on paper", 100f, 200f, Paint().apply { textSize = 48f })
        }
        val file = File(folder, "Scan.pdf")
        PdfEngine.imagesToPdf(listOf(scan), file)

        assertEquals(emptyList<TextWord>(), PdfEngine.words(file, 0))
    }

    @Test
    fun underlineAndStrikethroughDrawLines() {
        val rect = RectF(0.2f, 0.5f, 0.6f, 0.56f)
        val heightPx = 0.06f * 792

        val underlined = makePdf("Underline", text = "")
        PdfEngine.markText(underlined, 0, listOf(rect), TextMark.Underline, red)
        render(underlined).let { page ->
            val underY = (0.5f * 792 + 0.92f * heightPx).toInt()
            val middleY = (0.5f * 792 + 0.5f * heightPx).toInt()
            val x = (0.4f * 612).toInt()
            assertTrue("an underline is drawn near the bottom of the rect", Color.red(page.getPixel(x, underY)) > 200 && Color.green(page.getPixel(x, underY)) < 120)
            assertEquals(Color.WHITE, page.getPixel(x, middleY))
            assertEquals("the line stays inside the rect sideways", Color.WHITE, page.getPixel((0.65f * 612).toInt(), underY))
        }

        val struck = makePdf("Strikethrough", text = "")
        PdfEngine.markText(struck, 0, listOf(rect), TextMark.Strikethrough, red)
        render(struck).let { page ->
            val middleY = (0.5f * 792 + 0.5f * heightPx).toInt()
            val underY = (0.5f * 792 + 0.92f * heightPx).toInt()
            val x = (0.4f * 612).toInt()
            assertTrue("a strikethrough is drawn through the middle", Color.red(page.getPixel(x, middleY)) > 200 && Color.green(page.getPixel(x, middleY)) < 120)
            assertEquals(Color.WHITE, page.getPixel(x, underY))
        }
    }

    /** On a turned page the text runs down or up the screen: the line must follow it, on the text's lower side. */
    private fun assertLineFollowsTheText(rotation: Int, mark: TextMark, along: Float) {
        val file = makePdf("Turned $rotation $mark")
        PdfEngine.rotate(file, listOf(0), rotation)
        val rect = word(PdfEngine.words(file, 0), "Beta").rect
        PdfEngine.markText(file, 0, listOf(rect), mark, red)

        val page = render(file)
        fun isRed(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) < 120 && Color.blue(pixel) < 120
        // Unrotated, the line sits 92% down the rect (50% for strikethrough). Map that across the turn.
        val (x, y) = when (rotation) {
            90 -> (rect.right - along * rect.width()) to rect.centerY()
            180 -> rect.centerX() to (rect.bottom - along * rect.height())
            270 -> (rect.left + along * rect.width()) to rect.centerY()
            else -> rect.centerX() to (rect.top + along * rect.height())
        }
        // Look a few pixels around the expected spot: a line is about 7% of the text height thick.
        val hit = (-2..2).any { dx -> (-2..2).any { dy -> isRed(page.getPixel((x * page.width).toInt() + dx, (y * page.height).toInt() + dy)) } }
        assertTrue("the $mark line should run along the text on a page turned $rotation degrees (looked at $x, $y in $rect)", hit)
    }

    @Test
    fun underlineFollowsTheTextOnTurnedPages() {
        listOf(90, 180, 270).forEach { assertLineFollowsTheText(it, TextMark.Underline, 0.92f) }
    }

    @Test
    fun strikethroughFollowsTheTextOnATurnedPage() = assertLineFollowsTheText(90, TextMark.Strikethrough, 0.5f)

    // Text drawn rotated in PDF space ----------------------------------------------------------

    /** One line of Helvetica drawn with a text matrix turned [angle] degrees counter-clockwise, on a page with /Rotate [pageRotation]. */
    private fun makeTurnedTextPdf(name: String, pageRotation: Int, angle: Double, x: Float, y: Float, text: String = "Rotated Header"): File {
        val file = File(folder, "$name.pdf")
        PDDocument().use { document ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle.LETTER)
            page.rotation = pageRotation
            document.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 30f)
                stream.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(angle), x, y))
                stream.showText(text)
                stream.endText()
            }
            document.save(file)
        }
        return file
    }

    private fun isRed(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) < 120 && Color.blue(pixel) < 120

    private fun Bitmap.redNear(x: Float, y: Float) = (-2..2).any { dx -> (-2..2).any { dy -> isRed(getPixel((x * width).toInt() + dx, (y * height).toInt() + dy)) } }

    @Test
    fun aRotatedHeaderOnAnUprightPageIsFoundWhereItIs() {
        // Reads bottom to top, like a rotated table header.
        val file = makeTurnedTextPdf("Header", pageRotation = 0, angle = 90.0, x = 300f, y = 200f)

        val word = word(PdfEngine.words(file, 0), "Header")
        assertTrue("the word runs up the page: ${word.rect}", word.rect.height() * 792 > word.rect.width() * 612)
        assertEquals("the text's lower side faces right", Edge.Right, word.lower)
        assertHighlightLandsOn(file, "Header")
    }

    @Test
    fun anUnderlineUnderARotatedHeaderIsOnItsLowerSide() {
        val file = makeTurnedTextPdf("Header underline", pageRotation = 0, angle = 90.0, x = 300f, y = 200f)
        val word = word(PdfEngine.words(file, 0), "Header")

        PdfEngine.markText(file, 0, listOf(word.rect), TextMark.Underline, red, listOf(word.lower))

        val page = render(file)
        val rect = word.rect
        assertTrue("the line runs along the text, on the right", page.redNear(rect.left + 0.92f * rect.width(), rect.centerY()))
        assertTrue("nothing on the other side", !page.redNear(rect.left + 0.08f * rect.width(), rect.centerY()))
    }

    @Test
    fun aScanTurnedByThePageButUprightOnScreenIsMarkedUpright() {
        // OCR'd scans often have /Rotate 90 and content turned the other way, so the text reads normally.
        val file = makeTurnedTextPdf("Upright scan", pageRotation = 90, angle = 90.0, x = 300f, y = 200f, text = "Alpha Beta Gamma")

        val word = word(PdfEngine.words(file, 0), "Beta")
        assertEquals(Edge.Bottom, word.lower)
        // Displayed 792 wide and 612 tall; an upright word is wider than tall.
        assertTrue("the word is upright on screen: ${word.rect}", word.rect.width() * 792 > word.rect.height() * 612)
        assertHighlightLandsOn(file, "Beta")

        val underlined = makeTurnedTextPdf("Upright scan underline", pageRotation = 90, angle = 90.0, x = 300f, y = 200f, text = "Alpha Beta Gamma")
        val again = word(PdfEngine.words(underlined, 0), "Beta")
        PdfEngine.markText(underlined, 0, listOf(again.rect), TextMark.Underline, red, listOf(again.lower))
        val page = render(underlined)
        assertTrue("the underline is under the word", page.redNear(again.rect.centerX(), again.rect.top + 0.92f * again.rect.height()))
    }

    @Test
    fun severalRectsAreMarkedInOneChange() {
        val file = makePdf("Several")
        val words = PdfEngine.words(file, 0)

        PdfEngine.markText(file, 0, words.map { it.rect }, TextMark.Highlight, yellow)

        val page = render(file)
        words.forEach { assertTrue("${it.text} should be highlighted", page.pixels(it.rect).count(::isTinted) > 0) }
    }
}
