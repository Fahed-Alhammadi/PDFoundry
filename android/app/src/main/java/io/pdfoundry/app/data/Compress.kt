package io.pdfoundry.app.data

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Compress: images are downsampled to [dpi] where they are drawn and saved as JPEG at [quality]. */
enum class CompressLevel(val label: String, val dpi: Int, val quality: Float) {
    Smaller("Smaller", 150, 0.6f),
    Smallest("Smallest", 100, 0.4f)
}

/**
 * Makes a smaller copy of a PDF by re-encoding its images; text, vector drawing, links, form fields and
 * marks are left exactly as they are. An image keeps its transparency (its soft mask stays as it was).
 * Image masks, images with a colour-key mask and images that would not get smaller are left alone.
 */
object Compress {
    /** A result must be at least this much smaller to be kept. */
    const val MIN_GAIN = 0.05

    /**
     * Writes the compressed copy of [source] to [destination]. False (and nothing at [destination]) when
     * it would not be at least 5% smaller. [source] is only read; with [password] it is opened with it
     * and the copy keeps it.
     */
    fun run(source: File, level: CompressLevel, password: String?, destination: File): Boolean {
        val unlocked = password?.let { File(destination.parentFile, "${destination.name}.${System.nanoTime()}.unlocked") }
        val plain = password?.let { File(destination.parentFile, "${destination.name}.${System.nanoTime()}.plain") }
        try {
            if (password != null) {
                if (PdfEngine.unlock(source, password, unlocked!!, evenIfLimited = false) != UnlockResult.Full) throw PdfException("Password-protected documents are read-only.")
            }
            PDDocument.load(unlocked ?: source, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                if (document.isEncrypted) throw PdfException("Password-protected documents are read-only.")
                // Where each image is drawn largest, in points.
                val placed = HashMap<COSStream, Float>()
                for (page in document.pages) runCatching { Finder(page, placed).processPage(page) }
                for ((stream, points) in placed) runCatching { recompress(stream, points, level) }
                document.save(plain ?: destination)
            }
            // The copy keeps the source's password.
            if (password != null) PdfEngine.protect(plain!!, password, destination)
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            unlocked?.delete()
            plain?.delete()
        }
        if (destination.length() > source.length() * (1 - MIN_GAIN)) {
            destination.delete()
            return false
        }
        return true
    }

    private fun recompress(stream: COSStream, points: Float, level: CompressLevel) {
        val image = PDImageXObject(com.tom_roush.pdfbox.pdmodel.common.PDStream(stream), null)
        if (image.isStencil || stream.getDictionaryObject(COSName.MASK) != null) return
        if (image.bitsPerComponent == 1) return
        val longest = maxOf(image.width, image.height)
        // The size the image needs at this dpi where it is drawn largest (never more than it has).
        val target = (points / 72f * level.dpi).toInt().coerceIn(16, longest)
        val decoded: Bitmap = image.opaqueImage ?: return
        val scale = target.toFloat() / longest
        val scaled = if (scale < 0.999f) Bitmap.createScaledBitmap(decoded, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true) else decoded
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, (level.quality * 100).toInt(), out)
            out.toByteArray()
        }
        val width = scaled.width
        val height = scaled.height
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        // Only worth it when the image itself gets smaller.
        if (jpeg.size >= stream.length) return
        // Rewritten in place, so every page and form that uses the image keeps pointing at it.
        val softMask = stream.getItem(COSName.SMASK)
        val keep = setOf(COSName.TYPE, COSName.SUBTYPE, COSName.SMASK, COSName.INTENT, COSName.INTERPOLATE, COSName.METADATA)
        stream.keySet().toList().filter { it !in keep }.forEach { stream.removeItem(it) }
        stream.setItem(COSName.TYPE, COSName.XOBJECT)
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE)
        stream.setInt(COSName.WIDTH, width)
        stream.setInt(COSName.HEIGHT, height)
        stream.setInt(COSName.BITS_PER_COMPONENT, 8)
        stream.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
        if (softMask != null) stream.setItem(COSName.SMASK, softMask)
        stream.createRawOutputStream().use { it.write(jpeg) }
        stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
    }

    /** Records how large each image is drawn on a page (forms and patterns included). */
    private class Finder(page: PDPage, private val placed: HashMap<COSStream, Float>) : PDFGraphicsStreamEngine(page) {
        override fun drawImage(pdImage: PDImage) {
            val image = pdImage as? PDImageXObject ?: return
            val m = graphicsState.currentTransformationMatrix
            val w = kotlin.math.hypot(m.scaleX, m.shearY)
            val h = kotlin.math.hypot(m.shearX, m.scaleY)
            // Points along the image's longer side.
            val along = if (image.width >= image.height) w else h
            val stream = image.cosObject
            placed[stream] = maxOf(placed[stream] ?: 0f, along)
        }
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
