package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.pdfoundry.app.data.Annotations
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.MarkKind
import io.pdfoundry.app.data.PagePoint
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.TextMark
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Marks are real PDF annotations with appearance streams.
 *
 * Finding (2026-11-27, API 35 emulator): PdfRenderer (RENDER_MODE_FOR_DISPLAY and FOR_PRINT) draws
 * page content only, never annotations ([pdfRendererAloneDrawsNoAnnotations]). So [PdfSource.render]
 * draws the marks on top from their appearance streams ([io.pdfoundry.app.data.AnnotationLayer]);
 * every pixel check below goes through it.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "annotation-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, text: String = "Alpha Beta Gamma"): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText(text, 72f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; color = Color.BLACK })
        document.finishPage(page)
        return File(folder, "$name.pdf").also { file -> file.outputStream().use { document.writeTo(it) }; document.close() }
    }

    /** Turned 90° with a crop box: displayed 600 wide and 400 tall. */
    private fun makeTurnedCropped(name: String): File {
        val file = makePdf(name, text = "")
        val out = File(folder, "$name-out.pdf")
        PDDocument.load(file).use { document ->
            document.getPage(0).cropBox = PDRectangle(100f, 100f, 400f, 600f)
            document.getPage(0).rotation = 90
            document.save(out)
        }
        out.renameTo(file)
        return file
    }

    private fun render(file: File): Bitmap = PdfSource(file).use { source -> runBlocking { source.render(0, source.sizes[0].width) }!! }

    private fun solid(color: Int, w: Int = 120, h: Int = 60) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** The centre of the pixels matching [match], as page fractions; null when none match. */
    private fun Bitmap.centre(match: (Int) -> Boolean): Pair<Float, Float>? {
        var sx = 0.0; var sy = 0.0; var n = 0
        for (x in 0 until width) for (y in 0 until height) if (match(getPixel(x, y))) { sx += x; sy += y; n++ }
        return if (n == 0) null else (sx / n / width).toFloat() to (sy / n / height).toFloat()
    }

    private fun isBlue(p: Int) = Color.blue(p) > 180 && Color.red(p) < 90 && Color.green(p) < 90
    private fun isRed(p: Int) = Color.red(p) > 180 && Color.green(p) < 110 && Color.blue(p) < 110
    private fun isDark(p: Int) = Color.red(p) < 90 && Color.green(p) < 90 && Color.blue(p) < 90
    private fun isTinted(p: Int) = Color.red(p) > 180 && Color.green(p) > 140 && Color.blue(p) < 170

    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>?, what: String) {
        assertNotNull("$what should be drawn", actual)
        assertEquals("$what x", expected.first, actual!!.first, 0.02f)
        assertEquals("$what y", expected.second, actual.second, 0.02f)
    }

    @Test
    fun pdfRendererAloneDrawsNoAnnotations() {
        val file = makePdf("Plain renderer", text = "")
        PdfEngine.stamp(file, 0, solid(Color.BLUE), RectF(0.4f, 0.4f, 0.6f, 0.5f))
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    for (mode in listOf(android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)) {
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        page.render(bitmap, null, null, mode)
                        assertEquals("PdfRenderer draws no annotations (mode $mode)", null, bitmap.centre(::isBlue))
                    }
                }
            }
        }
    }

    @Test
    fun readerDrawsMarkAppearances() {
        val file = makePdf("Probe", text = "")
        PdfEngine.stamp(file, 0, solid(Color.BLUE), RectF(0.4f, 0.4f, 0.6f, 0.5f))
        // Nothing was added to the page content: only an annotation.
        PDDocument.load(file).use { assertEquals(0, it.getPage(0).resources.xObjectNames.count()) }
        assertNear(0.5f to 0.45f, render(file).centre(::isBlue), "the stamp's appearance")
    }

    @Test
    fun everyMarkKindIsAnAnnotationAfterReload() {
        val file = makePdf("Kinds")
        PdfEngine.stamp(file, 0, solid(Color.BLUE), RectF(0.1f, 0.5f, 0.3f, 0.55f))
        PdfEngine.ink(file, 0, listOf(InkStroke(listOf(PagePoint(0.1f, 0.7f), PagePoint(0.5f, 0.75f)), Color.BLACK, 0.01f, false)))
        val rect = PdfEngine.words(file, 0).first { it.text == "Beta" }.rect
        PdfEngine.markText(file, 0, listOf(rect), TextMark.Highlight, Color.argb(128, 255, 204, 0))
        PdfEngine.markText(file, 0, listOf(rect), TextMark.Underline, Color.RED)
        PdfEngine.markText(file, 0, listOf(rect), TextMark.Strikethrough, Color.RED)

        PDDocument.load(file).use { document ->
            val annotations = document.getPage(0).annotations
            assertEquals(listOf("Stamp", "Ink", "Highlight", "Underline", "StrikeOut"), annotations.map { it.subtype })
            for (a in annotations) {
                assertTrue("${a.subtype} is tagged", a.cosObject.getBoolean(COSName.getPDFName("PDFoundry"), false))
                assertTrue("${a.subtype} has a PDFoundry name", a.annotationName.startsWith("pdfoundry-"))
                assertTrue("${a.subtype} is ours", Annotations.isOurs(a))
                assertTrue("${a.subtype} is printed", a.isPrinted)
                assertNotNull("${a.subtype} has an appearance", a.normalAppearanceStream)
            }
            val quads = (annotations[2].cosObject.getDictionaryObject(COSName.getPDFName("QuadPoints")) as com.tom_roush.pdfbox.cos.COSArray).toFloatArray()
            assertEquals(8, quads.size)
            // Nothing is pressed into the page itself any more.
            assertEquals(0, document.getPage(0).resources.xObjectNames.count())
        }
        val marks = PdfEngine.marks(file, 0)
        assertEquals(listOf(MarkKind.Stamp, MarkKind.Ink, MarkKind.Highlight, MarkKind.Underline, MarkKind.Strikethrough), marks.map { it.kind })
        assertEquals(listOf(true, true, false, false, false), marks.map { it.movable })
        assertEquals(0.1f, marks[0].rect.left, 0.002f)
        assertEquals(0.55f, marks[0].rect.bottom, 0.002f)
    }

    @Test
    fun marksOnRotatedCroppedPage() {
        // Stamp.
        val stamped = makeTurnedCropped("Turned stamp")
        PdfEngine.stamp(stamped, 0, solid(Color.BLUE), RectF(0.7f, 0.1f, 0.9f, 0.3f))
        assertNear(0.8f to 0.2f, render(stamped).centre(::isBlue), "the stamp")
        val rect = PdfEngine.marks(stamped, 0).single().rect
        assertEquals(0.7f, rect.left, 0.002f); assertEquals(0.1f, rect.top, 0.002f)
        assertEquals(0.9f, rect.right, 0.002f); assertEquals(0.3f, rect.bottom, 0.002f)
        // A stamp's image is upright on screen: its top half (red here) stays at the top.
        val halves = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            android.graphics.Canvas(this).drawRect(0f, 0f, 100f, 50f, Paint().apply { color = Color.RED })
        }
        val upright = makeTurnedCropped("Turned halves")
        PdfEngine.stamp(upright, 0, halves, RectF(0.2f, 0.2f, 0.4f, 0.6f))
        val page = render(upright)
        assertNear(0.3f to 0.3f, page.centre(::isRed), "the image's top half")
        assertNear(0.3f to 0.5f, page.centre(::isBlue), "the image's bottom half")

        // Drawing.
        val drawn = makeTurnedCropped("Turned ink")
        PdfEngine.ink(drawn, 0, listOf(InkStroke(listOf(PagePoint(0.2f, 0.7f), PagePoint(0.4f, 0.7f)), Color.RED, 0.01f, false)))
        assertNear(0.3f to 0.7f, render(drawn).centre(::isRed), "the drawing")

        // Highlight, underline, strikethrough on a plain rect.
        for (mark in TextMark.entries) {
            val marked = makeTurnedCropped("Turned $mark")
            val color = if (mark == TextMark.Highlight) Color.argb(255, 255, 0, 0) else Color.RED
            // Text upright on screen (a scan turned by /Rotate): its lower side is the bottom.
            PdfEngine.markText(marked, 0, listOf(RectF(0.5f, 0.6f, 0.8f, 0.7f)), mark, color, listOf(io.pdfoundry.app.data.Edge.Bottom))
            val y = when (mark) {
                TextMark.Highlight -> 0.65f
                TextMark.Underline -> 0.6f + 0.92f * 0.1f
                TextMark.Strikethrough -> 0.65f
            }
            assertNear(0.65f to y, render(marked).centre(::isRed), "the $mark")
        }
    }

    @Test
    fun marksSurviveSaveAndReloadByAnotherEditor() {
        val file = makePdf("Reload")
        PdfEngine.stamp(file, 0, solid(Color.BLUE), RectF(0.4f, 0.4f, 0.6f, 0.5f))
        // Another change rewrites the file: the mark stays an annotation in the same place.
        PdfEngine.addBlankPage(file, 0)
        assertNear(0.5f to 0.45f, render(file).centre(::isBlue), "the stamp after another save")
        assertEquals(1, PdfEngine.marks(file, 0).size)
    }

    @Test
    fun arabicTextStampStillShaped() {
        val file = makePdf("Arabic", text = "")
        val image = PdfEngine.textBitmap("مرحبا بالعالم")
        val rect = RectF(0.2f, 0.3f, 0.8f, 0.3f + 0.6f * 612f / 792f * image.height / image.width)
        PdfEngine.stamp(file, 0, image, rect)

        val page = render(file)
        val left = (rect.left * page.width).toInt(); val top = (rect.top * page.height).toInt()
        val w = (rect.width() * page.width).toInt(); val h = (rect.height() * page.height).toInt()
        val expected = Bitmap.createScaledBitmap(image, w, h, true)
        // The rendered stamp matches the Android-shaped text pixel for pixel (within anti-aliasing).
        var same = 0; var inked = 0
        for (x in 0 until w) for (y in 0 until h) {
            val want = Color.alpha(expected.getPixel(x, y)) > 128
            val got = isDark(page.getPixel(left + x, top + y))
            if (want) inked++
            if (want == got) same++
        }
        assertTrue("there is text", inked > 200)
        assertTrue("the stamp is the shaped text (${same * 100 / (w * h)}% same)", same > w * h * 0.95)
    }

    @Test
    fun highlightStillMultiplies() {
        val file = makePdf("Multiply")
        val rect = PdfEngine.words(file, 0).first { it.text == "Beta" }.rect
        val before = render(file)
        fun dark(page: Bitmap) = (0 until page.width).sumOf { x -> (0 until page.height).count { y ->
            val fx = x.toFloat() / page.width; val fy = y.toFloat() / page.height
            fx in rect.left..rect.right && fy in rect.top..rect.bottom && isDark(page.getPixel(x, y))
        } }
        val darkBefore = dark(before)

        PdfEngine.markText(file, 0, listOf(rect), TextMark.Highlight, Color.argb(128, 255, 204, 0))

        val after = render(file)
        assertTrue("the text under the highlight stays dark (${dark(after)} of $darkBefore)", dark(after) >= darkBefore * 0.9)
        val tinted = (0 until after.width).sumOf { x -> (0 until after.height).count { y ->
            val fx = x.toFloat() / after.width; val fy = y.toFloat() / after.height
            fx in rect.left..rect.right && fy in rect.top..rect.bottom && isTinted(after.getPixel(x, y))
        } }
        assertTrue("and the paper around it is tinted", tinted > 100)
    }
}
