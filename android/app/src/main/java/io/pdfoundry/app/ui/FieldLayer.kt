package io.pdfoundry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.pdfoundry.app.data.FieldKind
import io.pdfoundry.app.data.FormField

/** The light tint that shows where a form field is, as in Acrobat. */
private val FieldTint = Color(0x332F6FEB)
private val FieldEdge = Color(0x662F6FEB)
private val ReadOnlyTint = Color(0x1A808080)

/**
 * The form fields of one page, drawn over the page image: a tinted box per field. Tapping a box fills it
 * in: text opens a small editor, a checkbox or radio button is ticked, a choice opens its list, a
 * signature field asks for a signature. The page image shows the saved value (the field's appearance),
 * so the box itself stays empty. Only shown while no tool is in use: a tool always wins over a field.
 */
@Composable
fun FieldLayer(fields: List<FormField>, enabled: Boolean, onText: (FormField) -> Unit, onSet: (FormField, String) -> Unit, onSign: (FormField) -> Unit) {
    if (fields.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        for (field in fields) {
            val usable = enabled && !field.readOnly
            var choosing by remember(field.name, field.widget) { mutableStateOf(false) }
            Box(
                Modifier
                    .offset(x = width * field.rect.left, y = height * field.rect.top)
                    .size(width * field.rect.width(), height * field.rect.height())
                    .background(if (field.readOnly) ReadOnlyTint else FieldTint)
                    .border(0.5.dp, if (field.readOnly) Color.Transparent else FieldEdge)
                    .clickable(enabled = usable, role = roleOf(field.kind), onClickLabel = actionOf(field.kind)) {
                        when (field.kind) {
                            FieldKind.Text, FieldKind.MultilineText -> onText(field)
                            FieldKind.Checkbox -> onSet(field, if (field.isOn) "Off" else field.onValue ?: "Yes")
                            FieldKind.Radio -> if (!field.isOn) field.onValue?.let { onSet(field, it) }
                            FieldKind.Choice -> choosing = true
                            FieldKind.Signature -> onSign(field)
                        }
                    }
                    .semantics {
                        contentDescription = "${describe(field.kind)} ${field.name}"
                        stateDescription = when (field.kind) {
                            FieldKind.Checkbox, FieldKind.Radio -> if (field.isOn) "Selected" else "Not selected"
                            FieldKind.Signature -> ""
                            else -> field.value.ifEmpty { "Empty" }
                        }
                    }
            ) {
                if (field.kind == FieldKind.Choice) {
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        field.options.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = { Text(field.labels.getOrElse(index) { option }) },
                                trailingIcon = { if (option == field.value) Icon(Icons.Filled.Check, "Selected") },
                                onClick = { choosing = false; onSet(field, option) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun roleOf(kind: FieldKind) = when (kind) {
    FieldKind.Checkbox -> Role.Checkbox
    FieldKind.Radio -> Role.RadioButton
    FieldKind.Choice -> Role.DropdownList
    else -> Role.Button
}

private fun actionOf(kind: FieldKind) = when (kind) {
    FieldKind.Checkbox -> "Tick or clear"
    FieldKind.Radio -> "Choose"
    FieldKind.Choice -> "Choose an option"
    FieldKind.Signature -> "Sign"
    else -> "Fill in"
}

private fun describe(kind: FieldKind) = when (kind) {
    FieldKind.Text, FieldKind.MultilineText -> "Text field"
    FieldKind.Checkbox -> "Checkbox"
    FieldKind.Radio -> "Option"
    FieldKind.Choice -> "List"
    FieldKind.Signature -> "Signature field"
}

/** Types a text field's value; Done (or the keyboard's Done for one line) saves it. */
@Composable
fun FieldTextDialog(field: FormField, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember(field) { mutableStateOf(field.value) }
    val multiline = field.kind == FieldKind.MultilineText
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.name.substringAfterLast('.').ifEmpty { "Text field" }) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("Saved as a form field, so other apps can still change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
