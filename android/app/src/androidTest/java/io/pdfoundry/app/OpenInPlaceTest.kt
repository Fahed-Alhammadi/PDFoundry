package io.pdfoundry.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.OutsideFiles
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.Stamp
import io.pdfoundry.app.ui.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Stands in for the system file picker's links, which a test cannot obtain: plain files in a folder
 * play the files that live outside the app.
 */
private class FolderFiles(context: Context) : OutsideFiles(context) {
    val kept = mutableSetOf<Uri>()
    var handover = false
    var writable = true

    override fun keep(uri: Uri): Boolean = !handover && kept.add(uri)
    override fun release(uri: Uri) { kept.remove(uri) }
    override fun kept(): Set<Uri> = kept.toSet()
    override fun stamp(uri: Uri): Stamp? = File(uri.path!!).takeIf { it.exists() }?.let { Stamp(it.lastModified(), it.length()) }
    override fun write(source: File, uri: Uri) {
        if (!writable) throw IOException("This file couldn't be saved where it is.")
        super.write(source, uri)
    }
    override fun location(uri: Uri): String = "On this device › ${File(uri.path!!).parentFile!!.name}"
}

@RunWith(AndroidJUnit4::class)
class OpenInPlaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var outsideFolder: File
    private lateinit var root: File
    private lateinit var outside: FolderFiles
    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "in-place-test-${System.nanoTime()}")
        outsideFolder = File(folder, "Contracts").apply { mkdirs() }
        root = File(folder, "app").apply { mkdirs() }
        outside = FolderFiles(context)
        store = DocumentStore(context, outside, root)
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, pages: Int): File {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 24f }
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
            page.canvas.drawText("$name page ${index + 1}", 72f, 96f, paint)
            document.finishPage(page)
        }
        return File(outsideFolder, "$name.pdf").also { file ->
            file.outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    private fun pageCount(file: File) = PdfSource(file).use { it.pageCount }
    private fun libraryFiles() = store.directory.listFiles().orEmpty().toList()

    @Test
    fun aFileIsOpenedWhereItLivesAndNotCopied() = runBlocking {
        val lease = makePdf("Lease", 2)

        val opened = store.open(listOf(Uri.fromFile(lease)))

        assertEquals(1, opened.size)
        assertEquals(emptyList<File>(), libraryFiles())
        val item = store.items.single()
        assertEquals("Lease", item.name)
        assertEquals("On this device › Contracts", item.location)
        assertEquals(2, item.pages)
        assertTrue(item.inPlace)
    }

    @Test
    fun aChangeIsWrittenBackToTheSameFile() = runBlocking {
        val lease = makePdf("Lease", 2)
        val working = store.open(listOf(Uri.fromFile(lease))).single()

        PdfEngine.addBlankPage(working, 1)
        store.push(working)

        assertEquals(3, pageCount(lease))
        assertEquals(listOf("Lease.pdf"), outsideFolder.list()!!.toList())
    }

    @Test
    fun openingTheSameFileAgainListsItOnce() = runBlocking {
        val uri = Uri.fromFile(makePdf("Lease", 1))

        val first = store.open(listOf(uri))
        val second = store.open(listOf(uri))

        assertEquals(first, second)
        assertEquals(1, store.items.size)
    }

    @Test
    fun removingItLeavesTheRealFileAlone() = runBlocking {
        val lease = makePdf("Lease", 1)
        val working = store.open(listOf(Uri.fromFile(lease))).single()

        store.delete(store.items)

        assertTrue(lease.exists())
        assertFalse(working.exists())
        assertEquals(emptyList<Any>(), store.items)
        assertEquals(emptySet<Uri>(), outside.kept())
    }

    @Test
    fun itIsStillListedAfterARestart() = runBlocking {
        val lease = makePdf("Lease", 2)
        store.open(listOf(Uri.fromFile(lease)))

        val restarted = DocumentStore(context, outside, root)
        restarted.refresh()

        assertEquals("On this device › Contracts", restarted.items.single().location)
        // And changes still go to the same file.
        val working = restarted.items.single().file
        PdfEngine.delete(working, listOf(1))
        restarted.push(working)
        assertEquals(1, pageCount(lease))
    }

    @Test
    fun aChangeMadeByAnotherAppIsShown() = runBlocking {
        val lease = makePdf("Lease", 1)
        val working = store.open(listOf(Uri.fromFile(lease))).single()

        PdfEngine.addBlankPage(lease, 0)
        lease.setLastModified(lease.lastModified() + 5_000)

        assertTrue(store.sync(working))
        assertEquals(2, pageCount(working))
    }

    @Test
    fun aFileThatCannotBeReachedIsShownAsLastSeen() = runBlocking {
        val lease = makePdf("Lease", 1)
        val working = store.open(listOf(Uri.fromFile(lease))).single()

        lease.delete()

        assertFalse(store.sync(working))
        assertEquals(1, pageCount(working))
    }

    @Test
    fun aFileWhoseAccessWasTakenAwayLeavesTheList() = runBlocking {
        val lease = makePdf("Lease", 1)
        store.open(listOf(Uri.fromFile(lease)))

        outside.kept.clear()
        store.refresh()

        assertEquals(emptyList<Any>(), store.items)
        assertTrue(lease.exists())
    }

    @Test
    fun aOneTimeHandOverIsCopiedIntoTheLibrary() = runBlocking {
        outside.handover = true
        val attachment = makePdf("Attachment", 1)

        val opened = store.open(listOf(Uri.fromFile(attachment))).single()

        assertEquals(store.directory, opened.parentFile)
        assertNull(store.items.single().location)
    }

    @Test
    fun aFileThatIsNotAPdfIsRefused() = runBlocking {
        val notes = File(outsideFolder, "notes.pdf").apply { writeText("just text") }

        assertEquals(emptyList<File>(), store.open(listOf(Uri.fromFile(notes))))
        assertEquals(emptyList<Any>(), store.items)
        assertEquals(emptySet<Uri>(), outside.kept())
    }

    @Test
    fun aFileOpenedWhileTheListIsRefreshingIsNotLost() = runBlocking(Dispatchers.Default) {
        val first = Uri.fromFile(makePdf("First", 1))
        store.open(listOf(first))
        repeat(20) { round ->
            val uri = Uri.fromFile(makePdf("Round $round", 1))
            val refreshing = async { store.refresh() }
            val opening = async { store.open(listOf(uri)) }
            refreshing.await()
            opening.await()
        }
        store.refresh()

        assertEquals(21, store.items.size)
        assertEquals(21, DocumentStore(context, outside, root).apply { refresh() }.items.size)
    }

    @Test
    fun aFailedSaveReportsIt() {
        runBlocking {
            val lease = makePdf("Lease", 1)
            val working = store.open(listOf(Uri.fromFile(lease))).single()
            outside.writable = false

            PdfEngine.addBlankPage(working, 0)
            assertThrows(IOException::class.java) { runBlocking { store.push(working) } }
            assertEquals(1, pageCount(lease))
        }
    }

    @Test
    fun saveACopyPutsAnIndependentCopyInTheLibrary() = runBlocking {
        val lease = makePdf("Lease", 2)
        val working = store.open(listOf(Uri.fromFile(lease))).single()

        val copy = store.saveCopy(working)

        assertEquals(File(store.directory, "Lease.pdf"), copy)
        assertEquals(2, store.items.size)
        PdfEngine.delete(copy, listOf(0))
        store.push(copy)
        assertEquals(2, pageCount(lease))
    }

    // Revert to Original ---------------------------------------------------------------------

    /** Waits until the reader has finished what it was asked to do. */
    private fun ReaderState.settled(after: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while ((version <= after || busy) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("the reader did not finish", version > after && !busy)
    }

    @Test
    fun revertPutsTheFileBackExactlyAndUndoBringsTheChangesBack() {
        val file = makePdf("Report", 3)
        val before = file.readBytes()
        val scope = CoroutineScope(Dispatchers.Default)
        val state = ReaderState(context.applicationContext, file, scope)
        try {
            state.open()
            state.settled(0)
            assertFalse(state.canRevert)

            var version = state.version
            state.edit { PdfEngine.delete(it, listOf(2)) }
            state.settled(version)
            version = state.version
            state.edit { PdfEngine.rotate(it, listOf(0), 90) }
            state.settled(version)
            assertEquals(2, pageCount(file))
            assertTrue(state.canRevert)

            version = state.version
            state.revertToOriginal()
            state.settled(version)
            assertArrayEquals(before, file.readBytes())
            assertFalse(state.canRevert)

            version = state.version
            state.undo()
            state.settled(version)
            assertEquals(2, pageCount(file))
            assertTrue(state.canRevert)
        } finally {
            state.close()
            scope.cancel()
        }
    }
}
