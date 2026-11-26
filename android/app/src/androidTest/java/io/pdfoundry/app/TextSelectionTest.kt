package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.Edge
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.TextMark
import io.pdfoundry.app.data.TextWord
import io.pdfoundry.app.ui.lineAreas
import io.pdfoundry.app.ui.nearestWord
import io.pdfoundry.app.ui.selectedWords
import io.pdfoundry.app.ui.wordAt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Choosing words on the page for a text mark: reading order, joining words into lines, and taps. */
@RunWith(AndroidJUnit4::class)
class TextSelectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private val letter = 612f / 792f

    private val left = listOf("Ann Bob Cat", "Dan Eve Fay", "Gus Hal Ian")
    private val right = listOf("Jo Kim Lee", "Max Ned Oz", "Pam Quin Ray")

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "selection-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** Two columns, written column by column as a word processor does: the left one, then the right one. */
    private fun twoColumns(): File {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK }
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        left.forEachIndexed { line, text -> page.canvas.drawText(text, 60f, 120f + line * 26f, paint) }
        right.forEachIndexed { line, text -> page.canvas.drawText(text, 330f, 120f + line * 26f, paint) }
        document.finishPage(page)
        return File(folder, "Columns.pdf").also { file -> file.outputStream().use { document.writeTo(it) }; document.close() }
    }

    private fun render(file: File): Bitmap = PdfSource(file).use { source -> runBlocking { source.render(0, source.sizes[0].width) }!! }

    @Test
    fun draggingDownTheLeftColumnSelectsOnlyTheLeftColumn() {
        val file = twoColumns()
        val words = PdfEngine.words(file, 0)
        val texts = words.map { it.text }

        val chosen = selectedWords(words, texts.indexOf("Ann"), texts.indexOf("Ian"))

        assertEquals(left.flatMap { it.split(" ") }, chosen.map { it.text })
        val areas = lineAreas(chosen, letter)
        assertEquals("one area per line", 3, areas.size)
        areas.forEach { assertTrue("no area reaches the right column: ${it.rect}", it.rect.right < 300f / 612) }

        // And on the page: nothing in the right column is tinted.
        PdfEngine.markText(file, 0, areas.map { it.rect }, TextMark.Highlight, Color.argb(128, 255, 204, 0), areas.map { it.lower })
        val page = render(file)
        val tintedRight = (330 until 600).sumOf { x -> (95 until 180).count { y -> page.getPixel(x, y).let { Color.blue(it) < 170 && Color.red(it) > 180 && Color.green(it) > 140 } } }
        assertEquals(0, tintedRight)
    }

    @Test
    fun wordsAcrossAGutterAreNeverJoined() {
        val a = TextWord("left", RectF(60f / 612, 100f / 792, 160f / 612, 125f / 792))
        val b = TextWord("right", RectF(330f / 612, 100f / 792, 420f / 612, 125f / 792))
        assertEquals(2, lineAreas(listOf(a, b), letter).size)

        val near = TextWord("next", RectF(166f / 612, 100f / 792, 220f / 612, 125f / 792))
        assertEquals("neighbours on a line become one area", 1, lineAreas(listOf(a, near), letter).size)
    }

    @Test
    fun aTapInTheMarginHitsNoWord() {
        val words = PdfEngine.words(twoColumns(), 0)
        val ann = words.first { it.text == "Ann" }.rect

        assertNull("far left of the line", wordAt(words, 0.02f, ann.centerY(), letter))
        assertNull("below the text", wordAt(words, ann.centerX(), 0.8f, letter))
        assertEquals(words.indexOfFirst { it.text == "Ann" }, wordAt(words, ann.centerX(), ann.centerY(), letter))
        // A drag still follows the finger to the nearest word.
        assertEquals(words.indexOfFirst { it.text == "Ann" }, nearestWord(words, 0.02f, ann.centerY(), letter))
    }

    @Test
    fun onASidewaysPageADragStaysOnItsLine() {
        // A page turned 90°: displayed 792 wide by 612 tall, lines run down the screen.
        val aspect = 792f / 612f
        val a = TextWord("A", RectF(0.50f, 0.10f, 0.53f, 0.20f), Edge.Left)
        val b = TextWord("B", RectF(0.45f, 0.30f, 0.48f, 0.40f), Edge.Left)

        // Level with B but on A's line, further along it: A is the nearest along the line.
        assertEquals(0, nearestWord(listOf(a, b), 0.515f, 0.30f, aspect))
    }
}
