package com.scorchsoft.ssmarkdown.files.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Just enough of a documents provider for `DocumentsContract`'s create,
 * rename and delete, which reach a provider as `call()`s, over one flat
 * folder on disk. Document URIs are `content://<authority>/<name>`; the
 * folder itself is `content://<authority>/`.
 *
 * Like real providers it picks a free name on a clash ("a (1).md") and
 * may hand back a new URI on rename.
 */
class FolderProviderFake : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val bundle = extras ?: Bundle()
        val target = uriOf(bundle)
        return when (method) {
            METHOD_CREATE -> created(bundle.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty())
            METHOD_RENAME -> renamed(target, bundle.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty())
            METHOD_DELETE -> deleted(target)
            else -> super.call(method, arg, extras)
        }
    }

    private fun created(name: String): Bundle {
        if (refuseCreate) throw SecurityException("read-only folder")
        val file = freeName(name)
        file.createNewFile()
        return Bundle().apply { putParcelable(EXTRA_URI, uriFor(file.name)) }
    }

    private fun renamed(
        target: Uri,
        name: String,
    ): Bundle {
        val from = fileOf(target)
        if (!from.exists()) throw FileNotFoundException("$target")
        val to = freeName(name)
        check(from.renameTo(to)) { "rename failed" }
        return Bundle().apply { putParcelable(EXTRA_URI, uriFor(to.name)) }
    }

    private fun deleted(target: Uri): Bundle? {
        val file = fileOf(target)
        if (!file.exists()) throw FileNotFoundException("$target")
        file.delete()
        return null
    }

    private fun freeName(name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file =
                File(dir, "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.')}")
        }
        return file
    }

    @Suppress("DEPRECATION") // getParcelable(String) is the only form before API 33, and minSdk is 29.
    private fun uriOf(bundle: Bundle): Uri = bundle.getParcelable(EXTRA_URI) ?: Uri.EMPTY

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileOf(uri)
        val columns =
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(
            columns,
        ).apply { if (file.isFile) addRow(arrayOf(file.name, file.length(), file.lastModified())) }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val file = fileOf(uri)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "text/markdown"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "com.scorchsoft.ssmarkdown.test.folder"

        // DocumentsContract's hidden method and extra names: the wire protocol between it and a provider.
        private const val METHOD_CREATE = "android:createDocument"
        private const val METHOD_RENAME = "android:renameDocument"
        private const val METHOD_DELETE = "android:deleteDocument"
        private const val EXTRA_URI = "uri"

        lateinit var dir: File
        var refuseCreate = false

        val folderUri: Uri get() = Uri.parse("content://$AUTHORITY/")

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(name)}")

        private fun fileOf(uri: Uri) = File(dir, uri.lastPathSegment.orEmpty())
    }
}
