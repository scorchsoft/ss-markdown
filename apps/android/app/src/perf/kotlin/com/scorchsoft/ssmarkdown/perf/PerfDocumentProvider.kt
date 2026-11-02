package com.scorchsoft.ssmarkdown.perf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the benchmark corpus to the app through `content://`, the path a
 * file from Files or Drive takes, so the benchmarks measure the real open,
 * journal and save code. Benchmark build types only (see the manifest).
 *
 * `content://<app>.perf/perf-1mb.md` and its siblings in [SIZES] are
 * generated on first use from [PerfCorpus], deterministically, and kept
 * in app storage. The app may save over them (autosave does), so the
 * benchmark puts the pristine text back before each iteration with
 * `content call --uri content://<app>.perf --method reset --arg perf-1mb.md`.
 */
class PerfDocumentProvider : ContentProvider() {
    private val root: File by lazy { File(requireNotNull(context).noBackupFilesDir, "perf") }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = nameOf(uri)
        val file = working(name)
        val values =
            mapOf(
                OpenableColumns.DISPLAY_NAME to name,
                OpenableColumns.SIZE to file.length(),
                Document.COLUMN_MIME_TYPE to MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED to file.lastModified(),
                Document.COLUMN_FLAGS to Document.FLAG_SUPPORTS_WRITE,
            )
        val columns = projection?.filter(values::containsKey)?.toTypedArray() ?: values.keys.toTypedArray()
        return MatrixCursor(columns, 1).apply { addRow(columns.map(values::get)) }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(working(nameOf(uri)), ParcelFileDescriptor.parseMode(mode))

    /**
     * `reset`: rewrites the working copy of the file named by [arg] from the
     * pristine one, and returns its size as `bytes`. `call` is not covered
     * by the provider's permission, so it checks DUMP (the shell) itself.
     */
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val ctx = requireNotNull(context)
        val trusted =
            Binder.getCallingUid() == Process.myUid() ||
                ctx.checkCallingPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        if (!trusted) throw SecurityException("reset needs DUMP")
        if (method != METHOD_RESET || arg == null) return null
        val pristine = pristine(checkName(arg))
        val working = File(root, arg)
        pristine.copyTo(working, overwrite = true)
        return Bundle().apply { putLong("bytes", working.length()) }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun nameOf(uri: Uri): String = checkName(uri.lastPathSegment ?: throw FileNotFoundException("$uri"))

    private fun checkName(name: String): String = name.takeIf { it in SIZES } ?: throw FileNotFoundException(name)

    /** The file the app reads and saves, made from the pristine copy when missing. */
    private fun working(name: String): File {
        val file = File(root, name)
        if (!file.isFile) pristine(name).copyTo(file, overwrite = true)
        return file
    }

    /** The generated text, written once per install. */
    private fun pristine(name: String): File {
        val file = File(File(root, "pristine"), name)
        synchronized(this) {
            if (!file.isFile) {
                file.parentFile?.mkdirs()
                val partial = File(file.path + ".part")
                partial.bufferedWriter().use { PerfCorpus.write(it, SIZES.getValue(name)) }
                partial.renameTo(file)
            }
        }
        return file
    }

    private companion object {
        const val MIME_TYPE = "text/markdown"
        const val METHOD_RESET = "reset"

        /** The corpus: the sizes the budgets name (quality-bar §4). */
        val SIZES: Map<String, Long> =
            mapOf(
                "perf-50kb.md" to 50L * 1024,
                "perf-1mb.md" to 1024L * 1024,
                "perf-20mb.md" to 20L * 1024 * 1024,
            )
    }
}
