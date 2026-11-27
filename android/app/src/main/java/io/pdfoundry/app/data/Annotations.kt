package io.pdfoundry.app.data

import android.graphics.Color
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.util.Calendar
import java.util.UUID

/** What a mark on a page is. Text markup belongs to its text: it can be deleted, not moved. */
enum class MarkKind { Highlight, Underline, Strikethrough, Ink, Stamp, FreeText, Rectangle, Ellipse, Line, Arrow, Comment, Other }

/**
 * A mark on a page. [id] is its `/NM` (or `#<index>` in the page's annotations for an old mark
 * without one); [rect] is in fractions of the displayed page from the top-left.
 */
data class MarkInfo(val id: String, val kind: MarkKind, val rect: RectF, val movable: Boolean)

/** The shapes in Markup › Shapes. */
enum class ShapeKind(val label: String) { Rectangle("Rectangle"), Ellipse("Ellipse"), Line("Line"), Arrow("Arrow") }

/**
 * PDFoundry's marks are real PDF annotations, each with its own appearance stream, so every viewer draws
 * them the same way and they stay separate objects that can be moved, resized and deleted later.
 */
object Annotations {
    /** The app-private key on every annotation PDFoundry creates. */
    val TAG: COSName = COSName.getPDFName("PDFoundry")
    const val PREFIX = "pdfoundry-"

    fun tag(a: PDAnnotation) {
        a.cosObject.setBoolean(TAG, true)
        a.annotationName = PREFIX + UUID.randomUUID().toString()
    }

    /** Made by PDFoundry: the tag, or (if a library dropped custom keys) the `/NM` prefix. */
    fun isOurs(a: PDAnnotation): Boolean =
        a.cosObject.getBoolean(TAG, false) || a.annotationName?.startsWith(PREFIX) == true

    fun kind(a: PDAnnotation): MarkKind = kind(a.cosObject)

    fun kind(dict: COSDictionary): MarkKind = when (dict.getNameAsString(COSName.SUBTYPE)) {
        "Highlight" -> MarkKind.Highlight
        "Underline" -> MarkKind.Underline
        "StrikeOut" -> MarkKind.Strikethrough
        "Ink" -> MarkKind.Ink
        "Stamp" -> MarkKind.Stamp
        "FreeText" -> MarkKind.FreeText
        "Square" -> MarkKind.Rectangle
        "Circle" -> MarkKind.Ellipse
        "Line" -> {
            val ends = dict.getDictionaryObject(COSName.LE) as? COSArray
            val end = (ends?.getObject(1) as? COSName)?.name
            if (end != null && end != "None") MarkKind.Arrow else MarkKind.Line
        }
        "Text" -> MarkKind.Comment
        else -> MarkKind.Other
    }

    fun isTextMarkup(kind: MarkKind) = kind == MarkKind.Highlight || kind == MarkKind.Underline || kind == MarkKind.Strikethrough

    /** Ours and not text markup. */
    fun isMovable(a: PDAnnotation): Boolean = isOurs(a) && kind(a).let { it != MarkKind.Other && !isTextMarkup(it) }

    /** Any mark of the kinds PDFoundry makes, old untagged ones included. Links, form fields and popups are not. */
    fun isDeletable(a: PDAnnotation): Boolean = kind(a) != MarkKind.Other
}

/**
 * The page as the user sees it: display points with the origin at the bottom-left of the displayed
 * page (rotation and crop box applied), and the conversion to and from PDF user space.
 */
internal class PageFrame(page: PDPage) {
    val box: PDRectangle = page.cropBox
    val rotation = ((page.rotation % 360) + 360) % 360
    private val sideways = rotation == 90 || rotation == 270
    val width = if (sideways) box.height else box.width
    val height = if (sideways) box.width else box.height

    // Display points to user space: user = (a x + c y + e, b x + d y + f).
    private val a: Float
    private val b: Float
    private val c: Float
    private val d: Float
    private val e: Float
    private val f: Float

    init {
        val values = when (rotation) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, box.lowerLeftX + box.width, box.lowerLeftY)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, box.lowerLeftX + box.width, box.lowerLeftY + box.height)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, box.lowerLeftX, box.lowerLeftY + box.height)
            else -> floatArrayOf(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
        }
        a = values[0]; b = values[1]; c = values[2]; d = values[3]; e = values[4]; f = values[5]
    }

    val toPage: Matrix get() = Matrix(a, b, c, d, e, f)

    /** The turn only, as an appearance stream's `/Matrix`: it draws display-oriented content upright on screen. */
    fun turn(): COSArray = floats(a, b, c, d, 0f, 0f)

    fun user(x: Float, y: Float): FloatArray = floatArrayOf(a * x + c * y + e, b * x + d * y + f)

    /** User space to display points (the turn is orthonormal, so its inverse is its transpose). */
    fun display(ux: Float, uy: Float): FloatArray {
        val dx = ux - e
        val dy = uy - f
        return floatArrayOf(a * dx + b * dy, c * dx + d * dy)
    }

    /** A point given as fractions from the top-left, in display points. */
    fun point(p: PagePoint): FloatArray = floatArrayOf(p.x * width, height - p.y * height)

    /** A rect in display points (left, bottom, right, top) as a user-space rectangle. */
    fun userRect(left: Float, bottom: Float, right: Float, top: Float): PDRectangle {
        val p = user(left, bottom)
        val q = user(right, top)
        return PDRectangle(minOf(p[0], q[0]), minOf(p[1], q[1]), kotlin.math.abs(q[0] - p[0]), kotlin.math.abs(q[1] - p[1]))
    }

    /** Fractions of the displayed page (top-left origin) as a user-space rectangle. */
    fun userRect(fractions: RectF): PDRectangle =
        userRect(fractions.left * width, height - fractions.bottom * height, fractions.right * width, height - fractions.top * height)

    /** A user-space rectangle as fractions of the displayed page from the top-left. */
    fun fractions(r: PDRectangle): RectF {
        val p = display(r.lowerLeftX, r.lowerLeftY)
        val q = display(r.upperRightX, r.upperRightY)
        val left = minOf(p[0], q[0]) / width
        val right = maxOf(p[0], q[0]) / width
        val top = 1f - maxOf(p[1], q[1]) / height
        val bottom = 1f - minOf(p[1], q[1]) / height
        return RectF(left, top, right, bottom)
    }
}

internal fun floats(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }

internal fun rgb(argb: Int) = PDColor(floatArrayOf(Color.red(argb) / 255f, Color.green(argb) / 255f, Color.blue(argb) / 255f), PDDeviceRGB.INSTANCE)

/**
 * Builds the annotations PDFoundry makes. Every one gets the tag, the print flag, a `/Rect` in user
 * space and an `/AP /N` appearance drawn in display orientation (its `/Matrix` turns it with the page),
 * so it looks the same in PdfRenderer, Chrome, Preview and Acrobat.
 */
internal object AnnotationWriter {
    /**
     * Adds an annotation of [subtype] covering the display-point rect ([left], [bottom], [w], [h]) on
     * [pageIndex]. [draw] paints the appearance with its origin at the rect's bottom-left corner.
     */
    fun add(
        document: PDDocument,
        pageIndex: Int,
        subtype: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        configure: (COSDictionary, PageFrame) -> Unit = { _, _ -> },
        draw: (PDPageContentStream) -> Unit
    ): PDAnnotation {
        val page = document.getPage(pageIndex)
        val frame = PageFrame(page)
        val width = w.coerceAtLeast(0.5f)
        val height = h.coerceAtLeast(0.5f)
        val dict = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setName(COSName.SUBTYPE, subtype)
        }
        val annotation = PDAnnotation.createAnnotation(dict)
        annotation.rectangle = frame.userRect(left, bottom, left + width, bottom + height)
        annotation.isPrinted = true
        annotation.page = page
        annotation.setModifiedDate(Calendar.getInstance())
        Annotations.tag(annotation)
        configure(dict, frame)
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance(document, frame, width, height, draw)) }
        val list = page.annotations
        list.add(annotation)
        page.annotations = list
        return annotation
    }

    fun appearance(document: PDDocument, frame: PageFrame, w: Float, h: Float, draw: (PDPageContentStream) -> Unit): PDAppearanceStream {
        val stream = PDAppearanceStream(document)
        stream.resources = PDResources()
        stream.bBox = PDRectangle(0f, 0f, w, h)
        stream.cosObject.setItem(COSName.MATRIX, frame.turn())
        PDPageContentStream(document, stream).use { draw(it) }
        return stream
    }
}

/** Finds a page's annotation by the id [MarkInfo] gives it. */
internal fun PDPage.annotation(id: String): PDAnnotation? {
    val list = annotations
    if (id.startsWith("#")) return id.drop(1).toIntOrNull()?.let { list.getOrNull(it) }
    return list.firstOrNull { it.annotationName == id }
}

internal fun idOf(annotation: PDAnnotation, index: Int): String = annotation.annotationName?.takeIf { it.isNotEmpty() } ?: "#$index"

internal fun COSBase?.floatsOrNull(): FloatArray? = (this as? COSArray)?.toFloatArray()
