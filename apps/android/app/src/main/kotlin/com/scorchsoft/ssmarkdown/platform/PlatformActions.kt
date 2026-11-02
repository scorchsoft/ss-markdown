package com.scorchsoft.ssmarkdown.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Html
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.scorchsoft.ssmarkdown.files.saf.SafeFileName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The Android side of the editor's one-shot effects: clipboard, share
 * sheets, haptics and writing through a picked URI. Everything that
 * touches a stream suspends onto [io].
 */
class PlatformActions(
    private val context: Context,
    private val io: CoroutineDispatcher,
) {
    /** Puts [text] on the clipboard as plain Markdown. */
    fun copy(text: CharSequence) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(com.scorchsoft.ssmarkdown.R.string.app_name), text),
        )
    }

    /**
     * The system share sheet for [text]. With [richText] it also carries
     * HTML (`EXTRA_HTML_TEXT`); until the preview renders Markdown to HTML
     * that is the escaped source in a `<pre>`, which keeps code exact.
     */
    fun shareText(
        text: CharSequence,
        richText: Boolean,
    ) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text.toString())
                if (richText) putExtra(Intent.EXTRA_HTML_TEXT, "<pre>${Html.escapeHtml(text)}</pre>")
            }
        start(Intent.createChooser(intent, null))
    }

    /**
     * Shares [text] as a file through the FileProvider: too large for the
     * clipboard's Binder limit. [name] is usually a provider's
     * `DISPLAY_NAME`, which a hostile app controls, so it is reduced to one
     * safe file name inside `cache/shared/` before anything is written
     * (SEC-1). Does nothing if the file cannot be written or shared.
     */
    suspend fun shareAsFile(
        name: String,
        text: CharSequence,
    ) {
        val uri =
            withContext(io) {
                try {
                    val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
                    val file = SafeFileName.inside(dir, name)
                    file.bufferedWriter().use { it.append(text) }
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: return
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        start(Intent.createChooser(intent, null))
    }

    /** Writes [text] to a picked [uri] (Export section…). False if the provider refused. */
    suspend fun writeText(
        uri: Uri,
        text: String,
    ): Boolean =
        withContext(io) {
            try {
                context.contentResolver
                    .openOutputStream(
                        uri,
                        "wt",
                    )?.use { it.write(text.toByteArray(Charsets.UTF_8)) } !=
                    null
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    /** Copies [file] to a picked [uri] (a history export). False if the provider refused. */
    suspend fun copyFile(
        file: File,
        uri: Uri,
    ): Boolean =
        withContext(io) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } !=
                    null
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    /**
     * Copies a picked [uri] into the cache (an import zip), or null on
     * failure or when it is larger than [MAX_IMPORT_BYTES] (SEC-8). Earlier
     * copies are deleted first, so imports never pile up in the cache.
     */
    suspend fun copyToCache(uri: Uri): File? =
        withContext(io) {
            context.cacheDir.listFiles { f -> f.name.startsWith(IMPORT_PREFIX) }?.forEach(File::delete)
            val target = File(context.cacheDir, "$IMPORT_PREFIX${System.nanoTime()}.zip")
            val copied =
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { BoundedRead.copy(input, it, MAX_IMPORT_BYTES) }
                    } == true
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            if (!copied) target.delete()
            target.takeIf { copied }
        }

    /** Opens scorchsoft.com in the browser; the app itself has no network access. */
    fun openSite() = start(Intent(Intent.ACTION_VIEW, SITE.toUri()))

    /** Opens the app's source code on GitHub in the browser. */
    fun openSource() = start(Intent(Intent.ACTION_VIEW, SOURCE.toUri()))

    /** A light tick (mode switch, save success) or the error pattern. */
    fun haptic(
        view: View,
        error: Boolean,
    ) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val constant =
            when {
                modern && error -> HapticFeedbackConstants.REJECT
                modern -> HapticFeedbackConstants.CONFIRM
                error -> HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
        view.performHapticFeedback(constant)
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            // Nothing can handle it on this device; the action simply does nothing.
        }
    }

    companion object {
        private const val SITE = "https://www.scorchsoft.com"
        private const val SOURCE = "https://github.com/scorchsoft/ss-markdown"
        private const val SHARED_DIR = "shared"
        private const val IMPORT_PREFIX = "import-"

        /** The largest history export accepted for import; real ones are a few megabytes (SEC-8). */
        const val MAX_IMPORT_BYTES: Long = 256L * 1024 * 1024
    }
}
