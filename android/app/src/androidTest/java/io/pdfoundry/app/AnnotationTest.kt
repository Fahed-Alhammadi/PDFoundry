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
import kotlinx.coroutines.cancel
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

    // Select, move, resize, delete ---------------------------------------------------------------

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", done())
    }

    private fun idle(state: io.pdfoundry.app.ui.ReaderState) {
        Thread.sleep(100)
        waitUntil { !state.busy }
        Thread.sleep(100)
        waitUntil { !state.busy }
    }

    /** The mark's appearance, decoded: its form stream and every image it draws. */
    private fun appearanceBytes(file: File, id: String): List<ByteArray> = PDDocument.load(file).use { document ->
        val a = document.getPage(0).annotations.first { it.annotationName == id }
        val form = a.normalAppearanceStream
        val images = form.resources.xObjectNames.map { (form.resources.getXObject(it) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject).cosObject.createInputStream().readBytes() }
        listOf(form.cosObject.createInputStream().readBytes()) + images
    }

    @Test
    fun moveResizeDeleteUndoRestoresExactly() {
        val file = makePdf("Moving")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val state = io.pdfoundry.app.ui.ReaderState(context.applicationContext, file, scope)
        try {
            state.open()
            waitUntil { state.source != null }
            idle(state)
            state.begin(io.pdfoundry.app.ui.EditMode.Place(solid(Color.BLUE), "signature"), 0)
            state.place(solid(Color.BLUE), RectF(0.2f, 0.6f, 0.5f, 0.7f))
            idle(state)
            val original = state.marks[0]!!.single()
            val originalLook = appearanceBytes(file, original.id)

            assertTrue(state.select(0, original.rect.centerX(), original.rect.centerY()))
            state.moveSelected(RectF(0.4f, 0.2f, 0.7f, 0.3f))
            idle(state)
            assertEquals(0.4f, state.marks[0]!!.single().rect.left, 0.002f)
            assertNear(0.55f to 0.25f, render(file).centre(::isBlue), "the moved stamp")
            state.moveSelected(RectF(0.4f, 0.2f, 0.9f, 0.4f))
            idle(state)
            assertNear(0.65f to 0.3f, render(file).centre(::isBlue), "the resized stamp")
            // Moving never redraws the image: the appearance is the same bytes.
            assertTrue(appearanceBytes(file, original.id).zip(originalLook).all { (a, b) -> a.contentEquals(b) })
            state.deleteSelected()
            idle(state)
            assertEquals(null, state.marks[0])
            assertEquals(null, state.selected)

            repeat(3) { state.undo(); idle(state) }

            val back = state.marks[0]!!.single()
            assertEquals(original.id, back.id)
            assertEquals(original.rect.left, back.rect.left, 0.0001f)
            assertEquals(original.rect.top, back.rect.top, 0.0001f)
            assertEquals(original.rect.right, back.rect.right, 0.0001f)
            assertEquals(original.rect.bottom, back.rect.bottom, 0.0001f)
            val look = appearanceBytes(file, original.id)
            assertEquals(originalLook.size, look.size)
            assertTrue("the appearance is restored byte for byte", look.zip(originalLook).all { (a, b) -> a.contentEquals(b) })
            assertNear(0.35f to 0.65f, render(file).centre(::isBlue), "the stamp back in place")
        } finally {
            state.close()
            Thread.sleep(300)
            scope.cancel()
        }
    }

    @Test
    fun inkMovesWithItsPaths() {
        val file = makePdf("Ink move", text = "")
        PdfEngine.ink(file, 0, listOf(InkStroke(listOf(PagePoint(0.1f, 0.1f), PagePoint(0.3f, 0.1f)), Color.RED, 0.01f, false)))
        val mark = PdfEngine.marks(file, 0).single()
        val dx = 0.5f
        PdfEngine.moveMark(file, 0, mark.id, RectF(mark.rect.left + dx, mark.rect.top + 0.5f, mark.rect.right + dx, mark.rect.bottom + 0.5f))
        assertNear(0.7f to 0.6f, render(file).centre(::isRed), "the moved drawing")
        PDDocument.load(file).use { document ->
            val ink = (document.getPage(0).annotations.single().cosObject.getDictionaryObject(COSName.getPDFName("InkList")) as com.tom_roush.pdfbox.cos.COSArray)
            val first = (ink.getObject(0) as com.tom_roush.pdfbox.cos.COSArray).toFloatArray()
            // The path moved with it (other viewers redraw ink from it): 0.6 × 612 and (1 − 0.6) × 792.
            assertEquals(0.6f * 612, first[0], 1f)
            assertEquals(0.4f * 792, first[1], 1f)
        }
    }

    @Test
    fun textMarkupDeletableNotMovable() {
        val file = makePdf("Markup delete")
        val rect = PdfEngine.words(file, 0).first { it.text == "Beta" }.rect
        PdfEngine.markText(file, 0, listOf(rect), TextMark.Highlight, Color.argb(128, 255, 204, 0))
        val mark = PdfEngine.marks(file, 0).single()
        assertEquals(false, mark.movable)
        assertEquals(mark, PdfEngine.markAt(file, 0, rect.centerX(), rect.centerY()))

        org.junit.Assert.assertThrows(io.pdfoundry.app.data.PdfException::class.java) {
            PdfEngine.moveMark(file, 0, mark.id, RectF(0.1f, 0.1f, 0.3f, 0.2f))
        }
        PdfEngine.deleteMark(file, 0, mark.id)
        assertEquals(emptyList<Any>(), PdfEngine.marks(file, 0))
    }

    @Test
    fun oldUntaggedAnnotationDeletable() {
        val file = makePdf("Old stamp", text = "")
        // A stamp made the way another app (or an older build) makes it: no tag, no /NM.
        PDDocument.load(file).use { document ->
            val page = document.getPage(0)
            val stamp = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp()
            stamp.rectangle = PDRectangle(100f, 100f, 100f, 50f)
            val appearance = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(document).apply {
                bBox = PDRectangle(0f, 0f, 100f, 50f)
                resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            }
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, appearance).use { it.setNonStrokingColor(0f, 0f, 1f); it.addRect(0f, 0f, 100f, 50f); it.fill() }
            stamp.appearance = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
            page.annotations = listOf(stamp)
            document.save(File(folder, "old-out.pdf"))
        }
        File(folder, "old-out.pdf").renameTo(file)

        val mark = PdfEngine.marks(file, 0).single()
        assertTrue("an old mark is known by its kind and place: ${mark.id}", mark.id.startsWith("#Stamp@"))
        assertEquals(MarkKind.Stamp, mark.kind)
        assertEquals("not ours: it can't be moved", false, mark.movable)
        assertNotNull("the reader shows it", render(file).centre(::isBlue))
        PdfEngine.deleteMark(file, 0, mark.id)
        assertEquals(0, PDDocument.load(file).use { it.getPage(0).annotations.size })
    }

    // Shapes and comments ------------------------------------------------------------------------

    @Test
    fun shapesAreRealAnnotations() {
        val file = makePdf("Shapes", text = "")
        val ember = Color.rgb(226, 81, 27)
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Rectangle, PagePoint(0.1f, 0.1f), PagePoint(0.4f, 0.3f), Color.RED)
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Ellipse, PagePoint(0.5f, 0.1f), PagePoint(0.9f, 0.3f), ember)
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Line, PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f), Color.RED)
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Arrow, PagePoint(0.1f, 0.7f), PagePoint(0.9f, 0.8f), Color.RED)
        // A tap is not a shape.
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Rectangle, PagePoint(0.5f, 0.5f), PagePoint(0.5f, 0.5f), Color.RED)

        PDDocument.load(file).use { document ->
            val annotations = document.getPage(0).annotations
            assertEquals(listOf("Square", "Circle", "Line", "Line"), annotations.map { it.subtype })
            for (a in annotations) {
                assertTrue(Annotations.isOurs(a))
                assertNotNull(a.normalAppearanceStream)
                val border = a.cosObject.getDictionaryObject(COSName.BS) as com.tom_roush.pdfbox.cos.COSDictionary
                assertEquals(2f, border.getFloat(COSName.W), 0.001f)
                assertEquals(null, a.cosObject.getDictionaryObject(COSName.IC))
            }
            val ellipseColor = annotations[1].color.components
            assertEquals(226 / 255f, ellipseColor[0], 0.01f); assertEquals(81 / 255f, ellipseColor[1], 0.01f)
            fun ends(i: Int) = (annotations[i].cosObject.getDictionaryObject(COSName.LE) as com.tom_roush.pdfbox.cos.COSArray).map { (it as COSName).name }
            assertEquals(listOf("None", "None"), ends(2))
            assertEquals(listOf("None", "OpenArrow"), ends(3))
            val line = (annotations[2].cosObject.getDictionaryObject(COSName.L) as com.tom_roush.pdfbox.cos.COSArray).toFloatArray()
            assertEquals(0.1f * 612, line[0], 0.5f); assertEquals(0.5f * 792, line[1], 0.5f)
            assertEquals(0.9f * 612, line[2], 0.5f)
        }
        assertEquals(listOf(MarkKind.Rectangle, MarkKind.Ellipse, MarkKind.Line, MarkKind.Arrow), PdfEngine.marks(file, 0).map { it.kind })
        assertTrue(PdfEngine.marks(file, 0).all { it.movable })
        // Drawn by the reader: the rectangle's outline, not its middle.
        val page = render(file)
        assertTrue("the rectangle's edge is drawn", isRed(page.getPixel((0.25f * page.width).toInt(), (0.1f * page.height).toInt() + 0)) ||
            isRed(page.getPixel((0.25f * page.width).toInt(), (0.1f * page.height).toInt() + 1)))
        assertEquals("no fill", Color.WHITE, page.getPixel((0.25f * page.width).toInt(), (0.2f * page.height).toInt()))
        assertNear(0.5f to 0.5f, run {
            // Only the line's band of the page.
            val band = Bitmap.createBitmap(page, 0, (0.45f * page.height).toInt(), page.width, (0.1f * page.height).toInt())
            band.centre(::isRed)?.let { it.first to 0.45f + it.second * 0.1f }
        }, "the line")
    }

    @Test
    fun shapesOnRotatedCroppedPage() {
        val file = makeTurnedCropped("Turned shapes")
        PdfEngine.shape(file, 0, io.pdfoundry.app.data.ShapeKind.Ellipse, PagePoint(0.6f, 0.2f), PagePoint(0.9f, 0.5f), Color.RED)
        assertNear(0.75f to 0.35f, render(file).centre(::isRed), "the ellipse")
        val rect = PdfEngine.marks(file, 0).single().rect
        assertEquals(0.6f, rect.left, 0.002f); assertEquals(0.5f, rect.bottom, 0.002f)
    }

    @Test
    fun commentRoundTripIncludingArabic() {
        val file = makePdf("Comments", text = "")
        val arabic = "مرحبا بالعالم"
        val id = PdfEngine.comment(file, 0, PagePoint(0.5f, 0.5f), arabic)
        assertNotNull(id)

        PDDocument.load(file).use { document ->
            val note = document.getPage(0).annotations.single()
            assertEquals("Text", note.subtype)
            assertEquals(arabic, note.contents)
            assertEquals("Comment", note.cosObject.getNameAsString(COSName.NAME))
            assertNotNull(note.normalAppearanceStream)
            assertTrue(Annotations.isOurs(note))
        }
        assertEquals(arabic, PdfEngine.commentText(file, 0, id!!))
        val mark = PdfEngine.marks(file, 0).single()
        assertEquals(MarkKind.Comment, mark.kind)
        assertEquals(0.5f, mark.rect.centerX(), 0.01f)
        assertNotNull("the note icon is drawn", render(file).centre { Color.red(it) > 200 && Color.green(it) in 150..230 && Color.blue(it) < 120 })

        PdfEngine.updateComment(file, 0, id, "Second thoughts")
        assertEquals("Second thoughts", PdfEngine.commentText(file, 0, id))
        // Emptying a comment deletes it.
        PdfEngine.updateComment(file, 0, id, "  ")
        assertEquals(emptyList<Any>(), PdfEngine.marks(file, 0))
    }

    @Test
    fun emptyCommentIsNotCreated() {
        val file = makePdf("No comment", text = "")
        val before = file.readBytes()
        assertEquals(null, PdfEngine.comment(file, 0, PagePoint(0.5f, 0.5f), "   "))
        assertTrue("the file is not touched", before.contentEquals(file.readBytes()))
    }
}
