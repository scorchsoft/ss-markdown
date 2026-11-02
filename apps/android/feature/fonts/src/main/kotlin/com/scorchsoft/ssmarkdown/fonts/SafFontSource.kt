package com.scorchsoft.ssmarkdown.fonts

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.scorchsoft.ssmarkdown.fonts.importing.ImportResult
import java.io.FileNotFoundException

/**
 * Imports a font the person picked with the system file picker
 * (`ACTION_OPEN_DOCUMENT`). The picker's MIME filter is a hint only; the
 * importer checks the bytes.
 *
 * @param resolver the app's content resolver.
 * @param repository where the font goes.
 */
class SafFontSource(
    private val resolver: ContentResolver,
    private val repository: FontRepository,
) {
    /** Imports [uri]. Never throws for a bad or vanished document. */
    suspend fun import(uri: Uri): ImportResult {
        val name = displayName(uri)
        return repository.import(name) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException("No stream for $uri")
        }
    }

    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "font"
        val cursor: Cursor =
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            } catch (_: SecurityException) {
                null
            } ?: return fallback
        return cursor.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) it.getString(column) ?: fallback else fallback
        }
    }

    /** MIME types to offer in the picker; `application/octet-stream` because many providers label fonts so. */
    companion object {
        /** Pass to `ActivityResultContracts.OpenDocument`. */
        val MIME_TYPES: Array<String> =
            arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/octet-stream")
    }
}
