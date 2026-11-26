package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.InkStroke
import io.pdfoundry.app.data.PagePoint
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.ui.EditMode
import io.pdfoundry.app.ui.ReaderState
import io.pdfoundry.app.ui.ToolMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The reader's bar: which mode it is in before and after a tool is used. */
@RunWith(AndroidJUnit4::class)
class ReaderModeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var scope: CoroutineScope
    private lateinit var state: ReaderState

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "mode-test-${System.nanoTime()}").apply { mkdirs() }
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText("Sign here", 72f, 96f, Paint().apply { textSize = 24f })
        document.finishPage(page)
        val file = File(folder, "Form.pdf").also { file -> file.outputStream().use { document.writeTo(it) }; document.close() }
        scope = CoroutineScope(Dispatchers.Default)
        state = ReaderState(context.applicationContext, file, scope)
        state.open()
        settled(0)
    }

    @After
    fun tearDown() {
        state.close()
        scope.cancel()
        folder.deleteRecursively()
    }

    /** Waits until the reader has finished what it was asked to do. */
    private fun settled(after: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while ((state.version <= after || state.busy) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("the reader did not finish", state.version > after && !state.busy)
    }

    private val signature = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    @Test
    fun toolModeStartsAtNone() {
        assertEquals(ToolMode.None, state.toolMode)
        assertNull(state.editMode)
    }

    @Test
    fun placingASignatureKeepsFillSign() {
        state.toolMode = ToolMode.FillSign

        // Sign twice in a row: after each one the bar is still in Fill & Sign.
        repeat(2) { round ->
            state.begin(EditMode.Place(signature, "signature"), page = 0)
            assertEquals(EditMode.Place(signature, "signature"), state.editMode)
            val version = state.version
            state.place(signature, RectF(0.1f, 0.1f + round * 0.3f, 0.4f, 0.2f + round * 0.3f))
            settled(version)

            assertEquals(ToolMode.FillSign, state.toolMode)
            assertNull("back to the bar, not still placing", state.editMode)
        }
        assertTrue(state.canUndo)
    }

    @Test
    fun finishingADrawingKeepsMarkup() {
        state.toolMode = ToolMode.Markup
        state.begin(EditMode.Ink(highlighter = false), page = 0)
        val version = state.version

        state.finishInk(listOf(InkStroke(listOf(PagePoint(0.1f, 0.5f), PagePoint(0.9f, 0.5f)), Color.BLACK, 0.01f, highlighter = false)))
        settled(version)

        assertEquals(ToolMode.Markup, state.toolMode)
        assertNull(state.editMode)
    }

    @Test
    fun cancellingAToolKeepsTheMode() {
        state.toolMode = ToolMode.Markup
        state.begin(EditMode.TextMark(TextMark.Highlight, Color.YELLOW), page = 0)

        state.cancelEdit()

        assertEquals(ToolMode.Markup, state.toolMode)
        assertNull(state.editMode)
    }

    @Test
    fun markingTextKeepsMarkup() {
        state.toolMode = ToolMode.Markup
        state.begin(EditMode.TextMark(TextMark.Underline, Color.RED), page = 0)
        val words = PdfEngine.words(state.file, 0)
        val version = state.version

        state.markText(words.map { it.rect }, TextMark.Underline, Color.RED)
        settled(version)

        assertEquals(ToolMode.Markup, state.toolMode)
        assertNull(state.editMode)
        assertTrue(state.canUndo)
    }
}
