package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.UnlockResult
import io.pdfoundry.app.ui.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Fixes from the independent review of the Android branch. */
@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var outsideFolder: File
    private lateinit var outside: FolderFiles
    private lateinit var store: DocumentStore
    private lateinit var scope: CoroutineScope
    private val states = mutableListOf<ReaderState>()

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "review-test-${System.nanoTime()}")
        outsideFolder = File(folder, "Contracts").apply { mkdirs() }
        outside = FolderFiles(context)
        store = DocumentStore(context, outside, File(folder, "app").apply { mkdirs() })
        scope = CoroutineScope(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        states.forEach { it.close() }
        Thread.sleep(300)
        scope.cancel()
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, into: File = folder): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
        page.canvas.drawText("$name page 1", 72f, 96f, Paint().apply { textSize = 24f })
        document.finishPage(page)
        return File(into, "$name.pdf").also { file -> file.outputStream().use { document.writeTo(it) }; document.close() }
    }

    private fun makeProtected(name: String, password: String = "pw", into: File = folder): File {
        val plain = makePdf("$name-plain", File(folder, "plain").apply { mkdirs() })
        return File(into, "$name.pdf").also { PdfEngine.protect(plain, password, it) }
    }

    private fun makeTwoPasswords(name: String, owner: String, user: String): File {
        val plain = makePdf("$name-plain", File(folder, "plain").apply { mkdirs() })
        val file = File(folder, "$name.pdf")
        PDDocument.load(plain).use { document ->
            document.protect(StandardProtectionPolicy(owner, user, AccessPermission().apply { setCanModify(false) }).apply { encryptionKeyLength = 256 })
            document.save(file)
        }
        return file
    }

    private fun md5(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun stamps(file: File, password: String) = PDDocument.load(file, password).use { it.getPage(0).resources.xObjectNames.count() }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", done())
    }

    private fun idle(state: ReaderState) {
        Thread.sleep(100)
        waitUntil { !state.busy }
        Thread.sleep(100)
        waitUntil { !state.busy }
    }

    private fun reader(file: File, password: String? = null): ReaderState = ReaderState(context.applicationContext, file, scope, store).also { state ->
        states += state
        state.open()
        waitUntil { state.source != null || state.locked || state.unreadable }
        idle(state)
        if (password != null) {
            var wrong = false
            state.unlock(password) { wrong = true }
            waitUntil { !state.locked || wrong }
            assertFalse(wrong)
            waitUntil { state.source != null }
            idle(state)
        }
    }

    private val signature = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    private fun ReaderState.stamp() = edit { PdfEngine.stamp(it, 0, signature, RectF(0.1f, 0.1f, 0.4f, 0.2f)) }

    // 1. Protect never works on a copy.

    @Test
    fun protectRefusesAFileThatCanOnlyBeCopied() = runBlocking {
        val real = makePdf("Handed", outsideFolder)
        val before = md5(real)
        outside.handover = true

        val error = assertThrows(Exception::class.java) { runBlocking { store.openForChange(Uri.fromFile(real)) } }

        assertEquals("PDFoundry can't change this file where it is.", error.message)
        assertTrue("nothing was copied into the library", store.directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.items.isEmpty())
        assertEquals(before, md5(real))
    }

    @Test
    fun protectOpensAKeepableFileInPlace() = runBlocking {
        val real = makePdf("Kept", outsideFolder)
        val working = store.openForChange(Uri.fromFile(real))
        store.protect(working, "pw")
        assertEquals(UnlockResult.Wrong, PdfEngine.access(real, ""))
        assertTrue(store.directory.listFiles().orEmpty().isEmpty())
    }

    // 2. Plain undo copies of a protected document don't outlive a crash.

    @Test
    fun leftoverUndoAndUnlockedCopiesAreCleared() {
        val undo = File(context.cacheDir, "undo").apply { mkdirs() }
        val unlocked = DocumentStore.unlockedDirectory(context).apply { mkdirs() }
        val staleUndo = File(undo, "stale-${System.nanoTime()}").apply { mkdirs(); File(this, "1.pdf").writeText("plain"); setLastModified(1_000) }
        val staleCopy = File(unlocked, "stale-${System.nanoTime()}.pdf").apply { writeText("plain"); setLastModified(1_000) }
        val freshUndo = File(undo, "fresh-${System.nanoTime()}").apply { mkdirs() }
        val freshCopy = File(unlocked, "fresh-${System.nanoTime()}.pdf").apply { writeText("open now") }

        store.clearLeftovers()

        assertFalse(staleUndo.exists())
        assertFalse(staleCopy.exists())
        assertTrue("a document open now keeps its undo copies", freshUndo.exists())
        assertTrue(freshCopy.exists())
        freshUndo.deleteRecursively()
        freshCopy.delete()
    }

    // 3. Owner password with a different user password.

    @Test
    fun ownerPasswordOpeningWarnsThatSavingSetsIt() {
        val two = makeTwoPasswords("Two", owner = "o", user = "u")
        assertFalse(PdfEngine.opensWith(two, "o"))
        assertTrue(PdfEngine.opensWith(two, "u"))
        assertTrue(PdfEngine.opensWith(makeProtected("Same"), "pw"))

        val state = reader(two, "o")
        assertFalse(state.readOnly)
        assertEquals("Saving will set this document's password to the one you entered.", state.message)

        val same = reader(makeProtected("Other"), "pw")
        assertNull(same.message)
    }

    // 4 and 9. Checkboxes and radios with /Opt; choices with export values and labels; drawn appearances.

    private fun optForm(destination: File) {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
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
                        if (mark) { content.addRect(3f, 3f, widget.rectangle.width - 6f, widget.rectangle.height - 6f); content.fill() }
                    }
                }
                widget.appearance = PDAppearanceDictionary().apply {
                    cosObject.setItem(COSName.N, COSDictionary().apply { setItem(COSName.getPDFName(on), stream(true)); setItem(COSName.Off, stream(false)) })
                }
                widget.setAppearanceState("Off")
            }
            val agree = PDCheckBox(form).apply { partialName = "agree"; exportValues = listOf("Agreed") }
            states(place(agree, PDRectangle(150f, 700f, 18f, 18f)), "0")
            val size = PDRadioButton(form).apply { partialName = "size"; exportValues = listOf("Small", "Large") }
            val small = PDAnnotationWidget()
            val large = PDAnnotationWidget()
            size.widgets = listOf(small, large)
            states(place(size, PDRectangle(150f, 650f, 18f, 18f), small), "0")
            states(place(size, PDRectangle(190f, 650f, 18f, 18f), large), "1")
            val pick = PDComboBox(form).apply { partialName = "pick"; setOptions(listOf("s", "m"), listOf("Small", "Medium")) }
            place(pick, PDRectangle(150f, 600f, 120f, 22f))
            val answer = PDComboBox(form).apply { partialName = "answer"; setOptions(listOf("نعم", "لا")) }
            place(answer, PDRectangle(150f, 550f, 120f, 22f))
            val note = PDTextField(form).apply { partialName = "note"; isMultiline = true }
            place(note, PDRectangle(150f, 450f, 200f, 60f))
            val turned = PDTextField(form).apply { partialName = "turned" }
            place(turned, PDRectangle(400f, 400f, 22f, 150f)).appearanceCharacteristics = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply { rotation = 90 }
            form.fields.addAll(listOf(agree, size, pick, answer, note, turned))
            document.save(destination)
        }
    }

    private fun appearanceImage(file: File, name: String): Pair<PDAppearanceStream, PDImageXObject?> = PDDocument.load(file).use { document ->
        val widget = document.documentCatalog.acroForm.getField(name).widgets.first()
        val stream = widget.appearance.normalAppearance.appearanceStream
        val image = stream.resources.xObjectNames.firstNotNullOfOrNull { stream.resources.getXObject(it) as? PDImageXObject }
        // Read what is needed while the document is open.
        image?.let { it.width; it.height }
        stream to image
    }

    @Test
    fun checkboxAndRadioWithOptUseExportValues() {
        val file = File(folder, "Opt.pdf").also { optForm(it) }
        val fields = PdfEngine.fields(file)
        assertEquals("Agreed", fields.first { it.name == "agree" }.onValue)
        assertEquals(listOf("Small", "Large"), fields.filter { it.name == "size" }.map { it.onValue })

        PdfEngine.setField(file, "agree", "Agreed")
        PdfEngine.setField(file, "size", "Large", widgetIndex = 1)

        val after = PdfEngine.fields(file)
        assertTrue(after.first { it.name == "agree" }.checked)
        assertTrue(after.first { it.name == "size" && it.widget == 1 }.checked)
        assertFalse(after.first { it.name == "size" && it.widget == 0 }.checked)
        PDDocument.load(file).use { document ->
            val form = document.documentCatalog.acroForm
            assertEquals("Agreed", form.getField("agree").valueAsString)
            assertEquals("Large", form.getField("size").valueAsString)
        }

        PdfEngine.setField(file, "agree", "Off")
        assertFalse(PdfEngine.fields(file).first { it.name == "agree" }.checked)
    }

    @Test
    fun choiceSavesTheExportValueAndShowsTheLabel() {
        val file = File(folder, "Opt.pdf").also { optForm(it) }
        val pick = PdfEngine.fields(file).first { it.name == "pick" }
        assertEquals(listOf("s", "m"), pick.options)
        assertEquals(listOf("Small", "Medium"), pick.labels)

        PdfEngine.setField(file, "pick", "m")

        PDDocument.load(file).use { assertEquals("m", it.documentCatalog.acroForm.getField("pick").cosObject.getString(COSName.V)) }
        assertEquals("m", PdfEngine.fields(file).first { it.name == "pick" }.value)
        assertTrue("the label is drawn as the field's appearance", appearanceImage(file, "pick").second != null)
        assertThrows(Exception::class.java) { PdfEngine.setField(file, "pick", "x") }
    }

    @Test
    fun nonLatinChoiceOptionIsDrawn() {
        val file = File(folder, "Opt.pdf").also { optForm(it) }
        PdfEngine.setField(file, "answer", "نعم")
        assertEquals("نعم", PdfEngine.fields(file).first { it.name == "answer" }.value)
        assertTrue(appearanceImage(file, "answer").second != null)
    }

    @Test
    fun multilineValueIsDrawnOnSeveralLines() {
        val file = File(folder, "Opt.pdf").also { optForm(it) }
        PdfEngine.setField(file, "note", "مرحبا\nبالعالم")
        val image = appearanceImage(file, "note").second!!
        val oneLine = PdfEngine.textBitmap("مرحبا")
        assertTrue("two lines tall (${image.height} vs ${oneLine.height})", image.height > oneLine.height * 3 / 2)
        assertEquals("مرحبا\nبالعالم", PdfEngine.fields(file).first { it.name == "note" }.value)
    }

    @Test
    fun turnedWidgetGetsATurnedAppearance() {
        val file = File(folder, "Opt.pdf").also { optForm(it) }
        PdfEngine.setField(file, "turned", "مرحبا")
        val (stream, image) = appearanceImage(file, "turned")
        assertTrue(image != null)
        // Drawn upright in a 150 x 22 box, turned a quarter onto the 22 x 150 widget.
        assertEquals(150f, stream.bBox.width, 0.01f)
        assertEquals(22f, stream.bBox.height, 0.01f)
        assertEquals(0f, stream.matrix.scaleX, 0.001f)
        assertEquals(1f, kotlin.math.abs(stream.matrix.shearY), 0.001f)
    }

    // 5. Revert keeps a password set since the document was opened.

    @Test
    fun revertAfterProtectKeepsThePassword() {
        val file = makePdf("Plain")
        val state = reader(file)
        state.protect("pw")
        idle(state)
        state.stamp()
        idle(state)

        state.revertToOriginal()
        idle(state)

        assertTrue(state.hasPassword)
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(0, stamps(file, "pw"))
        state.stamp()
        idle(state)
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(1, stamps(file, "pw"))
    }

    // 6. Tools don't copy pages out of a file whose restrictions they would drop.

    @Test
    fun restrictedFileCantBeSplitOrExtracted() = runBlocking {
        val restricted = makeTwoPasswords("Restricted", owner = "o", user = "")
        assertThrows(Exception::class.java) { runBlocking { store.split(restricted, listOf(listOf(0)), password = null) } }
        assertThrows(Exception::class.java) { runBlocking { store.extractPages(restricted, listOf(0)) } }
        assertThrows(Exception::class.java) { runBlocking { store.extract(restricted, listOf(0)) } }
        assertTrue("nothing was written", store.directory.listFiles().orEmpty().isEmpty())
        // With the owner password, it can: the output keeps it.
        val part = store.split(restricted, listOf(listOf(0)), password = "o").single()
        assertEquals(UnlockResult.Wrong, PdfEngine.access(part, ""))
    }

    // 7. Closing while a change is still saving keeps that change.

    @Test
    fun closingWhileSavingKeepsTheLastChange() {
        val file = makeProtected("Secret")
        val state = reader(file, "pw")
        val copy = state.unlockedCopy

        state.edit { Thread.sleep(600); PdfEngine.stamp(it, 0, signature, RectF(0.1f, 0.1f, 0.4f, 0.2f)) }
        state.stamp()
        state.close()
        states.remove(state)

        waitUntil { !copy.exists() && !state.busy }
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals("both changes were saved before closing", 2, stamps(file, "pw"))
    }

    // 8. Protect never pushes a stale working copy.

    @Test
    fun protectRefusesWhenTheRealFileCantBeReached() = runBlocking {
        val real = makePdf("Gone", outsideFolder)
        val working = store.open(listOf(Uri.fromFile(real))).single()
        real.delete()

        val error = assertThrows(Exception::class.java) { runBlocking { store.protect(working, "pw") } }

        assertTrue(error.message!!.contains("can't be reached"))
        assertFalse(PdfEngine.isProtected(working))
        assertFalse(real.exists())
    }

    // Review Focus 1: a field change on a protected document keeps the password.

    @Test
    fun fieldChangeOnAProtectedFormStaysProtected() {
        val plain = File(folder, "Form-plain.pdf").also { FormFixture.write(it) }
        val file = File(folder, "Form.pdf").also { PdfEngine.protect(plain, "pw", it) }
        val state = reader(file, "pw")

        state.setField(state.fields.first { it.name == "name" }, "Jane")
        idle(state)
        state.setField(state.fields.first { it.name == "agree" }, "Yes")
        idle(state)

        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        PDDocument.load(file, "pw").use { document ->
            assertEquals("Jane", document.documentCatalog.acroForm.getField("name").valueAsString)
            assertEquals(7, document.documentCatalog.acroForm.fields.size)
        }
        state.undo()
        idle(state)
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertTrue(state.fields.first { it.name == "name" }.value == "Jane")
        assertFalse(state.fields.first { it.name == "agree" }.checked)
    }
}
