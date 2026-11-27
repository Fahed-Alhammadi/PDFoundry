package io.pdfoundry.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Password-protected files: what a password opens, and that every save keeps the password. */
@RunWith(AndroidJUnit4::class)
class PasswordTest {
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
        folder = File(context.cacheDir, "password-test-${System.nanoTime()}")
        outsideFolder = File(folder, "Contracts").apply { mkdirs() }
        outside = FolderFiles(context)
        store = DocumentStore(context, outside, File(folder, "app").apply { mkdirs() })
        scope = CoroutineScope(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        states.forEach { it.close() }
        scope.cancel()
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, pages: Int = 1, into: File = folder): File {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
            page.canvas.drawText("$name page ${index + 1}", 72f, 96f, paint)
            document.finishPage(page)
        }
        return File(into, "$name.pdf").also { file ->
            file.outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    private fun makeProtected(name: String, password: String = "pw", into: File = folder): File {
        val plain = makePdf("$name-plain", into = File(folder, "plain").apply { mkdirs() })
        return File(into, "$name.pdf").also { PdfEngine.protect(plain, password, it) }
    }

    /** A file with separate owner and user passwords ([user] may be empty: restrictions only). */
    private fun makeTwoPasswords(name: String, owner: String, user: String): File {
        val plain = makePdf("$name-plain", into = File(folder, "plain").apply { mkdirs() })
        val file = File(folder, "$name.pdf")
        PDDocument.load(plain).use { document ->
            document.protect(StandardProtectionPolicy(owner, user, AccessPermission().apply { setCanModify(false) }).apply { encryptionKeyLength = 256 })
            document.save(file)
        }
        return file
    }

    private fun md5(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** How many images (stamps) the first page holds, read with [password]. */
    private fun stamps(file: File, password: String) = PDDocument.load(file, password).use { document -> document.getPage(0).annotations.count { it.subtype == "Stamp" } }

    private fun reader(file: File): ReaderState = ReaderState(context.applicationContext, file, scope, store).also {
        states += it
        it.open()
        waitUntil { it.source != null || it.locked || it.unreadable }
        idle(it)
    }

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

    private fun unlocked(file: File, password: String = "pw"): ReaderState {
        val state = reader(file)
        assertTrue("asks for the password", state.locked)
        var wrong = false
        state.unlock(password) { wrong = true }
        waitUntil { !state.locked || wrong }
        assertFalse("the password is right", wrong)
        waitUntil { state.source != null }
        idle(state)
        return state
    }

    private val signature = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun ReaderState.stamp() {
        edit { PdfEngine.stamp(it, 0, signature, RectF(0.1f, 0.1f, 0.4f, 0.2f)) }
        idle(this)
    }

    @Test
    fun accessTellsFullFromLimited() {
        val file = makeTwoPasswords("Two", owner = "o", user = "u")
        assertEquals(UnlockResult.Full, PdfEngine.access(file, "o"))
        assertEquals(UnlockResult.Limited, PdfEngine.access(file, "u"))
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, "x"))
        assertTrue(PdfEngine.isProtected(file))
        assertFalse(PdfEngine.isProtected(makePdf("Plain")))
    }

    @Test
    fun fullAccessUnlockCanEdit() {
        val state = unlocked(makeProtected("Secret"))
        assertFalse(state.readOnly)
        assertTrue(state.hasPassword)
        state.stamp()
        assertTrue(state.canUndo)
        assertEquals(1, stamps(state.file, "pw"))
    }

    @Test
    fun limitedAccessStaysReadOnly() {
        val state = unlocked(makeTwoPasswords("Two", owner = "o", user = "u"), "u")
        assertTrue(state.readOnly)
        assertFalse(state.hasPassword)
        val before = md5(state.file)
        state.edit { PdfEngine.addBlankPage(it, 0) }
        idle(state)
        assertEquals("nothing is changed", before, md5(state.file))
    }

    @Test
    fun restrictionOnlyFileStaysReadOnly() {
        val state = reader(makeTwoPasswords("Restricted", owner = "o", user = ""))
        assertFalse("opens without asking", state.locked)
        assertTrue(state.readOnly)
    }

    @Test
    fun editOfProtectedFileStaysProtected() {
        val file = makeProtected("Secret")
        val state = unlocked(file)

        state.stamp()
        assertTrue(PdfEngine.isProtected(file))
        state.undo()
        idle(state)
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(0, stamps(file, "pw"))
        state.stamp()
        state.close()
        states.remove(state)

        // Opened again from disk: still needs the same password, and holds one stamp.
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(UnlockResult.Full, PdfEngine.access(file, "pw"))
        assertEquals(1, stamps(file, "pw"))
        Thread.sleep(500)
        assertTrue(reader(file).locked)
    }

    @Test
    fun failedPushKeepsFileProtected() = runBlocking {
        val real = makeProtected("Lease", into = outsideFolder)
        val working = store.open(listOf(Uri.fromFile(real))).single()
        val state = unlocked(working)
        val realBefore = md5(real)
        val workingBefore = md5(working)

        // The file's app refuses the save.
        outside.writable = false
        state.stamp()
        assertNotNull(state.message)
        assertEquals(realBefore, md5(real))
        assertEquals(workingBefore, md5(working))

        // A save cut short halfway: the real file is finished later from the working copy, still protected.
        outside.writable = true
        outside.cutShort = true
        state.message = null
        state.stamp()
        assertNotNull(state.message)
        assertEquals(workingBefore, md5(working))
        outside.cutShort = false
        store.sync(working)
        assertEquals(workingBefore, md5(real))
        assertEquals(UnlockResult.Wrong, PdfEngine.access(real, ""))
        assertEquals(0, stamps(real, "pw"))
    }

    @Test
    fun protectWhileOpenThenEditStaysProtected() {
        val file = makePdf("Open")
        val state = reader(file)
        assertFalse(state.locked)

        state.protect("pw")
        idle(state)
        assertTrue(state.hasPassword)
        assertTrue(PdfEngine.isProtected(file))

        state.stamp()
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(1, stamps(file, "pw"))

        // Undo of the stamp keeps the password too.
        state.undo()
        idle(state)
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(0, stamps(file, "pw"))
    }

    @Test
    fun removePasswordWritesPlainFile() {
        val file = makeProtected("Secret")
        val state = unlocked(file)

        state.removePassword()
        idle(state)

        assertFalse(state.hasPassword)
        assertFalse(PdfEngine.isProtected(file))
        state.stamp()
        assertFalse(PdfEngine.isProtected(file))
        assertEquals(1, PDDocument.load(file).use { it.getPage(0).annotations.count { a -> a.subtype == "Stamp" } })
    }

    @Test
    fun revertToAProtectedOriginalKeepsThePassword() {
        val file = makeProtected("Secret")
        val original = file.readBytes()
        val state = unlocked(file)
        state.stamp()

        state.revertToOriginal()
        idle(state)
        assertTrue("byte for byte", original.contentEquals(file.readBytes()))

        state.stamp()
        assertEquals(UnlockResult.Wrong, PdfEngine.access(file, ""))
        assertEquals(1, stamps(file, "pw"))
    }

    @Test
    fun plainCopyIsDeletedOnClose() {
        val state = unlocked(makeProtected("Secret"))
        val copy = state.unlockedCopy
        assertTrue(copy.exists())
        assertTrue("in the app's private cache", copy.path.startsWith(context.cacheDir.path))
        assertFalse("no password in the name", copy.name.contains("pw"))

        state.close()
        states.remove(state)
        waitUntil { !copy.exists() }
    }

    @Test
    fun storeProtectsInPlaceAndRefusesTwice() = runBlocking {
        val real = makePdf("Lease", into = outsideFolder)
        val working = store.open(listOf(Uri.fromFile(real))).single()

        store.protect(working, "pw")

        assertEquals(UnlockResult.Wrong, PdfEngine.access(real, ""))
        assertEquals(UnlockResult.Full, PdfEngine.access(real, "pw"))
        assertTrue(store.items.single().locked)
        assertThrows(Exception::class.java) { runBlocking { store.protect(working, "other") } }
        assertEquals(UnlockResult.Full, PdfEngine.access(real, "pw"))
    }
}
