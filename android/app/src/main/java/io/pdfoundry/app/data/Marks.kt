package io.pdfoundry.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The ink used for typed text and marks: the brand's graphite. */
const val MarkInk: Int = 0xFF12141C.toInt()

/** A tick or a cross for Fill & Sign, placed and resized like a signature. */
enum class Mark(val label: String) { Check("Check"), Cross("Cross") }

/** The mark as a transparent square bitmap, drawn with round caps; large enough to stay sharp when zoomed. */
fun Mark.bitmap(side: Int = 512): Bitmap {
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MarkInk
        style = Paint.Style.STROKE
        strokeWidth = side * 0.12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val s = side.toFloat()
    val path = Path().apply {
        when (this@bitmap) {
            Mark.Check -> {
                moveTo(0.16f * s, 0.54f * s)
                lineTo(0.40f * s, 0.78f * s)
                lineTo(0.86f * s, 0.22f * s)
            }
            Mark.Cross -> {
                moveTo(0.2f * s, 0.2f * s)
                lineTo(0.8f * s, 0.8f * s)
                moveTo(0.8f * s, 0.2f * s)
                lineTo(0.2f * s, 0.8f * s)
            }
        }
    }
    Canvas(bitmap).drawPath(path, paint)
    return bitmap
}

/** Today's date as the Date tool places it: the locale's medium style, without the time ("Nov 27, 2026"). */
fun todayText(date: Date = Date(), locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date)
