package io.pdfoundry.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** When a file outside the app was last changed and how big it is: tells whether the app's copy is current. */
data class Stamp(val modified: Long, val size: Long)

/**
 * Files that live outside the app (device storage, an SD card, a cloud app), reached through the
 * links the system file picker hands out. Tests replace the parts that need a real picker.
 */
open class OutsideFiles(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Asks to keep reading and writing this file after the app restarts. False for a one-time
     * hand-over (share sheet, mail attachment) and for a file its app does not let others change.
     */
    open fun keep(uri: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(uri, access)
        } catch (e: SecurityException) {
            return false
        }
        // Taking more than was offered is ignored without an error, so check what is really held.
        if (uri !in kept()) return false
        if (!writable(uri)) {
            release(uri)
            return false
        }
        return true
    }

    open fun release(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, access) }
    }

    /** Every file the app may still read and write. Android drops a file from this when it is deleted. */
    open fun kept(): Set<Uri> = resolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }.map { it.uri }.toSet()

    /** Null when the file can't be reached right now (moved, deleted, or its app is offline). */
    open fun stamp(uri: Uri): Stamp? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else Stamp(if (cursor.isNull(0)) 0 else cursor.getLong(0), if (cursor.isNull(1)) -1 else cursor.getLong(1))
        }
    }.getOrNull()

    private fun writable(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
        }
    }.getOrNull() ?: false

    /** Copies the file into [destination]. The copy replaces the old one only when all of it arrived. */
    open fun read(uri: Uri, destination: File): Boolean {
        val partial = File(destination.parentFile, destination.name + ".part")
        return try {
            val input = resolver.openInputStream(uri) ?: return false
            input.use { stream -> partial.outputStream().use { stream.copyTo(it) } }
            partial.renameTo(destination)
        } catch (e: Exception) {
            false
        } finally {
            partial.delete()
        }
    }

    /** Replaces the file's contents with [source]. */
    open fun write(source: File, uri: Uri) {
        try {
            // "wt" empties the file first; a few apps only accept the "rwt" spelling.
            val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "rwt")
                ?: throw IOException()
            output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        } catch (e: Exception) {
            throw IOException("This file couldn't be saved where it is. Use More > Save a copy in PDFoundry to keep working on a copy.", e)
        }
    }

    open fun name(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Where the file lives, in words: "On this device › Documents › Contracts", "Drive". */
    open fun location(uri: Uri): String {
        val document = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
        val app = uri.authority?.let { authority ->
            runCatching { context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString() }.getOrNull()
        }
        return describe(uri.authority, document, app)
    }

    companion object {
        fun describe(authority: String?, document: String, app: String?): String = when (authority) {
            "com.android.externalstorage.documents" -> {
                val volume = document.substringBefore(':')
                val folders = document.substringAfter(':', "").split('/').dropLast(1).filter { it.isNotEmpty() }
                (listOf(if (volume == "primary") "On this device" else "SD card") + folders).joinToString(" › ")
            }
            "com.android.providers.downloads.documents" -> "On this device › Download"
            "com.android.providers.media.documents" -> "On this device"
            else -> app?.takeIf { it.isNotBlank() } ?: "Another app"
        }
    }
}
