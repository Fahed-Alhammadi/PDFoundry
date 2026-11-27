package io.pdfoundry.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.pdfoundry.app.data.RangeResult
import io.pdfoundry.app.ui.PasswordWarning
import io.pdfoundry.app.ui.PdfTool
import io.pdfoundry.app.ui.SplitMode
import io.pdfoundry.app.ui.ToolSection
import io.pdfoundry.app.ui.canProtect
import io.pdfoundry.app.ui.splitParts
import io.pdfoundry.app.ui.splitSummary
import io.pdfoundry.app.ui.toolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Tools tab's sections, and the Split and Protect sheets' rules. */
@RunWith(AndroidJUnit4::class)
class ToolsCatalogTest {
    @Test
    fun catalogShowsSplitAndProtect() {
        assertEquals(
            listOf(
                ToolSection.Create to listOf(PdfTool.Scan, PdfTool.Photos),
                ToolSection.Combine to listOf(PdfTool.Merge, PdfTool.Extract, PdfTool.Split),
                ToolSection.Protect to listOf(PdfTool.Protect)
            ),
            toolCatalog(scanner = true)
        )
        // Without Google's scanner, Scan is left out; Enhance has no tool yet and stays hidden.
        assertEquals(listOf(PdfTool.Photos), toolCatalog(scanner = false).first().second)
        assertFalse(toolCatalog(scanner = true).any { it.first == ToolSection.Enhance })
        assertEquals("Make several PDFs from one.", PdfTool.Split.detail)
        assertEquals("Lock a PDF with a password.", PdfTool.Protect.detail)
    }

    @Test
    fun splitScreenRejectsBadRanges() {
        for (bad in listOf("", "0", "5-3", "1-11", "a")) {
            val result = splitParts(SplitMode.Custom, "2", bad, 10)
            assertTrue("“$bad” can't be split", result is RangeResult.Bad)
        }
        assertEquals("Page 11 doesn't exist. This PDF has 10 pages.", splitSummary(splitParts(SplitMode.Custom, "2", "1-11", 10)))
        assertEquals("Makes 3 files.", splitSummary(splitParts(SplitMode.Custom, "2", "1-3, 4-6, 9", 10)))
        assertEquals("Makes 3 files.", splitSummary(splitParts(SplitMode.EveryN, "2", "", 5)))
        assertEquals("Makes 5 files.", splitSummary(splitParts(SplitMode.EveryPage, "2", "", 5)))
        assertTrue(splitParts(SplitMode.EveryN, "0", "", 5) is RangeResult.Bad)
        assertTrue(splitParts(SplitMode.EveryN, "", "", 5) is RangeResult.Bad)
    }

    @Test
    fun protectNeedsTheSamePasswordTwice() {
        assertFalse(canProtect("", ""))
        assertFalse(canProtect("secret", ""))
        assertFalse(canProtect("secret", "Secret"))
        assertTrue(canProtect("secret", "secret"))
        assertEquals("If you forget the password, the file can't be opened, and it can't be recovered.", PasswordWarning)
    }
}
