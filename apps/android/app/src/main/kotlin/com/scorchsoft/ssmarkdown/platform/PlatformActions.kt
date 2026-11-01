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

    /** Shares [text] as a file through the FileProvider: too large for the clipboard's Binder limit. */
    suspend fun shareAsFile(
        name: String,
        text: CharSequence,
    ) {
        val file =
            withContext(io) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                File(dir, name).also { f -> f.bufferedWriter().use { it.append(text) } }
            }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
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

    /** Copies a picked [uri] into the cache (an import zip), or null on failure. */
    suspend fun copyToCache(uri: Uri): File? =
        withContext(io) {
            try {
                val target = File(context.cacheDir, "import-${System.nanoTime()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                    ?: return@withContext null
                target
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    /** Opens scorchsoft.com in the browser; the app itself has no network access. */
    fun openSite() = start(Intent(Intent.ACTION_VIEW, SITE.toUri()))

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

    private companion object {
        const val SITE = "https://www.scorchsoft.com"
    }
}
