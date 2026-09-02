package com.payandplan.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object FileStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun mimeOf(context: Context, uri: Uri, fallbackName: String): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        val ext = fallbackName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Copies the picked document into private storage so it survives permission loss. */
    fun copyIn(context: Context, uri: Uri): Triple<File, String, String>? {
        val name = displayName(context, uri)
        val mime = mimeOf(context, uri, name)
        val ext = name.substringAfterLast('.', "").ifBlank {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        }
        val target = File(dir(context), "${UUID.randomUUID()}.$ext")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Triple(target, name, mime)
        }.getOrNull()
    }

    fun newCameraFile(context: Context): File =
        File(dir(context), "receipt_${System.currentTimeMillis()}.jpg")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
