package io.pdfoundry.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import io.pdfoundry.app.data.Appearance
import java.io.File

/** PDFoundry's brand palette: furnace graphite and ember orange. */
object Brand {
    val Ember = Color(0xFFE2511B)
    val EmberBright = Color(0xFFFF7438)
    val EmberLight = Color(0xFFFFC299)
    val Graphite = Color(0xFF12141C)
    val Steel = Color(0xFF2B3042)
}

private val LightColors = lightColorScheme(
    primary = Brand.Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF5D6270),
    secondaryContainer = Color(0xFFE6E7EE),
    onSecondaryContainer = Color(0xFF1A1C24),
    background = Color(0xFFF6F4F1),
    surface = Color(0xFFF6F4F1),
    surfaceContainer = Color(0xFFEDEAE6),
    surfaceContainerHigh = Color(0xFFE7E4E0),
    surfaceContainerLow = Color(0xFFFBF9F7)
)

private val DarkColors = darkColorScheme(
    primary = Brand.EmberBright,
    onPrimary = Color(0xFF2A0A00),
    primaryContainer = Color(0xFF7A2A0C),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFC3C6D3),
    secondaryContainer = Color(0xFF353949),
    background = Brand.Graphite,
    surface = Brand.Graphite,
    surfaceContainer = Color(0xFF1D2029),
    surfaceContainerHigh = Color(0xFF272B37),
    surfaceContainerLow = Color(0xFF171A22)
)

@Composable
fun PDFoundryTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

// region Leaving the app: share, print, links

private fun Context.shareUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

fun Context.sharePdfs(files: List<File>) {
    if (files.isEmpty()) return
    val intent = if (files.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, shareUri(files[0]))
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { shareUri(it) }))
    }
    intent.type = "application/pdf"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, null))
}

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Sends the file to the system print dialog as it is. Must be called with an Activity context. */
fun Context.printPdf(file: File) {
    val manager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (signal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback.onLayoutFinished(info, old != new)
        }

        override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, signal: CancellationSignal?, callback: WriteResultCallback) {
            try {
                file.inputStream().use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(destination).use { input.copyTo(it) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    manager.print(file.nameWithoutExtension, adapter, null)
}

// endregion
