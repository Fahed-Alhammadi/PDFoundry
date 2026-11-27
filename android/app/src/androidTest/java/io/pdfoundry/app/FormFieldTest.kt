package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.FieldKind
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.ui.ReaderState
import io.pdfoundry.app.ui.fitted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A one-page form like a paper one: name (text), address (multi-line), agree (checkbox), size (radio:
 * S or L), color (choice), id (read-only text, "A-1") and sig (signature). Built with PdfBox the way
 * form tools build them, with Helvetica as the fields' font.
 */
object FormFixture {
    fun write(destination: File) {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"

            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                content.newLineAtOffset(72f, 720f)
                content.showText("Application form")
                content.setFont(PDType1Font.HELVETICA, 11f)
                listOf(670f to "Name", 600f to "Address", 540f to "I agree", 500f to "Size: S / L", 460f to "Colour", 420f to "Reference", 360f to "Signature").fold(720f) { previous, (y, label) ->
                    content.newLineAtOffset(0f, y - previous)
                    content.showText(label)
                    y
                }
                content.endText()
            }

            fun place(field: PDTerminalField, rect: PDRectangle, widget: PDAnnotationWidget = field.widgets.first()): PDAnnotationWidget {
                widget.rectangle = rect
                widget.page = page
                page.annotations.add(widget)
                return widget
            }
            fun states(widget: PDAnnotationWidget, on: String) {
                fun stream(mark: Boolean) = PDAppearanceStream(document).apply {
                    bBox = PDRectangle(widget.rectangle.width, widget.rectangle.height)
                    resources = PDResources()
                    PDPageContentStream(document, this).use { content ->
                        if (mark) {
                            content.setNonStrokingColor(0f, 0f, 0f)
                            content.addRect(3f, 3f, widget.rectangle.width - 6f, widget.rectangle.height - 6f)
                            content.fill()
                        }
                    }
                }
                val normal = COSDictionary().apply {
                    setItem(COSName.getPDFName(on), stream(true))
                    setItem(COSName.Off, stream(false))
                }
                widget.appearance = PDAppearanceDictionary().apply { cosObject.setItem(COSName.N, normal) }
                widget.setAppearanceState("Off")
            }

            val name = PDTextField(form).apply { partialName = "name" }
            place(name, PDRectangle(150f, 662f, 260f, 22f))
            val address = PDTextField(form).apply { partialName = "address"; isMultiline = true }
            place(address, PDRectangle(150f, 570f, 260f, 50f))
            val agree = PDCheckBox(form).apply { partialName = "agree" }
            states(place(agree, PDRectangle(150f, 534f, 18f, 18f)), "Yes")
            val size = PDRadioButton(form).apply { partialName = "size" }
            val small = PDAnnotationWidget()
            val large = PDAnnotationWidget()
            size.widgets = listOf(small, large)
            states(place(size, PDRectangle(150f, 494f, 18f, 18f), small), "S")
            states(place(size, PDRectangle(190f, 494f, 18f, 18f), large), "L")
            val color = PDComboBox(form).apply { partialName = "color"; setOptions(listOf("Red", "Green", "Blue")) }
            place(color, PDRectangle(150f, 452f, 120f, 22f))
            val id = PDTextField(form).apply { partialName = "id" }
            place(id, PDRectangle(150f, 412f, 120f, 22f))
            val sig = PDSignatureField(form).apply { partialName = "sig" }
            place(sig, PDRectangle(150f, 330f, 220f, 50f))

            form.fields.addAll(listOf(name, address, agree, size, color, id, sig))
            id.setValue("A-1")
            id.isReadOnly = true
            document.save(destination)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class FormFieldTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var form: File

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "form-test-${System.nanoTime()}").apply { mkdirs() }
        form = File(folder, "Form.pdf").also { FormFixture.write(it) }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun field(name: String, widget: Int = 0) = PdfEngine.fields(form).first { it.name == name && it.widget == widget }

    @Test
    fun fieldsAreListedWithRects() {
        val fields = PdfEngine.fields(form)
        assertEquals(
            listOf("name" to FieldKind.Text, "address" to FieldKind.MultilineText, "agree" to FieldKind.Checkbox, "size" to FieldKind.Radio, "size" to FieldKind.Radio, "color" to FieldKind.Choice, "id" to FieldKind.Text, "sig" to FieldKind.Signature),
            fields.map { it.name to it.kind }
        )
        val name = fields.first()
        assertEquals(150f / 612, name.rect.left, 0.001f)
        assertEquals((792f - 684f) / 792, name.rect.top, 0.001f)
        assertEquals(410f / 612, name.rect.right, 0.001f)
        assertEquals((792f - 662f) / 792, name.rect.bottom, 0.001f)
        assertEquals(listOf("S", "L"), fields.filter { it.name == "size" }.map { it.onValue })
        assertEquals(listOf("Red", "Green", "Blue"), field("color").options)
        assertTrue(field("id").readOnly)
        assertEquals("A-1", field("id").value)
    }

    @Test
    fun fieldsOnARotatedPageFollowTheRotation() {
        PdfEngine.rotate(form, listOf(0), 90)
        val name = field("name")
        // Turned a quarter clockwise, the page is shown 792 wide: the field's bottom edge (y = 662) becomes its left.
        assertEquals((792f - 684f) / 792, 1f - name.rect.right, 0.001f)
        assertEquals(150f / 612, name.rect.top, 0.001f)
    }

    @Test
    fun fieldValuesSurviveAsFields() {
        PdfEngine.setField(form, "name", "Jane Doe")
        PdfEngine.setField(form, "address", "1 Main Street\nSpringfield")
        PdfEngine.setField(form, "agree", "Yes")
        PdfEngine.setField(form, "size", "L", widgetIndex = 1)
        PdfEngine.setField(form, "color", "Green")

        assertEquals("Jane Doe", field("name").value)
        assertEquals("1 Main Street\nSpringfield", field("address").value)
        assertTrue(field("agree").isOn)
        assertTrue(field("size", widget = 1).isOn)
        assertTrue(!field("size", widget = 0).isOn)
        assertEquals("Green", field("color").value)

        // Still fields, not flattened into the page.
        PDDocument.load(form).use { document ->
            val acroForm = document.documentCatalog.acroForm
            assertEquals(7, acroForm.fields.size)
            assertEquals(8, document.getPage(0).annotations.count { it is PDAnnotationWidget })
            assertEquals("Jane Doe", acroForm.getField("name").valueAsString)
        }

        // Clearing a checkbox.
        PdfEngine.setField(form, "agree", "Off")
        assertTrue(!field("agree").isOn)
    }

    @Test
    fun arabicFieldValueRoundTrips() {
        val arabic = "مرحبا بالعالم"
        PdfEngine.setField(form, "name", arabic)

        val name = field("name")
        assertEquals(arabic, name.value)
        PDDocument.load(form).use { assertEquals(arabic, it.documentCatalog.acroForm.getField("name").valueAsString) }

        // The page shows it: dark pixels inside the field's box.
        val page = PdfSource(form).use { source -> runBlocking { source.render(0, 612) }!! }
        val xs = (name.rect.left * page.width).toInt() until (name.rect.right * page.width).toInt()
        val ys = (name.rect.top * page.height).toInt() until (name.rect.bottom * page.height).toInt()
        val dark = xs.sumOf { x -> ys.count { y -> page.getPixel(x, y).let { Color.red(it) < 100 && Color.green(it) < 100 && Color.blue(it) < 100 } } }
        assertTrue("the Arabic text is drawn ($dark dark pixels)", dark > 20)
    }

    @Test
    fun latinValueIsDrawnByTheFieldsFont() {
        PdfEngine.setField(form, "name", "Jane")
        val name = field("name")
        val page = PdfSource(form).use { source -> runBlocking { source.render(0, 612) }!! }
        val xs = (name.rect.left * page.width).toInt() until (name.rect.right * page.width).toInt()
        val ys = (name.rect.top * page.height).toInt() until (name.rect.bottom * page.height).toInt()
        assertTrue(xs.any { x -> ys.any { y -> Color.red(page.getPixel(x, y)) < 100 } })
    }

    @Test
    fun readOnlyFieldUnchanged() {
        assertThrows(Exception::class.java) { PdfEngine.setField(form, "id", "B-2") }
        assertEquals("A-1", field("id").value)
    }

    @Test
    fun fieldChangeIsUndoable() {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = DocumentStore(context, FolderFiles(context), File(folder, "app").apply { mkdirs() })
        val state = ReaderState(context.applicationContext, form, scope, store)
        fun waitIdle() {
            val deadline = System.currentTimeMillis() + 15_000
            Thread.sleep(100)
            while ((state.busy || state.source == null) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
        try {
            state.open()
            waitIdle()
            val name = state.fields.first { it.name == "name" }
            state.setField(name, "Jane")
            waitIdle()
            assertEquals("Jane", state.fields.first { it.name == "name" }.value)
            val agree = state.fields.first { it.name == "agree" }
            state.setField(agree, "Yes")
            waitIdle()
            assertTrue(state.fields.first { it.name == "agree" }.isOn)

            // One Undo step per field change.
            state.undo()
            waitIdle()
            assertTrue(!state.fields.first { it.name == "agree" }.isOn)
            assertEquals("Jane", state.fields.first { it.name == "name" }.value)
            state.undo()
            waitIdle()
            assertEquals("", state.fields.first { it.name == "name" }.value)

            // A read-only field is left alone.
            state.setField(state.fields.first { it.name == "id" }, "B-2")
            waitIdle()
            assertEquals("A-1", state.fields.first { it.name == "id" }.value)
        } finally {
            state.close()
            scope.cancel()
        }
    }

    @Test
    fun signatureIsFittedIntoTheField() {
        // A 2:1 image in a field 220 x 50 points on a 612 x 792 page: as tall as the field, centred.
        val box = RectF(150f / 612, (792f - 380f) / 792, 370f / 612, (792f - 330f) / 792)
        val rect = fitted(box, 2f, 612f / 792f)
        assertEquals(box.height(), rect.height(), 0.0001f)
        assertEquals(100f / 612, rect.width(), 0.0001f)
        assertEquals(box.centerX(), rect.centerX(), 0.0001f)

        val image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        PdfEngine.stamp(form, 0, image, rect)
        assertEquals(7, PDDocument.load(form).use { it.documentCatalog.acroForm.fields.size })
    }
}
