package io.pdfoundry.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Extract Pages from the Tools tab: a new document with the chosen pages; the source is never changed. */
@RunWith(AndroidJUnit4::class)
class ExtractPagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var outsideFolder: File
    private lateinit var root: File
    private lateinit var outside: FolderFiles
    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "extract-test-${System.nanoTime()}")
        outsideFolder = File(folder, "Contracts").apply { mkdirs() }
        root = File(folder, "app").apply { mkdirs() }
        outside = FolderFiles(context)
        store = DocumentStore(context, outside, root)
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun makePdf(name: String, pages: Int, into: File = outsideFolder): File {
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

    private fun md5(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun pageCount(file: File) = PdfSource(file).use { it.pageCount }

    @Test
    fun extractingFromAFileOpenedInPlaceLeavesItUnchanged() = runBlocking {
        val lease = makePdf("Lease", 3)
        val working = store.open(listOf(Uri.fromFile(lease))).single()
        val realBefore = md5(lease)
        val workingBefore = working.readBytes()
        val stampBefore = lease.lastModified()

        val extracted = store.extractPages(working, listOf(0, 2))

        assertEquals("the real file is untouched", realBefore, md5(lease))
        assertEquals(stampBefore, lease.lastModified())
        assertArrayEquals("the working copy is untouched", workingBefore, working.readBytes())
        assertEquals(listOf("Lease.pdf"), outsideFolder.list()!!.toList())

        // The new document is in the library with the chosen pages, in order.
        assertEquals(store.directory, extracted.parentFile)
        assertEquals(2, pageCount(extracted))
        assertEquals(listOf(0), PdfEngine.search(extracted, "Lease page 1").map { it.page })
        assertEquals(listOf(1), PdfEngine.search(extracted, "Lease page 3").map { it.page })
        // The source is not added to the library a second time: only the extract is new there.
        assertEquals(listOf(extracted), store.directory.listFiles()!!.toList())
        assertEquals(2, store.items.size)
        assertTrue(store.items.any { it.file == extracted && !it.inPlace })
    }

    @Test
    fun aFileChangedByAnotherAppIsExtractedAsItIsNow() = runBlocking {
        val lease = makePdf("Lease", 2)
        val working = store.open(listOf(Uri.fromFile(lease))).single()
        // Another app adds a page; the working copy has not caught up yet.
        PdfEngine.addBlankPage(lease, 1)
        lease.setLastModified(lease.lastModified() + 5_000)
        val realBefore = md5(lease)

        val extracted = store.extractPages(working, listOf(0, 1, 2))

        assertEquals(3, pageCount(extracted))
        assertEquals(realBefore, md5(lease))
    }

    @Test
    fun aFilePickedFromFilesForExtractingIsNotListed() = runBlocking {
        val lease = makePdf("Lease", 3)
        val before = md5(lease)

        val picked = store.temporaryCopy(Uri.fromFile(lease))!!
        val extracted = store.extractPages(picked, listOf(1))
        store.discardTemporary(picked)

        assertEquals(before, md5(lease))
        assertEquals("Lease extract", extracted.nameWithoutExtension)
        assertEquals(1, pageCount(extracted))
        // Only the new document is listed: the picked file was neither opened in place nor imported.
        assertEquals(listOf(extracted), store.items.map { it.file })
        assertEquals(emptySet<Uri>(), outside.kept())
        assertTrue(!picked.exists())
    }

    @Test
    fun aPickedFileThatIsNotAPdfIsRefused() = runBlocking {
        val notes = File(outsideFolder, "Notes.pdf").apply { writeText("plain text") }
        assertEquals(null, store.temporaryCopy(Uri.fromFile(notes)))
    }

    @Test
    fun extractingFromALibraryDocumentLeavesItUnchanged() = runBlocking {
        val report = makePdf("Report", 4, into = store.directory)
        store.refresh()
        val before = md5(report)

        val extracted = store.extractPages(report, listOf(3, 1))

        assertEquals(before, md5(report))
        assertEquals(2, pageCount(extracted))
        assertEquals("Report extract", extracted.nameWithoutExtension)
        assertEquals(listOf(0), PdfEngine.search(extracted, "Report page 2").map { it.page })
    }
}
