package io.pdfoundry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pdfoundry.app.data.Mark
import io.pdfoundry.app.data.TextMark

/** A highlight colour, as on iOS. */
data class HighlightColor(val name: String, val argb: Int)

val HighlightColors = listOf(
    HighlightColor("Yellow", android.graphics.Color.argb(128, 255, 204, 0)),
    HighlightColor("Green", android.graphics.Color.argb(102, 52, 199, 89)),
    HighlightColor("Pink", android.graphics.Color.argb(102, 255, 45, 85)),
    HighlightColor("Blue", android.graphics.Color.argb(89, 0, 122, 255))
)

/** Underline and strikethrough are drawn in red. */
val MarkRed: Int = android.graphics.Color.rgb(255, 59, 48)

/**
 * The reader's bottom bar. Reading: Markup, Fill & Sign, Pages and Undo. In a mode: close (×), that
 * mode's tools, and Undo, always at the right end. Fill & Sign: Signature, Initials, Text, Date, ✓, ✗;
 * where the bar is too narrow for all of them (most phones, or large text), ✓ and ✗ share one "Mark" menu.
 */
@Composable
fun ModeBar(
    mode: ToolMode,
    canUndo: Boolean,
    onMode: (ToolMode) -> Unit,
    onPages: () -> Unit,
    onUndo: () -> Unit,
    onTextMark: (TextMark, Int) -> Unit,
    onText: () -> Unit,
    onDraw: () -> Unit,
    onSignature: () -> Unit,
    onInitials: () -> Unit = {},
    onDate: () -> Unit = {},
    onMark: (Mark) -> Unit = {}
) {
    Surface(tonalElevation = 3.dp) {
      BoxWithConstraints {
        val foldMarks = maxWidth < 480.dp || largeText()
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).heightIn(min = 72.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (mode) {
                ToolMode.None -> {
                    // Four buttons have room for their labels at any text size (wrapped onto two lines if needed).
                    ToolButton(Icons.Filled.Edit, "Markup", iconOnlyWhenLarge = false) { onMode(ToolMode.Markup) }
                    ToolButton(Icons.Filled.HistoryEdu, "Fill & Sign", iconOnlyWhenLarge = false) { onMode(ToolMode.FillSign) }
                    ToolButton(Icons.Filled.GridView, "Pages", iconOnlyWhenLarge = false, onClick = onPages)
                }
                ToolMode.Markup -> {
                    CloseButton("Close Markup") { onMode(ToolMode.None) }
                    HighlightButton { onTextMark(TextMark.Highlight, it) }
                    ToolButton(Icons.Filled.FormatUnderlined, "Underline") { onTextMark(TextMark.Underline, MarkRed) }
                    ToolButton(Icons.Filled.FormatStrikethrough, "Strikethrough") { onTextMark(TextMark.Strikethrough, MarkRed) }
                    ToolButton(Icons.Filled.TextFields, "Text", onClick = onText)
                    ToolButton(Icons.Filled.Draw, "Draw", onClick = onDraw)
                }
                ToolMode.FillSign -> {
                    CloseButton("Close Fill & Sign") { onMode(ToolMode.None) }
                    ToolButton(SignatureIcon, "Signature", onClick = onSignature)
                    ToolButton(Icons.Filled.Abc, "Initials", onClick = onInitials)
                    ToolButton(Icons.Filled.TextFields, "Text", onClick = onText)
                    ToolButton(Icons.Filled.CalendarToday, "Date", onClick = onDate)
                    if (foldMarks) {
                        MarkButton(onMark)
                    } else {
                        ToolButton(Icons.Filled.Check, Mark.Check.label) { onMark(Mark.Check) }
                        ToolButton(Icons.Filled.Close, Mark.Cross.label) { onMark(Mark.Cross) }
                    }
                }
            }
            ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = canUndo, iconOnlyWhenLarge = mode != ToolMode.None, onClick = onUndo)
        }
      }
    }
}

/** ✓ and ✗ under one button, for a narrow bar. */
@Composable
private fun RowScope.MarkButton(onPick: (Mark) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Row { ToolButton(Icons.Filled.Check, "Mark", trailing = Icons.Filled.ArrowDropDown) { open = true } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(Mark.Check.label) }, leadingIcon = { Icon(Icons.Filled.Check, null) }, onClick = { open = false; onPick(Mark.Check) })
            DropdownMenuItem(text = { Text(Mark.Cross.label) }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { open = false; onPick(Mark.Cross) })
        }
    }
}

/** Accessibility text sizes. */
@Composable
private fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.3f

/**
 * A labelled tool: icon above a one-line label that shrinks to fit its share of the bar. At accessibility
 * text sizes a crowded bar's labels would not fit, so with [iconOnlyWhenLarge] the button shows its icon
 * only and the label is read aloud; otherwise the label may wrap onto two lines.
 */
@Composable
fun RowScope.ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, trailing: ImageVector? = null, iconOnlyWhenLarge: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val large = largeText()
    val compact = large && iconOnlyWhenLarge
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (compact) contentDescription = label }
            .padding(horizontal = 2.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            if (trailing != null) Icon(trailing, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        if (!compact) {
            val density = LocalDensity.current
            val base = MaterialTheme.typography.labelSmall
            Text(
                label,
                style = base,
                color = tint,
                maxLines = if (large) 2 else 1,
                softWrap = large,
                textAlign = TextAlign.Center,
                // Shrinks a long label ("Strikethrough") on a narrow phone instead of cutting it off.
                autoSize = TextAutoSize.StepBased(minFontSize = (8f / density.fontScale).sp, maxFontSize = base.fontSize, stepSize = 0.5.sp),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CloseButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.widthIn(min = 44.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, label, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RowScope.HighlightButton(onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Row { ToolButton(Icons.Outlined.BorderColor, "Highlight", trailing = Icons.Filled.ArrowDropDown) { open = true } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HighlightColors.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    leadingIcon = {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(option.argb).copy(alpha = 1f)).semantics { role = Role.Image })
                    },
                    onClick = { open = false; onPick(option.argb) }
                )
            }
        }
    }
}
