package io.pdfoundry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class SavedSignature(val file: File, val image: Bitmap)

/** Signatures the user has drawn, kept as transparent PNGs in the app's private storage. */
class SignatureStore(context: Context) {
    private val directory = File(context.filesDir, "signatures").apply { mkdirs() }

    var signatures by mutableStateOf<List<SavedSignature>>(emptyList())
        private set

    init {
        load()
    }

    fun add(image: Bitmap) {
        val file = File(directory, "${System.currentTimeMillis()}.png")
        runCatching { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        load()
    }

    fun delete(signature: SavedSignature) {
        signature.file.delete()
        load()
    }

    private fun load() {
        signatures = directory.listFiles { file -> file.extension == "png" }.orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { file -> BitmapFactory.decodeFile(file.path)?.let { SavedSignature(file, it) } }
    }
}

enum class Appearance(val title: String) { System("System"), Light("Light"), Dark("Dark") }

/** The few preferences the app has. Changes apply immediately. */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var appearance by mutableStateOf(Appearance.entries.getOrElse(preferences.getInt("appearance", 0)) { Appearance.System })
        private set
    var sort by mutableStateOf(LibrarySort.entries.getOrElse(preferences.getInt("sort", 0)) { LibrarySort.Recent })
        private set

    fun update(value: Appearance) {
        appearance = value
        preferences.edit().putInt("appearance", value.ordinal).apply()
    }

    fun update(value: LibrarySort) {
        sort = value
        preferences.edit().putInt("sort", value.ordinal).apply()
    }
}

object AppInfo {
    const val NAME = "PDFoundry"
    const val HELP = "https://github.com/Fahed-Alhammadi/PDFoundry#readme"
    const val FEEDBACK = "https://github.com/Fahed-Alhammadi/PDFoundry/issues"
    const val WEBSITE = "https://fahed-alhammadi.github.io/PDFoundry/"
    const val PRIVACY = "https://fahed-alhammadi.github.io/PDFoundry/privacy.html"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=io.pdfoundry.app"

    /** The website as shown to the user: host and path, without the scheme or a trailing slash. */
    val websiteDisplay: String get() = WEBSITE.removePrefix("https://").trimEnd('/')
}
