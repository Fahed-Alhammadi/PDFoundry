package io.pdfoundry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.concurrent.CancellationException

/** One recognised word: its text (in reading order) and its box in pixels of the page picture. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Make Searchable: recognises the words on pages that have no text and adds them as invisible text over
 * the words (text render mode 3), so search, selection and copy work while the page looks exactly the
 * same. Tesseract runs on the device with the bundled English and Arabic "fast" models; nothing is
 * downloaded.
 */
object Ocr {
    const val DPI = 300
    const val LANGUAGES = "eng+ara"
    private const val MAX_PIXELS = 12_000_000L
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The pages (0-based) with no extractable text: the ones Make Searchable would process. */
    fun pagesNeedingText(file: File, password: String? = null): List<Int> =
        (if (password == null) PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()) else PDDocument.load(file, password, MemoryUsageSetting.setupTempFileOnly())).use { document ->
            val stripper = PDFTextStripper()
            (0 until document.numberOfPages).filter { index ->
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                runCatching { stripper.getText(document) }.getOrDefault("").isBlank()
            }
        }

    /** The bundled language data, copied once from the app's assets to its private files. */
    private fun dataPath(context: Context): String {
        val root = File(context.filesDir, "tesseract")
        val folder = File(root, "tessdata").apply { mkdirs() }
        for (name in LANGUAGES.split("+").map { "$it.traineddata" }) {
            val target = File(folder, name)
            val size = context.assets.openFd("tessdata/$name").use { it.length }
            if (target.length() == size) continue
            val temp = File(folder, "$name.tmp")
            context.assets.open("tessdata/$name").use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        }
        return root.path
    }

    /**
     * Recognises [pages] of [file] and adds their text. The work is done on a copy; [file] is replaced
     * only when every page is done and some text was found, so Cancel ([isCancelled], checked between
     * and during pages) or a failure leaves it exactly as it was. With [password], [file] is opened with
     * it and saved protected with it. Returns how many pages got text (0: nothing changed).
     */
    fun run(file: File, pages: List<Int>, password: String? = null, progress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): Int {
        val context = appContext ?: throw PdfException("Text recognition isn't ready.")
        if (pages.isEmpty()) return 0
        val stamp = System.nanoTime()
        val plain = File(file.parentFile, "${file.name}.ocr-source.$stamp")
        val result = File(file.parentFile, "${file.name}.ocr.$stamp")
        try {
            val source = if (password != null) {
                if (PdfEngine.unlock(file, password, plain, evenIfLimited = false) != UnlockResult.Full) throw PdfException("Password-protected documents are read-only.")
                plain
            } else file
            val found = HashMap<Int, Pair<List<OcrWord>, Float>>()
            val api = TessBaseAPI()
            try {
                if (!api.init(dataPath(context), LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) throw PdfException("Text recognition could not start.")
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        pages.filter { it in 0 until renderer.pageCount }.forEachIndexed { done, index ->
                            if (isCancelled()) throw CancellationException()
                            progress(done, pages.size)
                            val (picture, scale) = renderer.openPage(index).use { page ->
                                var scale = DPI / 72f
                                val pixels = page.width.toLong() * page.height * scale * scale
                                if (pixels > MAX_PIXELS) scale *= kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
                                val bitmap = Bitmap.createBitmap(Math.round(page.width * scale).coerceAtLeast(1), Math.round(page.height * scale).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                bitmap to scale
                            }
                            try {
                                val words = recognise(api, picture, isCancelled)
                                if (words.isNotEmpty()) found[index] = words to scale
                            } finally {
                                picture.recycle()
                            }
                        }
                    }
                }
            } finally {
                api.recycle()
            }
            if (isCancelled()) throw CancellationException()
            progress(pages.size, pages.size)
            if (found.isEmpty()) return 0
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val arabic = context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { PDType0Font.load(document, it, true) }
                for ((index, value) in found) addInvisibleText(document, index, value.first, value.second, arabic)
                document.save(result)
            }
            if (isCancelled()) throw CancellationException()
            if (password != null) {
                PdfEngine.protect(result, password, file)
            } else if (!result.renameTo(file)) {
                result.copyTo(file, overwrite = true)
            }
            return found.size
        } finally {
            plain.delete()
            result.delete()
        }
    }

    private fun recognise(api: TessBaseAPI, picture: Bitmap, isCancelled: () -> Boolean): List<OcrWord> {
        api.setImage(picture)
        // Recognising one page takes seconds: Cancel stops Tesseract in the middle of it.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread {
            while (!done.get()) {
                if (isCancelled()) {
                    api.stop()
                    return@Thread
                }
                try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        try {
            // getHOCRText runs recognition with Tesseract's progress monitor, which is what stop() cancels.
            api.getHOCRText(0)
        } finally {
            done.set(true)
            watcher.interrupt()
            watcher.join()
        }
        if (isCancelled()) throw CancellationException()
        val words = mutableListOf<OcrWord>()
        val iterator = api.resultIterator ?: return words
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (text.isNotEmpty() && confidence >= 30f && box.width() > 0 && box.height() > 0) {
                    words += OcrWord(text, box.left, box.top, box.right, box.bottom)
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            iterator.delete()
        }
        api.clear()
        return words
    }

    private fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    /**
     * An Arabic word as it is drawn: right to left, so its glyphs are written in visual order (each base
     * letter with its marks). Text extraction turns visual order back into reading order, as it does for
     * any PDF with Arabic text, so search and copy give the word as recognised.
     */
    internal fun visualOrder(word: String): String {
        val clusters = mutableListOf<StringBuilder>()
        for (c in word) {
            val mark = Character.getType(c) == Character.NON_SPACING_MARK.toInt()
            if (mark && clusters.isNotEmpty()) clusters.last().append(c) else clusters += StringBuilder().append(c)
        }
        return clusters.reversed().joinToString("")
    }

    /**
     * Writes [words] (boxes in pixels of a picture of the displayed page at [scale] pixels per point) as
     * invisible text, each word stretched to its box, on the page as displayed (rotation and crop box).
     */
    internal fun addInvisibleText(document: PDDocument, pageIndex: Int, words: List<OcrWord>, scale: Float, arabic: PDFont) {
        val page = document.getPage(pageIndex)
        val frame = PageFrame(page)
        val latin = PDType1Font.HELVETICA
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.transform(frame.toPage)
            stream.beginText()
            stream.setRenderingMode(RenderingMode.NEITHER)
            for (word in words) {
                val rtl = word.text.any(::isArabic)
                val font = if (rtl) arabic else latin
                val shown = if (rtl) visualOrder(word.text) else word.text
                val natural = runCatching { font.getStringWidth(shown) / 1000f }.getOrNull() ?: continue
                if (natural <= 0f) continue
                val left = word.left / scale
                val right = word.right / scale
                val bottom = frame.height - word.bottom / scale
                val height = (word.bottom - word.top) / scale
                val size = height.coerceAtLeast(1f)
                val stretch = ((right - left) / (natural * size) * 100f).coerceIn(10f, 1000f)
                runCatching {
                    stream.setFont(font, size)
                    stream.setHorizontalScaling(stretch)
                    // The baseline a fifth of the way up the box: the rest is for letters that hang below it.
                    stream.setTextMatrix(Matrix(1f, 0f, 0f, 1f, left, bottom + height * 0.2f))
                    stream.showText(shown)
                }
            }
            stream.endText()
        }
    }
}
