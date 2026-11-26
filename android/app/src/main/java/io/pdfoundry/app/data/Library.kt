package io.pdfoundry.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** One PDF file in the library. */
data class PdfItem(val file: File, val pages: Int, val size: Long, val modified: Long, val locked: Boolean) {
    val name: String get() = file.nameWithoutExtension
    val detail: String
        get() {
            val count = if (locked) "Locked" else if (pages == 1) "1 page" else "$pages pages"
            return "$count · ${formatSize(size)}"
        }
}

enum class LibrarySort(val title: String) { Recent("Date modified"), Name("Name"), Size("Size") }

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes bytes"
}

/** The library: every PDF in the app's private `documents` folder. */
class DocumentStore(private val context: Context) {
    val directory = File(context.filesDir, "documents").apply { mkdirs() }

    var items by mutableStateOf<List<PdfItem>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun start() {
        withContext(Dispatchers.IO) {
            val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            if (!preferences.getBoolean("seeded", false)) {
                preferences.edit().putBoolean("seeded", true).apply()
                runCatching { PdfEngine.writeWelcomeGuide(uniqueFile("Welcome to PDFoundry")) }
            }
        }
        refresh()
    }

    suspend fun refresh() {
        val scanned = withContext(Dispatchers.IO) {
            directory.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }.orEmpty().map { file ->
                var pages = 0
                var locked = false
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { pages = it.pageCount }
                    }
                } catch (e: SecurityException) {
                    locked = true
                } catch (e: Exception) {
                    pages = 0
                }
                PdfItem(file, pages, file.length(), file.lastModified(), locked)
            }
        }
        items = scanned
        loaded = true
    }

    fun sorted(sort: LibrarySort, query: String): List<PdfItem> {
        val filtered = if (query.isBlank()) items else items.filter { it.name.contains(query.trim(), ignoreCase = true) }
        return when (sort) {
            LibrarySort.Recent -> filtered.sortedByDescending { it.modified }
            LibrarySort.Name -> filtered.sortedBy { it.name.lowercase() }
            LibrarySort.Size -> filtered.sortedByDescending { it.size }
        }
    }

    fun file(name: String) = File(directory, "$name.pdf")

    fun uniqueFile(rawName: String): File {
        val base = sanitized(rawName).ifEmpty { "Untitled" }
        var candidate = File(directory, "$base.pdf")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base $counter.pdf")
            counter++
        }
        return candidate
    }

    /** Copies PDFs picked or received from other apps into the library. Returns the new files. */
    suspend fun import(uris: List<Uri>, nameOverride: String? = null): List<File> {
        val imported = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val destination = uniqueFile(nameOverride ?: displayName(uri) ?: "Imported")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { input.copyTo(it) }
                    } ?: return@mapNotNull null
                    // Keep only real PDFs: the header must be present.
                    val header = destination.inputStream().use { stream -> ByteArray(5).also { stream.read(it) } }
                    if (String(header) != "%PDF-") {
                        destination.delete()
                        null
                    } else {
                        destination
                    }
                } catch (e: Exception) {
                    destination.delete()
                    null
                }
            }
        }
        refresh()
        return imported
    }

    suspend fun createFromImages(uris: List<Uri>, name: String): File? {
        val file = withContext(Dispatchers.IO) {
            val bitmaps = uris.mapNotNull { decode(it) }
            if (bitmaps.isEmpty()) return@withContext null
            uniqueFile(name).also { PdfEngine.imagesToPdf(bitmaps, it) }
        }
        refresh()
        return file
    }

    suspend fun merge(sources: List<PdfItem>, name: String, deleteOriginals: Boolean): File {
        val destination = withContext(Dispatchers.IO) {
            uniqueFile(name).also { target ->
                PdfEngine.merge(sources.map { it.file }, target)
                if (deleteOriginals) sources.forEach { it.file.delete() }
            }
        }
        refresh()
        return destination
    }

    suspend fun extract(source: File, pages: List<Int>): File {
        val destination = withContext(Dispatchers.IO) {
            uniqueFile("${source.nameWithoutExtension} extract").also { PdfEngine.extract(source, pages, it) }
        }
        refresh()
        return destination
    }

    suspend fun rename(item: PdfItem, newName: String) {
        val clean = sanitized(newName)
        if (clean.isEmpty() || clean == item.name) return
        withContext(Dispatchers.IO) { item.file.renameTo(uniqueFile(clean)) }
        refresh()
    }

    suspend fun duplicate(item: PdfItem) {
        withContext(Dispatchers.IO) { item.file.copyTo(uniqueFile("${item.name} copy")) }
        refresh()
    }

    suspend fun delete(targets: List<PdfItem>) {
        withContext(Dispatchers.IO) { targets.forEach { it.file.delete() } }
        refresh()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun decode(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Photos are far larger than a page needs; 2400 px on the long side prints cleanly.
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2400) {
                    val factor = 2400f / longest
                    decoder.setTargetSize((info.size.width * factor).toInt(), (info.size.height * factor).toInt())
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    companion object {
        /** A safe file name: no path separators, no leading dots, no ".pdf" suffix. */
        fun sanitized(name: String): String {
            var clean = name.replace('/', '-').replace('\\', '-').replace(':', '-').trim()
            if (clean.endsWith(".pdf", ignoreCase = true)) clean = clean.dropLast(4)
            return clean.trimStart('.').trim().take(120)
        }
    }
}

/** An open PDF for display. PdfRenderer allows one page open at a time, so rendering is serialised. */
class PdfSource(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Page sizes in points, as displayed (rotation applied). */
    val sizes: List<Size> = (0 until pageCount).map { index -> renderer.openPage(index).use { Size(it.width, it.height) } }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || index !in 0 until pageCount || widthPx <= 0) return@withLock null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override fun close() {
        // Waits for a render in progress, then releases the file.
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    renderer.close()
                    descriptor.close()
                }
            }
        }
    }
}

/** First-page thumbnails for the library grid, cached by file and modification time. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(item: PdfItem, widthPx: Int): Bitmap? {
        val key = "${item.file.path}|${item.modified}|$widthPx"
        cache.get(key)?.let { return it }
        if (item.locked) return null
        return withContext(Dispatchers.IO) {
            runCatching { PdfSource(item.file).use { it.render(0, widthPx) } }.getOrNull()
        }?.also { cache.put(key, it) }
    }
}
