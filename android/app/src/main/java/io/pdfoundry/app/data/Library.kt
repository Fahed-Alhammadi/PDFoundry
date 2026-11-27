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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One PDF in the list. [location] is set for a file opened where it lives ("On this device › Documents"):
 * [file] is then the app's working copy of it, and every change is written back to the real file.
 */
data class PdfItem(val file: File, val pages: Int, val size: Long, val modified: Long, val locked: Boolean, val location: String? = null) {
    val name: String get() = file.nameWithoutExtension
    val inPlace: Boolean get() = location != null
    val detail: String
        get() {
            val count = if (locked) "Locked" else if (pages == 1) "1 page" else "$pages pages"
            return "$count · ${formatSize(size)}"
        }
}

/** Why a picked file was not opened. */
enum class Skipped { NotPdf, Unreadable }

enum class LibrarySort(val title: String) { Recent("Date modified"), Name("Name"), Size("Size") }

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes bytes"
}

/** True when two files hold exactly the same bytes. */
fun sameContents(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    // Fills the buffer unless the file ends first (a plain read may return less than asked for).
    fun fill(stream: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
    a.inputStream().use { first ->
        b.inputStream().use { second ->
            val x = ByteArray(64 * 1024)
            val y = ByteArray(64 * 1024)
            while (true) {
                val read = fill(first, x)
                if (read != fill(second, y)) return false
                if (read == 0) return true
                for (index in 0 until read) if (x[index] != y[index]) return false
            }
        }
    }
}

/** A file opened where it lives: the link to it, and what it looked like when the working copy was made. */
private class Link(val id: String, val uri: Uri, val name: String, val location: String, var stamp: Stamp) {
    /** A save to the real file was started and has not been seen to finish: the real file may be incomplete. */
    var pushing = false
}

/**
 * The document list: every PDF in the app's private `documents` folder, plus the files opened
 * where they live (see [open]).
 */
class DocumentStore(private val context: Context, private val outside: OutsideFiles = OutsideFiles(context), root: File = context.filesDir) {
    val directory = File(root, "documents").apply { mkdirs() }

    // Files opened in place. Each has a working copy at opened/<id>/<name>.pdf: the PDF code needs a
    // real file, and the copy keeps the list working when the file's own app is slow or offline.
    private val openedDirectory = File(root, "opened").apply { mkdirs() }
    private val linksFile = File(root, "opened.json")
    private val gate = Mutex()
    private val listing = Mutex()
    private var links = listOf<Link>()
    private var linksLoaded = false

    var items by mutableStateOf<List<PdfItem>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun start() {
        withContext(Dispatchers.IO) {
            clearLeftovers()
            val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            if (!preferences.getBoolean("seeded", false)) {
                preferences.edit().putBoolean("seeded", true).apply()
                runCatching { PdfEngine.writeWelcomeGuide(uniqueFile("Welcome to PDFoundry")) }
            }
        }
        refresh()
        // Files opened in place may have been changed by another app since the last run.
        val changed = withContext(Dispatchers.IO) {
            gate.withLock { links.map { link -> workingFile(link).lastModified().let { before -> update(link); workingFile(link).lastModified() != before } } }
        }
        if (changed.any { it }) refresh()
    }

    suspend fun refresh() = listing.withLock {
        val scanned = withContext(Dispatchers.IO) {
            val opened = gate.withLock {
                loadLinks()
                // Only links that are certainly dead are removed (the file was deleted, or access was
                // taken away), one by one, so a file being opened at this moment is never lost.
                val kept = outside.kept()
                links.filter { it.uri !in kept || !workingFile(it).exists() }.forEach { drop(it) }
                links.toList()
            }
            directory.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }.orEmpty().map { describe(it, null) } +
                opened.map { describe(workingFile(it), it.location) }
        }
        items = scanned
        loaded = true
    }

    private fun describe(file: File, location: String?): PdfItem {
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
        return PdfItem(file, pages, file.length(), file.lastModified(), locked, location)
    }

    // Files opened in place ------------------------------------------------------------------

    /**
     * Opens PDFs where they live, without copying them into the library: each is listed with its
     * location and every change is written back to it. A file that can't be kept that way (a
     * one-time hand-over, or one its app doesn't let others change) is copied into the library
     * instead, as [import] does. Returns where each one can be read.
     */
    suspend fun open(uris: List<Uri>, skipped: MutableList<Skipped>? = null): List<File> = open(uris, skipped, copyHandovers = true)

    /**
     * Opens one PDF where it lives for a tool that changes the real file (Password-protect). A file that
     * can't be kept and written where it is (a one-time hand-over, or one its app doesn't let others
     * change) is refused rather than copied: changing a copy would leave the real file as it was.
     */
    suspend fun openForChange(uri: Uri): File {
        val skipped = mutableListOf<Skipped>()
        return open(listOf(uri), skipped, copyHandovers = false).firstOrNull()
            ?: throw PdfException(if (skipped.isEmpty()) "PDFoundry can't change this file where it is." else if (skipped.first() == Skipped.NotPdf) "That file isn't a PDF." else "That file couldn't be read.")
    }

    private suspend fun open(uris: List<Uri>, skipped: MutableList<Skipped>?, copyHandovers: Boolean): List<File> {
        val handovers = mutableListOf<Uri>()
        val opened = withContext(Dispatchers.IO) {
            gate.withLock {
                loadLinks()
                uris.mapNotNull { uri ->
                    links.firstOrNull { it.uri == uri }?.let { known ->
                        update(known)
                        return@mapNotNull workingFile(known)
                    }
                    if (!outside.keep(uri)) {
                        handovers += uri
                        return@mapNotNull null
                    }
                    val name = sanitized(outside.name(uri) ?: "").ifEmpty { "Untitled" }
                    val link = Link(java.util.UUID.randomUUID().toString(), uri, name, outside.location(uri), outside.stamp(uri) ?: Stamp(0, -1))
                    val file = workingFile(link)
                    file.parentFile?.mkdirs()
                    val arrived = outside.read(uri, file)
                    if (arrived && isPdf(file)) {
                        links = links + link
                        saveLinks()
                        file
                    } else {
                        skipped?.add(if (arrived) Skipped.NotPdf else Skipped.Unreadable)
                        file.parentFile?.deleteRecursively()
                        outside.release(uri)
                        null
                    }
                }
            }
        }
        val copied = if (handovers.isEmpty() || !copyHandovers) emptyList() else import(handovers, skipped = skipped)
        refresh()
        return (opened + copied).distinct()
    }

    /**
     * Makes the working copy of a file opened in place match the real file again. False when the
     * real file can't be reached right now; the copy then shows it as last seen.
     */
    suspend fun sync(file: File): Boolean = withContext(Dispatchers.IO) {
        gate.withLock { link(file)?.let { update(it) } ?: true }
    }

    /** Writes a changed working copy back to the real file. Library documents need nothing. */
    suspend fun push(file: File) = withContext(Dispatchers.IO) {
        gate.withLock {
            val link = link(file) ?: return@withLock
            // Noted first, so a save that is cut short (the app is closed, storage runs out) is finished
            // later from the working copy instead of the half-written file being taken as the truth.
            link.pushing = true
            saveLinks()
            outside.write(file, link.uri)
            link.pushing = false
            outside.stamp(link.uri)?.let { link.stamp = it }
            saveLinks()
        }
    }

    /** Copies a file opened in place into the library, where it can be renamed and edited freely. */
    suspend fun saveCopy(file: File): File {
        val copy = withContext(Dispatchers.IO) { file.copyTo(uniqueFile(file.nameWithoutExtension)) }
        refresh()
        return copy
    }

    fun location(file: File): String? = items.firstOrNull { it.file == file }?.location

    private fun update(link: Link): Boolean {
        var stamp = outside.stamp(link.uri) ?: return false
        val file = workingFile(link)
        var unchanged = stamp == link.stamp && stamp.modified != 0L
        if (link.pushing && file.exists()) {
            // An earlier save did not finish, so the real file may be incomplete: save the working copy again.
            try {
                outside.write(file, link.uri)
                link.pushing = false
                outside.stamp(link.uri)?.let { link.stamp = it }
                saveLinks()
                return true
            } catch (e: Exception) {
                // Still can't be saved. If the real file has changed it may be the half-written one: keep the
                // working copy. If it is untouched, the save never reached it, and it is read again below.
                if (!unchanged) return false
                link.pushing = false
                unchanged = false
                stamp = outside.stamp(link.uri) ?: return false
            }
        }
        // A file whose app reports no date is copied again every time, to be safe.
        if (unchanged && file.exists()) return true
        // The working copy is replaced only by a whole PDF.
        val incoming = File(file.parentFile, file.name + ".incoming")
        try {
            if (!outside.read(link.uri, incoming) || !isPdf(incoming) || !incoming.renameTo(file)) return false
        } finally {
            incoming.delete()
        }
        link.stamp = stamp
        saveLinks()
        return true
    }

    private fun workingFile(link: Link) = File(openedDirectory, "${link.id}/${link.name}.pdf")

    private fun link(file: File): Link? {
        loadLinks()
        return links.firstOrNull { workingFile(it) == file }
    }

    /** Forgets a file opened in place. The real file is not touched. */
    private fun drop(link: Link) {
        outside.release(link.uri)
        File(openedDirectory, link.id).deleteRecursively()
        links = links - link
        saveLinks()
    }

    private fun loadLinks() {
        if (linksLoaded) return
        linksLoaded = true
        links = runCatching {
            val saved = JSONArray(linksFile.readText())
            (0 until saved.length()).map { index ->
                val entry = saved.getJSONObject(index)
                Link(entry.getString("id"), Uri.parse(entry.getString("uri")), entry.getString("name"), entry.getString("location"), Stamp(entry.getLong("modified"), entry.getLong("size"))).apply { pushing = entry.optBoolean("pushing", false) }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveLinks() {
        val saved = JSONArray()
        links.forEach { link ->
            saved.put(JSONObject().put("id", link.id).put("uri", link.uri.toString()).put("name", link.name).put("location", link.location).put("modified", link.stamp.modified).put("size", link.stamp.size).put("pushing", link.pushing))
        }
        val draft = File(linksFile.parentFile, linksFile.name + ".new")
        draft.writeText(saved.toString())
        draft.renameTo(linksFile)
    }

    private fun isPdf(file: File): Boolean {
        val header = file.inputStream().use { stream -> ByteArray(5).also { stream.read(it) } }
        return String(header) == "%PDF-"
    }

    // ----------------------------------------------------------------------------------------

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

    /**
     * Brings PDFs picked or received from other apps into the library and returns where each one is.
     * A PDF whose contents are already in the library is not added again: its existing document is returned.
     * (Other apps hand files over as one-time links, so contents are the only thing to compare.)
     */
    suspend fun import(uris: List<Uri>, nameOverride: String? = null, skipped: MutableList<Skipped>? = null): List<File> {
        val imported = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val destination = uniqueFile(nameOverride ?: displayName(uri) ?: "Imported")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { input.copyTo(it) }
                    } ?: run {
                        skipped?.add(Skipped.Unreadable)
                        return@mapNotNull null
                    }
                    // Keep only real PDFs: the header must be present.
                    if (!isPdf(destination)) {
                        destination.delete()
                        skipped?.add(Skipped.NotPdf)
                        null
                    } else {
                        val twin = directory.listFiles()?.firstOrNull { it != destination && it.extension.equals("pdf", ignoreCase = true) && sameContents(it, destination) }
                        if (twin != null) {
                            destination.delete()
                            twin
                        } else {
                            destination
                        }
                    }
                } catch (e: Exception) {
                    destination.delete()
                    skipped?.add(Skipped.Unreadable)
                    null
                }
            }
        }
        refresh()
        return imported.distinct()
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
                // Files opened in place belong to another location: they are never deleted from here.
                if (deleteOriginals) sources.filter { !it.inPlace }.forEach { it.file.delete() }
            }
        }
        refresh()
        return destination
    }

    /** From the reader's page organizer. With [password] (a protected document), the new file keeps it. */
    suspend fun extract(source: File, pages: List<Int>, password: String? = null): File {
        val destination = withContext(Dispatchers.IO) {
            uniqueFile("${source.nameWithoutExtension} extract").also { PdfEngine.extract(source, pages, it, password) }
        }
        refresh()
        return destination
    }

    /**
     * Before a tool reads a file opened in place: brings its working copy up to date with the real file
     * if another app changed it, so the pages shown are the pages used. Only ever reads the real file,
     * and never while a save to it is unfinished (the real file may be half-written: the working copy
     * is the truth then, and finishing the save is left to the reader). The download happens without
     * holding the store's lock. False when the real file can't be reached; the working copy is then
     * used as last seen. Library documents and temporary copies need nothing.
     */
    suspend fun syncForReading(file: File): Boolean = withContext(Dispatchers.IO) {
        val (link, seen) = gate.withLock { link(file)?.let { it to it.stamp } } ?: return@withContext true
        if (link.pushing) return@withContext true
        val now = outside.stamp(link.uri) ?: return@withContext false
        if (now == seen && now.modified != 0L && file.exists()) return@withContext true
        val incoming = File(file.parentFile, "${file.name}.reading-${System.nanoTime()}")
        try {
            if (!outside.read(link.uri, incoming) || !isPdf(incoming)) return@withContext false
            gate.withLock {
                // Replaced only if nothing happened meanwhile (a save started, or the link was dropped).
                if (link.pushing || link !in links || link.stamp != seen) return@withLock true
                if (!incoming.renameTo(file)) return@withLock false
                link.stamp = now
                saveLinks()
                true
            }
        } finally {
            incoming.delete()
        }
    }

    /**
     * Extract Pages from the Tools tab: copies the chosen pages of [source] into a new library document,
     * "<name> (pages)". [source] is the file shown in the page picker: a library document, the working
     * copy of a file opened in place (brought up to date by [syncForReading] first), or a temporary copy.
     * It is only read. A protected source is opened with [password] and the new document keeps it.
     */
    suspend fun extractPages(source: File, pages: List<Int>, password: String? = null): File {
        val destination = withContext(Dispatchers.IO) {
            uniqueFile("${source.nameWithoutExtension} (pages)").also { PdfEngine.extract(source, pages, it, password) }
        }
        refresh()
        return destination
    }

    /**
     * Split PDF from the Tools tab: each of [parts] (0-based pages) becomes a new library document,
     * "<name> (part 1)", "(part 2)"… [source] is read the same way as for [extractPages] and never
     * written. A protected source is opened with [password] and every part keeps it.
     */
    suspend fun split(source: File, parts: List<List<Int>>, password: String?): List<File> {
        if (parts.isEmpty()) throw PdfException("There are no pages to save.")
        val made = withContext(Dispatchers.IO) {
            val destinations = mutableListOf<File>()
            try {
                parts.indices.forEach { index ->
                    // Reserved one by one, so two parts never get the same name.
                    destinations += uniqueFile("${source.nameWithoutExtension} (part ${index + 1})").also { it.createNewFile() }
                }
                PdfEngine.split(source, parts, destinations, password)
            } catch (e: Exception) {
                destinations.forEach { it.delete() }
                throw e
            }
            destinations.toList()
        }
        refresh()
        return made
    }

    /**
     * Compress from the Tools tab: a smaller copy of [source], "<name> (compressed)", in the library.
     * Null when it would not be at least 5% smaller (nothing is kept then). [source] is only read; a
     * protected one is opened with [password] and the copy keeps it.
     */
    suspend fun compress(source: File, level: CompressLevel, password: String?, baseName: String = source.nameWithoutExtension): File? {
        val made = withContext(Dispatchers.IO) {
            val destination = uniqueFile("$baseName (compressed)")
            if (Compress.run(source, level, password, destination)) destination else null
        }
        if (made != null) refresh()
        return made
    }

    /**
     * Password-protect from the Tools tab: replaces [file] (a library document, or the working copy of a
     * file opened in place, whose real file is then written too) with its protected version. If saving
     * fails, the file keeps what it had. Refuses a file that is already protected.
     */
    suspend fun protect(file: File, password: String) {
        withContext(Dispatchers.IO) {
            // A working copy that can't be brought up to date must not be written over newer content.
            if (!syncForReading(file)) throw PdfException("This file can't be reached right now, so it can't be protected. Try again when it can be opened.")
            if (PdfEngine.isProtected(file)) throw PdfException("This document is already protected with a password.")
            val before = File(context.cacheDir, "protect-${System.nanoTime()}.pdf")
            file.copyTo(before, overwrite = true)
            try {
                PdfEngine.protect(file, password, file)
                push(file)
            } catch (e: Exception) {
                before.copyTo(file, overwrite = true)
                throw e
            } finally {
                before.delete()
            }
        }
        refresh()
    }

    /**
     * A private, temporary copy of a picked file, for a tool that uses it for one job only (Extract
     * Pages, Merge PDFs): the file is neither opened in place nor added to the library. Null when it
     * isn't a readable PDF. Remove it with [discardTemporary]; it is also removed if the caller is
     * cancelled while it is being copied, and any left over are cleared at [start].
     */
    suspend fun temporaryCopy(uri: Uri): File? {
        val name = sanitized(outside.name(uri) ?: "").ifEmpty { "Untitled" }
        val file = File(pickedDirectory, "${java.util.UUID.randomUUID()}/$name.pdf")
        try {
            val ok = withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                runCatching { outside.read(uri, file) && isPdf(file) }.getOrDefault(false)
            }
            if (ok) return file
            discardTemporary(file)
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            discardTemporary(file)
            throw e
        }
    }

    fun discardTemporary(file: File) {
        val folder = file.parentFile ?: return
        if (folder.parentFile == pickedDirectory) folder.deleteRecursively()
    }

    /** A list entry for a file that is not in the list (a temporary copy), for the merge sheet. */
    suspend fun itemFor(file: File): PdfItem = withContext(Dispatchers.IO) { describe(file, null) }

    private val pickedDirectory get() = File(context.cacheDir, "picked")

    /** Temporary files a tool was using when the app was closed. */
    internal fun clearLeftovers() {
        pickedDirectory.deleteRecursively()
        // Unlocked copies of protected files, and the reader's undo copies (plain for a protected
        // document), left by an earlier run that ended without closing them. Anything made since this
        // process started belongs to a document open now and is kept.
        val started = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        listOf(unlockedDirectory(context), File(context.cacheDir, "undo")).forEach { folder ->
            folder.listFiles()?.filter { it.lastModified() < started }?.forEach { it.deleteRecursively() }
        }
        // Pictures made by Export as Images for sharing, and sheet previews, from an earlier run.
        listOf(File(context.cacheDir, "exports"), File(context.cacheDir, "preview")).forEach { it.deleteRecursively() }
        // Downloads made by an earlier version's Extract Pages.
        context.cacheDir.listFiles { file -> file.name.startsWith("extract-") && file.name.endsWith(".pdf") }?.forEach { it.delete() }
    }

    suspend fun rename(item: PdfItem, newName: String) {
        val clean = sanitized(newName)
        if (clean.isEmpty() || clean == item.name || item.inPlace) return
        withContext(Dispatchers.IO) { item.file.renameTo(uniqueFile(clean)) }
        refresh()
    }

    suspend fun duplicate(item: PdfItem) {
        withContext(Dispatchers.IO) { item.file.copyTo(uniqueFile("${item.name} copy")) }
        refresh()
    }

    /** Deletes library documents. A file opened in place is only removed from the list: the real file stays. */
    suspend fun delete(targets: List<PdfItem>) {
        withContext(Dispatchers.IO) {
            gate.withLock { targets.forEach { target -> link(target.file)?.let { drop(it) } ?: target.file.delete() } }
        }
        refresh()
    }

    private fun displayName(uri: Uri): String? = outside.name(uri)

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
        /**
         * Where unprotected working copies of password-protected files live while they are open: the
         * app's private cache, never the library or the real file's location. Each is deleted on close.
         */
        fun unlockedDirectory(context: Context) = File(context.cacheDir, "unlocked")

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
    // A file that can't be opened (password-protected, damaged) must not leave its descriptor open.
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }
    private val mutex = Mutex()
    private var closed = false
    /** PdfRenderer draws no annotations: marks are drawn on top from their appearance streams. */
    private val marks = AnnotationLayer(file)

    val pageCount: Int = renderer.pageCount

    /** Page sizes in points, as displayed (rotation applied). */
    val sizes: List<Size> = try {
        (0 until pageCount).map { index -> renderer.openPage(index).use { Size(it.width, it.height) } }
    } catch (e: Throwable) {
        renderer.close()
        descriptor.close()
        throw e
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || index !in 0 until pageCount || widthPx <= 0) return@withLock null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    marks.draw(index, bitmap)
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
                    marks.close()
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
