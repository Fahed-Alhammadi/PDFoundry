package io.pdfoundry.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pdfoundry.app.BuildConfig
import io.pdfoundry.app.R
import io.pdfoundry.app.app
import io.pdfoundry.app.data.AppInfo
import io.pdfoundry.app.data.Appearance
import io.pdfoundry.app.data.formatSize
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = context.app.settings
    val store = context.app.store
    val signatures = context.app.signatures
    var page by rememberSaveable { mutableStateOf("root") }
    var appearanceMenu by remember { mutableStateOf(false) }

    when (page) {
        "about" -> {
            BackHandler { page = "root" }
            AboutScreen(onBack = { page = "root" })
            return
        }
        "signatures" -> {
            BackHandler { page = "root" }
            SavedSignaturesScreen(onBack = { page = "root" })
            return
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }) }) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsGroup {
                Box {
                    SettingsRow(Icons.Filled.Contrast, Color(0xFF5E5CE6), "Appearance", value = settings.appearance.title) { appearanceMenu = true }
                    DropdownMenu(expanded = appearanceMenu, onDismissRequest = { appearanceMenu = false }) {
                        Appearance.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title) }, onClick = { settings.update(option); appearanceMenu = false })
                        }
                    }
                }
            }
            Column {
                SettingsGroup {
                    SettingsRow(SignatureIcon, Brand.Ember, "Signatures", value = signatures.signatures.size.takeIf { it > 0 }?.toString(), chevron = true) { page = "signatures" }
                    HorizontalDivider(Modifier.padding(start = 60.dp))
                    SettingsRow(Icons.Filled.Storage, Color(0xFF8E8E93), "Documents", value = "${store.items.size} · ${formatSize(store.items.sumOf { it.size })}")
                }
                Text(
                    "Scans, new PDFs and received files are stored on this device, inside PDFoundry. Files you open from elsewhere stay where they are. Use Share to send a copy anywhere else.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }
            SettingsGroup {
                SettingsRow(Icons.Filled.Info, Brand.Steel, "About ${AppInfo.NAME}", chevron = true) { page = "about" }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column { content() }
    }
}

/** A Settings row: a white symbol on a coloured rounded tile, a title, and an optional value. */
@Composable
private fun SettingsRow(icon: ImageVector, tile: Color, title: String, value: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(title, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedSignaturesScreen(onBack: () -> Unit) {
    val store = LocalContext.current.app.signatures
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Signatures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "New signature") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().padding(16.dp)) {
            if (store.signatures.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(SignatureIcon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No signatures", style = MaterialTheme.typography.titleLarge)
                    Text("Draw your signature once and reuse it in any document.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                SignatureList(modifier = Modifier.weight(1f))
                Text("Signatures are stored only on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                SignaturePad(onUse = { image, _ ->
                    store.add(image)
                    adding = false
                })
            }
        }
    }
}

// region About

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("About ${AppInfo.NAME}") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BrandPanel()
            SettingsGroup {
                LinkRow(Icons.AutoMirrored.Filled.HelpOutline, "Help") { context.openUrl(AppInfo.HELP) }
                HorizontalDivider(Modifier.padding(start = 56.dp))
                LinkRow(Icons.Outlined.Feedback, "Send feedback") { context.openUrl(AppInfo.FEEDBACK) }
                HorizontalDivider(Modifier.padding(start = 56.dp))
                LinkRow(Icons.Outlined.StarOutline, "Rate ${AppInfo.NAME}") { context.openUrl(AppInfo.PLAY_LISTING) }
            }
            SettingsGroup {
                LinkRow(Icons.Outlined.Language, "Website", detail = AppInfo.websiteDisplay) { context.openUrl(AppInfo.WEBSITE) }
                HorizontalDivider(Modifier.padding(start = 56.dp))
                LinkRow(Icons.Outlined.PrivacyTip, "Privacy policy") { context.openUrl(AppInfo.PRIVACY) }
            }
            Text("Open source under the MIT License.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The one bold element on About: the app's mark on its dark brand colour. */
@Composable
private fun BrandPanel() {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Brand.Graphite, Brand.Steel)))
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Brand.Ember.copy(alpha = 0.55f)))
    ) {
        // A faint oversized page as the brand motif.
        Icon(Icons.Outlined.Description, null, tint = Color.White.copy(alpha = 0.05f), modifier = Modifier.align(Alignment.CenterEnd).offset(x = 50.dp, y = 20.dp).size(230.dp).rotate(-14f))
        Column(Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.brand_logo), null,
                Modifier.size(92.dp).shadow(16.dp, RoundedCornerShape(21.dp)).clip(RoundedCornerShape(21.dp)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(21.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text(AppInfo.NAME, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("© $year Fahed Alhammadi. All rights reserved.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Brand.EmberLight, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** A row that opens a page outside the app. */
@Composable
private fun LinkRow(icon: ImageVector, title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open outside the app", role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ArrowOutward, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

// endregion
