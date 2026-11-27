package io.pdfoundry.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    Png("PNG", "png", "image/png"),
    Jpeg("JPEG", "jpg", "image/jpeg")
}

/** Export as Images: Screen is 150 dpi, Print 300 dpi. */
enum class ExportQuality(val label: String, val dpi: Int) { Screen("Screen", 150), Print("Print", 300) }

/**
 * Saves pages as pictures: each page rendered as the reader shows it (marks included) to a PNG or a
 * JPEG (quality 0.9). The source is only read.
 */
object ExportImages {
    const val JPEG_QUALITY = 90
    /** About 3300 × 5100 pixels at most per picture (Redaction uses the same). */
    const val MAX_PIXELS = 17_000_000L

    /**
     * "<base> page 1.png"; with more than 9 pages the number is zero-padded to the width of the page
     * count ("page 01"), so the files sort in order.
     */
    fun fileName(baseName: String, pageIndex: Int, pageCount: Int, format: ImageFormat): String {
        val number = (pageIndex + 1).toString()
        val padded = if (pageCount > 9) number.padStart(pageCount.toString().length, '0') else number
        return "$baseName page $padded.${format.extension}"
    }

    /**
     * Renders [pages] (0-based) of [source] into [folder] and returns the files in page order. A
     * protected [source] is opened with [password] through a temporary unlocked copy, deleted at the end.
     * [isCancelled] stops between pages; the files made so far are deleted then.
     */
    fun run(
        source: File,
        pages: List<Int>,
        format: ImageFormat,
        dpi: Int,
        folder: File,
        baseName: String,
        password: String? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): List<File> {
        folder.mkdirs()
        val unlocked = password?.let { File(folder, ".unlocked-${System.nanoTime()}.pdf") }
        val made = mutableListOf<File>()
        try {
            if (password != null && PdfEngine.unlock(source, password, unlocked!!) == UnlockResult.Wrong) throw PdfException("That password didn't work.")
            val file = unlocked ?: source
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    AnnotationLayer(file, widgets = true).use { marks ->
                        val count = renderer.pageCount
                        val wanted = pages.filter { it in 0 until count }.distinct().sorted()
                        if (wanted.isEmpty()) throw PdfException("There are no pages to save.")
                        wanted.forEachIndexed { done, index ->
                            if (isCancelled()) throw InterruptedException()
                            progress(done, wanted.size)
                            renderer.openPage(index).use { page ->
                                var scale = dpi / 72f
                                val pixels = page.width.toLong() * page.height * scale * scale
                                // A very large page is drawn smaller rather than running out of memory.
                                if (pixels > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
                                val bitmap = Bitmap.createBitmap(Math.round(page.width * scale).coerceAtLeast(1), Math.round(page.height * scale).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    marks.draw(index, bitmap)
                                    val out = File(folder, fileName(baseName, index, count, format))
                                    made += out
                                    out.outputStream().use { stream ->
                                        if (format == ImageFormat.Png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                        else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        progress(wanted.size, wanted.size)
                    }
                }
            }
            return made
        } catch (e: Throwable) {
            made.forEach { it.delete() }
            throw e
        } finally {
            unlocked?.delete()
        }
    }
}
