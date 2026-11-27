package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.Mark
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.SignatureStore
import io.pdfoundry.app.data.bitmap
import io.pdfoundry.app.data.todayText
import io.pdfoundry.app.ui.EditMode
import io.pdfoundry.app.ui.ReaderState
import io.pdfoundry.app.ui.ToolMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Fill & Sign's Initials, Date, ✓ and ✗. */
@RunWith(AndroidJUnit4::class)
class FillSignMarksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "marks-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun makePdf(name: String): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText("Name", 72f, 96f, Paint().apply { textSize = 24f })
        document.finishPage(page)
        return File(folder, "$name.pdf").also { file -> file.outputStream().use { document.writeTo(it) }; document.close() }
    }

    @Test
    fun todayTextIsMediumStyle() {
        val date = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 27) }.time
        assertEquals("Nov 27, 2026", todayText(date, Locale.US))
    }

    @Test
    fun initialsStoredApart() {
        val signatures = SignatureStore(context, "signatures", root = folder)
        val initials = SignatureStore(context, "initials", root = folder)
        initials.add(Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) })

        assertEquals(1, initials.signatures.size)
        assertEquals(0, signatures.signatures.size)
        assertEquals(1, File(folder, "initials").list()!!.size)
        // Reloaded from disk, the lists stay apart.
        assertEquals(0, SignatureStore(context, "signatures", root = folder).signatures.size)
        assertEquals(1, SignatureStore(context, "initials", root = folder).signatures.size)
    }

    @Test
    fun marksAreDarkInkOnClear() {
        for (mark in Mark.entries) {
            val image = mark.bitmap(256)
            assertEquals(0, Color.alpha(image.getPixel(2, 2)))
            val inked = (0 until 256 step 2).any { x -> (0 until 256 step 2).any { y -> image.getPixel(x, y).let { Color.alpha(it) == 255 && Color.red(it) < 40 } } }
            assertTrue("$mark has ink", inked)
        }
    }

    @Test
    fun checkStampOnRotatedPageLandsInRect() {
        val file = makePdf("Sideways")
        PdfEngine.rotate(file, listOf(0), 90)

        // Displayed 792 wide and 612 tall: a check near the top-right corner.
        PdfEngine.stamp(file, 0, Mark.Check.bitmap(), RectF(0.8f, 0.05f, 0.95f, 0.25f))

        val page = PdfSource(file).use { source -> runBlocking { source.render(0, source.sizes[0].width) }!! }
        fun dark(x: Int, y: Int) = page.getPixel(x, y).let { Color.red(it) < 90 && Color.green(it) < 90 && Color.blue(it) < 90 }
        val inside = (0.8f * 792).toInt()..(0.95f * 792).toInt()
        val insideY = (0.05f * 612).toInt()..(0.25f * 612).toInt()
        assertTrue("the check is drawn where it was placed", inside.any { x -> insideY.any { y -> dark(x, y) } })
        // And nowhere in the opposite corner.
        assertTrue((0..(0.3f * 792).toInt()).none { x -> ((0.6f * 612).toInt() until 612).any { y -> dark(x, y) } })
    }

    @Test
    fun fillSignStaysAfterPlacing() {
        val scope = CoroutineScope(Dispatchers.Default)
        val state = ReaderState(context.applicationContext, makePdf("Form"), scope)
        try {
            state.open()
            val deadline = System.currentTimeMillis() + 15_000
            while (state.source == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            state.toolMode = ToolMode.FillSign

            val items = listOf(
                EditMode.Place(Mark.Check.bitmap(), "check", width = 0.06f),
                EditMode.Place(Mark.Cross.bitmap(), "cross", width = 0.06f),
                EditMode.Place(PdfEngine.textBitmap(todayText()), "date", width = 0.3f),
                EditMode.Place(Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }, "initials", width = 0.18f)
            )
            items.forEachIndexed { index, item ->
                state.begin(item, page = 0)
                assertEquals(item, state.editMode)
                state.place(item.image, RectF(0.1f, 0.1f + index * 0.15f, 0.1f + item.width, 0.15f + index * 0.15f))
                assertEquals(ToolMode.FillSign, state.toolMode)
                assertNull(state.editMode)
            }
            Thread.sleep(100)
            while (state.busy && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(state.canUndo)
            assertEquals(4, com.tom_roush.pdfbox.pdmodel.PDDocument.load(state.file).use { it.getPage(0).annotations.count { a -> a.subtype == "Stamp" } })
        } finally {
            state.close()
            scope.cancel()
        }
    }
}
