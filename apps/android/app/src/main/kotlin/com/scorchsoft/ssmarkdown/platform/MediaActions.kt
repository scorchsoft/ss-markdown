package com.scorchsoft.ssmarkdown.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.R
import com.scorchsoft.ssmarkdown.media.ExportFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Android half of exporting images and copying rich text: Downloads
 * through MediaStore (no storage permission on Android 10+), a ZIP through
 * a picked URI, the share sheet and the clipboard through the app's
 * FileProvider. Everything that touches a stream runs on [io] and reports
 * failure as `false`, never as an exception.
 */
class MediaActions(
    private val context: Context,
    private val io: CoroutineDispatcher,
) {
    /** Writes [files] into `Downloads/SS Markdown/<folder>/`. Returns how many were written. */
    suspend fun saveToDownloads(
        folder: String,
        files: List<ExportFile>,
    ): Int =
        withContext(io) {
            val resolver = context.contentResolver
            val path = "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER/${safe(folder)}"
            files.count { file ->
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.MIME_TYPE, file.mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, path)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@count false
                val written = write(uri) { it.write(file.bytes) }
                if (written) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } else {
                    resolver.delete(uri, null, null)
                }
                written
            }
        }

    /** Writes [files] as one ZIP to a picked [uri]. */
    suspend fun writeZip(
        uri: Uri,
        files: List<ExportFile>,
    ): Boolean =
        withContext(io) {
            write(uri) { out ->
                ZipOutputStream(out).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        zip.write(file.bytes)
                        zip.closeEntry()
                    }
                }
            }
        }

    /** The share sheet with [files], through the FileProvider. */
    suspend fun share(files: List<ExportFile>) {
        val uris = withContext(io) { files.mapNotNull(::cached) }
        if (uris.isEmpty()) return
        val intent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        intent.type = files.map { it.mime }.distinct().singleOrNull() ?: "image/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(Intent.createChooser(intent, null))
    }

    /** Puts [file] on the clipboard as an image, through the FileProvider. */
    suspend fun copyImage(file: ExportFile): Boolean {
        val uri = withContext(io) { cached(file) }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (uri == null || clipboard == null) return false
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, uri))
        return true
    }

    /** "Copy as rich text": [text] as plain Markdown with [html] beside it for apps that paste formatting. */
    fun copyHtml(
        text: CharSequence,
        html: String,
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newHtmlText(context.getString(R.string.app_name), text, html))
    }

    /** Opens a web, mail or phone link in another app. */
    fun openLink(url: String) = start(Intent(Intent.ACTION_VIEW, url.toUri()))

    private fun cached(file: ExportFile): Uri? =
        try {
            val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
            val target = File(dir, safe(file.name)).apply { writeBytes(file.bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private inline fun write(
        uri: Uri,
        body: (java.io.OutputStream) -> Unit,
    ): Boolean =
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use(body) != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            // Nothing on this phone handles it; the action does nothing rather than crash.
        }
    }

    private companion object {
        const val APP_FOLDER = "SS Markdown"
        const val SHARED_DIR = "shared"
        val UNSAFE = Regex("""[\\/:*?"<>|]""")

        fun safe(name: String): String = UNSAFE.replace(name, "-").ifBlank { "untitled" }
    }
}
