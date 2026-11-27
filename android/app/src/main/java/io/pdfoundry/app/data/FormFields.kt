package io.pdfoundry.app.data

import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

enum class FieldKind { Text, MultilineText, Checkbox, Radio, Choice, Signature }

/**
 * One place to fill in on a page: a form field's widget. A field with several widgets (a radio group,
 * a field repeated on two pages) is listed once per widget; [widget] says which.
 *
 * [rect] is in fractions of the displayed page from its top-left (rotation and crop box applied), like
 * every other position in the app. [value] is the field's current value: text, the selected option,
 * or for a checkbox or radio button the "on" name of the selected widget ("Off" when none). [onValue]
 * is the value this widget stands for (checkbox and radio only). [options] are a choice's values and
 * [labels] how they are shown.
 */
data class FormField(
    val name: String,
    val kind: FieldKind,
    val page: Int,
    val rect: RectF,
    val value: String,
    val options: List<String> = emptyList(),
    val onValue: String? = null,
    val readOnly: Boolean = false,
    val widget: Int = 0,
    val labels: List<String> = options
) {
    /** True when this widget is the selected one (checkbox or radio), or the field has a value. */
    val isOn: Boolean get() = onValue != null && value == onValue
}

internal fun kindOf(field: PDField): FieldKind? = when (field) {
    is PDTextField -> if (field.isMultiline) FieldKind.MultilineText else FieldKind.Text
    is PDCheckBox -> FieldKind.Checkbox
    is PDRadioButton -> FieldKind.Radio
    is PDChoice -> FieldKind.Choice
    is PDSignatureField -> FieldKind.Signature
    else -> null // push buttons have nothing to fill in
}

/** The name a checkbox or radio widget is "on" as, from its appearance states. */
internal fun onValueOf(widget: PDAnnotationWidget): String? {
    val normal = widget.appearance?.normalAppearance ?: return null
    if (!normal.isSubDictionary) return null
    return normal.subDictionary.keys.map { it.name }.firstOrNull { it != COSName.Off.name }
}

internal fun valueOf(field: PDField): String = when (field) {
    is PDTextField -> field.value.orEmpty()
    is PDChoice -> field.value.firstOrNull().orEmpty()
    is PDCheckBox, is PDRadioButton -> field.valueAsString.ifEmpty { COSName.Off.name }
    else -> ""
}

/**
 * Gives a text field's widget an appearance that shows [value] drawn by Android, as an image: used when
 * the field's own font can't show the text (Arabic in a Helvetica field, say). The value itself stays
 * real text in /V, so other apps can read and change it and will draw their own appearance.
 */
internal fun imageAppearance(document: PDDocument, widget: PDAnnotationWidget, value: String, multiline: Boolean) {
    val rect = widget.rectangle ?: return
    val width = rect.width
    val height = rect.height
    if (width <= 0f || height <= 0f) return
    val stream = PDAppearanceStream(document).apply {
        bBox = PDRectangle(width, height)
        resources = PDResources()
    }
    if (value.isNotEmpty()) {
        val bitmap = PdfEngine.textBitmap(value, MarkInk)
        val padding = minOf(2f, height * 0.1f)
        // About a 12-point line, smaller if the field is small; never wider than the field.
        val lineHeight = minOf(height - padding * 2, 16f)
        val scale = minOf((width - padding * 2) / bitmap.width, lineHeight / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val rightToLeft = !java.text.Bidi(value, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
        val x = if (rightToLeft) width - padding - drawnWidth else padding
        val y = if (multiline) height - padding - drawnHeight else (height - drawnHeight) / 2
        PDPageContentStream(document, stream).use { content ->
            content.drawImage(LosslessFactory.createFromImage(document, bitmap), x, y, drawnWidth, drawnHeight)
        }
    } else {
        PDPageContentStream(document, stream).use { }
    }
    widget.appearance = PDAppearanceDictionary().apply { setNormalAppearance(stream) }
}
