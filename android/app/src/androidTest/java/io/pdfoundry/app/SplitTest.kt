package io.pdfoundry.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.PageRanges
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.PdfSource
import io.pdfoundry.app.data.RangeResult
import io.pdfoundry.app.data.UnlockResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Split PDF: page ranges, and parts written as new documents without touching the source. */
@RunWith(AndroidJUnit4::class)
class SplitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var outsideFolder: File
    private lateinit var outside: FolderFiles
    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        PdfEngine.init(context)
        folder = File(context.cacheDir, "split-test-${System.nanoTime()}")
        outsideFolder = File(folder, "Contracts").apply { mkdirs() }
        outside = FolderFiles(context)
        store = DocumentStore(context, outside, File(folder, "app").apply { mkdirs() })
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

    private fun md5(file: File) = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // Ranges: the vectors shared by all three apps.

    @Test
    fun customRangesAreRead() {
        assertEquals(RangeResult.Ok(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(8))), PageRanges.parse("1-3, 4-6, 9", 10))
        assertEquals(RangeResult.Ok(listOf(listOf(1))), PageRanges.parse("2", 2))
    }

    @Test
    fun badRangesSayWhy() {
        assertEquals(RangeResult.Bad("Enter page ranges, such as 1-3, 4-6."), PageRanges.parse("", 10))
        assertEquals(RangeResult.Bad("Enter page ranges, such as 1-3, 4-6."), PageRanges.parse("   ", 10))
        assertEquals(RangeResult.Bad("Page 0 doesn't exist. This PDF has 10 pages."), PageRanges.parse("0", 10))
        assertEquals(RangeResult.Bad("Write ranges from low to high, such as 3-5."), PageRanges.parse("5-3", 10))
        assertEquals(RangeResult.Bad("Page 11 doesn't exist. This PDF has 10 pages."), PageRanges.parse("1-11", 10))
        assertEquals(RangeResult.Bad("Use numbers, dashes and commas, such as 1-3, 4-6."), PageRanges.parse("a", 10))
    }

    @Test
    fun everyNMakesConsecutiveParts() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PageRanges.every(2, 5))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), PageRanges.every(1, 3))
    }

    // Splitting

    @Test
    fun splitLeavesSourceUnchanged() = runBlocking {
        val lease = makePdf("Lease", 4)
        val working = store.open(listOf(Uri.fromFile(lease))).single()
        val realBefore = md5(lease)
        val workingBefore = md5(working)

        val parts = store.split(working, listOf(listOf(0, 1), listOf(2, 3)), password = null)

        assertEquals(realBefore, md5(lease))
        assertEquals(workingBefore, md5(working))
        assertEquals(listOf("Lease.pdf"), outsideFolder.list()!!.toList())
        assertEquals(listOf("Lease (part 1)", "Lease (part 2)"), parts.map { it.nameWithoutExtension })
        assertEquals(listOf(2, 2), parts.map { file -> PdfSource(file).use { it.pageCount } })
        // The parts are in the library; the source is listed once, as before (opened in place).
        assertEquals(setOf("Lease (part 1)", "Lease (part 2)"), store.directory.list()!!.map { it.removeSuffix(".pdf") }.toSet())
        assertEquals(3, store.items.size)
    }

    @Test
    fun splitAvoidsNamesInUse() = runBlocking {
        val report = makePdf("Report", 2)
        store.split(report, listOf(listOf(0), listOf(1)), password = null)
        val again = store.split(report, listOf(listOf(0)), password = null)
        assertEquals("Report (part 1) 2", again.single().nameWithoutExtension)
    }

    @Test
    fun splitOfProtectedFileKeepsPassword() = runBlocking {
        val plain = makePdf("Secret", 3)
        val locked = File(folder, "Secret.pdf")
        PdfEngine.protect(plain, "pw", locked)

        val parts = store.split(locked, listOf(listOf(0), listOf(1, 2)), password = "pw")

        assertEquals(2, parts.size)
        for (part in parts) {
            assertTrue("needs the password", PdfEngine.isProtected(part))
            assertEquals(UnlockResult.Wrong, PdfEngine.access(part, ""))
            assertEquals(UnlockResult.Full, PdfEngine.access(part, "pw"))
        }
        assertEquals(listOf(1, 2), parts.map { PdfEngine.pageCount(it, "pw") })
    }
}
