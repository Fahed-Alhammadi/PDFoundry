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
    val labels: List<String> = options,
    /** Checkbox or radio: this widget is ticked (from its appearance state). */
    val checked: Boolean = false,
    /** The field's tooltip (/TU), the name its author gave it for people; null when it has none. */
    val tooltip: String? = null
) {
    /** True when this widget is the ticked one (checkbox or radio). */
    val isOn: Boolean get() = checked

    /** What the field is called to the user: its tooltip, else the last part of its name. */
    val title: String get() = tooltip?.trim()?.takeIf { it.isNotEmpty() } ?: name.substringAfterLast('.').ifEmpty { "Text field" }
}

internal fun kindOf(field: PDField): FieldKind? = when (field) {
    is PDTextField -> if (field.isMultiline) FieldKind.MultilineText else FieldKind.Text
    is PDCheckBox -> FieldKind.Checkbox
    is PDRadioButton -> FieldKind.Radio
    is PDChoice -> FieldKind.Choice
    is PDSignatureField -> FieldKind.Signature
    else -> null // push buttons have nothing to fill in
}

/** The name of a checkbox or radio widget's "on" appearance state. */
internal fun onStateOf(widget: PDAnnotationWidget): String? {
    val normal = widget.appearance?.normalAppearance ?: return null
    if (!normal.isSubDictionary) return null
    return normal.subDictionary.keys.map { it.name }.firstOrNull { it != COSName.Off.name }
}

/**
 * The value a checkbox or radio widget stands for: its export value when the field has /Opt (one
 * entry per widget, the appearance states then usually being "0", "1"…), otherwise its on state.
 */
internal fun onValueOf(field: com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton, index: Int): String? {
    val exports = runCatching { field.exportValues }.getOrNull().orEmpty()
    if (exports.isNotEmpty()) return exports.getOrNull(index)
    return field.widgets.getOrNull(index)?.let { onStateOf(it) }
}

/** True when a checkbox or radio widget is shown ticked. */
internal fun checkedOf(widget: PDAnnotationWidget): Boolean = widget.appearanceState?.name?.let { it != COSName.Off.name } ?: false

/**
 * Ticks the widget [index] of a checkbox or radio field (or clears the field with "Off"): every widget
 * standing for the same value is shown on, the others off, and /V names the on state, as Acrobat does.
 */
internal fun setButton(field: com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton, index: Int, on: Boolean) {
    val widgets = field.widgets
    val exports = runCatching { field.exportValues }.getOrNull().orEmpty()
    val chosen = if (on) widgets.getOrNull(index) else null
    val chosenValue = chosen?.let { if (exports.isNotEmpty()) exports.getOrNull(index) else onStateOf(it) }
    widgets.forEachIndexed { position, widget ->
        val value = if (exports.isNotEmpty()) exports.getOrNull(position) else onStateOf(widget)
        val state = if (chosen != null && (position == index || value == chosenValue)) onStateOf(widget) else null
        widget.setAppearanceState(state ?: COSName.Off.name)
    }
    field.cosObject.setItem(COSName.V, COSName.getPDFName(chosen?.let { onStateOf(it) } ?: COSName.Off.name))
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
    // A widget turned with /MK /R is drawn in its own upright box, which the matrix turns onto the page.
    val rotation = ((runCatching { widget.appearanceCharacteristics?.rotation }.getOrNull() ?: 0) % 360 + 360) % 360
    val sideways = rotation == 90 || rotation == 270
    val width = if (sideways) rect.height else rect.width
    val height = if (sideways) rect.width else rect.height
    if (width <= 0f || height <= 0f) return
    val stream = PDAppearanceStream(document).apply {
        bBox = PDRectangle(width, height)
        resources = PDResources()
        if (rotation != 0) setMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(rotation.toDouble()), 0f, 0f).createAffineTransform())
    }
    if (value.isNotEmpty()) {
        val padding = minOf(2f, height * 0.1f)
        // About a 12-point line, smaller if the field is small.
        val lineHeight = minOf(height - padding * 2, 16f).coerceAtLeast(1f)
        val single = PdfEngine.textBitmap(value, MarkInk)
        val bitmap = if (multiline) {
            // Wrapped to the field's width at the same text size as one line, on as many lines as it needs.
            PdfEngine.textBlockBitmap(value, ((width - padding * 2) * single.height / lineHeight).toInt().coerceAtLeast(1), MarkInk)
        } else single
        val scale = if (multiline) minOf((width - padding * 2) / bitmap.width, (height - padding * 2) / bitmap.height, lineHeight / single.height)
        else minOf((width - padding * 2) / bitmap.width, lineHeight / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val rightToLeft = !java.text.Bidi(value, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
        val x = if (rightToLeft && !multiline) width - padding - drawnWidth else padding
        val y = if (multiline) height - padding - drawnHeight else (height - drawnHeight) / 2
        PDPageContentStream(document, stream).use { content ->
            content.drawImage(LosslessFactory.createFromImage(document, bitmap), x, y, drawnWidth, drawnHeight)
        }
    } else {
        PDPageContentStream(document, stream).use { }
    }
    widget.appearance = PDAppearanceDictionary().apply { setNormalAppearance(stream) }
}
