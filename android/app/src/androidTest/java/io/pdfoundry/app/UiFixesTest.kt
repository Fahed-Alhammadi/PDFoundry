package io.pdfoundry.app

import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfItem
import io.pdfoundry.app.data.ShapeKind
import io.pdfoundry.app.ui.EditMode
import io.pdfoundry.app.ui.closeLabel
import io.pdfoundry.app.ui.menuPosition
import io.pdfoundry.app.ui.scrollTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixes found while taking the Android screenshots. */
@RunWith(AndroidJUnit4::class)
class UiFixesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "ui-fixes-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    @Test
    fun markMenuStaysOnThePage() {
        // A comment at the right edge of a 1080 × 1400 page: the 400 px menu moves left to fit.
        assertEquals(IntOffset(680, 556), menuPosition(1020f, 700f, 1070f, 750f, 400f, 120f, 1080f, 1400f, 24f))
        // Near the top, it goes below the mark.
        assertEquals(IntOffset(100, 164), menuPosition(100f, 40f, 200f, 140f, 400f, 120f, 1080f, 1400f, 24f))
        // A mark filling the page: no room above or below, so it stays inside, at the top.
        assertEquals(IntOffset(0, 0), menuPosition(-20f, 0f, 1080f, 1400f, 400f, 120f, 1080f, 1400f, 24f))
        // Never past the bottom or right edge, even when the menu is wider than the page.
        val wide = menuPosition(500f, 1300f, 600f, 1390f, 1200f, 120f, 1080f, 1400f, 24f)
        assertEquals(0, wide.x)
        assertEquals(1300 - 24 - 120, wide.y)
    }

    @Test
    fun shapesCloseRatherThanCancel() {
        assertEquals("Close Shapes", closeLabel(EditMode.Shape(ShapeKind.Rectangle, 0)))
        assertEquals("Close Redact", closeLabel(EditMode.Redact))
        // Tools whose work is not placed yet still cancel.
        assertEquals("Cancel", closeLabel(EditMode.Ink(highlighter = false)))
    }

    private fun item(name: String) = PdfItem(File(folder, "$name.pdf"), pages = 1, size = 0, modified = 0, locked = false)

    @Test
    fun libraryScrollsToTheFirstAddedDocument() {
        val list = listOf("A", "B", "C", "D", "E").map(::item)
        assertEquals(3, scrollTarget(list, listOf(File(folder, "E.pdf"), File(folder, "D.pdf"))))
        assertNull(scrollTarget(list, listOf(File(folder, "Z.pdf"))))
        assertNull(scrollTarget(list, emptyList()))
    }

    @Test
    fun fieldDialogUsesTheTooltip() {
        val file = File(folder, "Form.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val acro = PDAcroForm(document)
            document.documentCatalog.acroForm = acro
            acro.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acro.defaultAppearance = "/Helv 12 Tf 0 g"
            val named = PDTextField(acro).apply { partialName = "txt_fullname_1"; alternateFieldName = "Your full name" }
            val plain = PDTextField(acro).apply { partialName = "City" }
            listOf(named to 600f, plain to 500f).forEach { (field, y) ->
                field.widgets.first().apply { rectangle = PDRectangle(72f, y, 200f, 24f); this.page = page }
            }
            acro.fields = listOf(named, plain)
            page.annotations = listOf(named.widgets.first(), plain.widgets.first())
            document.save(file)
        }
        val fields = PdfEngine.fields(file)
        assertEquals("Your full name", fields.first { it.name == "txt_fullname_1" }.title)
        assertEquals("City", fields.first { it.name == "City" }.title)
    }
}
