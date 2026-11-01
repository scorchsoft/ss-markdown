package com.scorchsoft.ssmarkdown.files.provider

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * The real [DocumentProvider]: Storage Access Framework URIs through a
 * [ContentResolver].
 *
 * `"wt"` opens a plain stream. `"rwt"` opens a file descriptor so the
 * pipeline can call `FileChannel.truncate` itself, for providers that do
 * not honour the mode's truncation (docs/robustness.md §2).
 */
class ContentResolverProvider(
    private val resolver: ContentResolver,
) : DocumentProvider {
    override fun metadata(uri: DocumentUri): ProviderResult<FileMetadata> =
        providerCall {
            resolver.query(Uri.parse(uri.value), null, null, null, null)?.use(::readMetadata)
        }.orNotFound()

    override fun openInput(uri: DocumentUri): ProviderResult<InputStream> =
        providerCall { resolver.openInputStream(Uri.parse(uri.value)) }.orNotFound()

    override fun openOutput(
        uri: DocumentUri,
        mode: WriteMode,
    ): ProviderResult<WriteChannel> =
        providerCall(openingForWrite = true) {
            resolver.openFileDescriptor(Uri.parse(uri.value), mode.code)?.let(::DescriptorChannel)
        }.orNotFound()

    private fun readMetadata(cursor: Cursor): FileMetadata? {
        if (!cursor.moveToFirst()) return null
        val flags =
            cursor.intOr(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
            )
        return FileMetadata(
            displayName = cursor.stringOr(OpenableColumns.DISPLAY_NAME),
            size = cursor.longOr(OpenableColumns.SIZE, FileMetadata.UNKNOWN_SIZE),
            lastModified = cursor.longOr(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0),
            mimeType = cursor.stringOr(DocumentsContract.Document.COLUMN_MIME_TYPE),
            writable = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
        )
    }

    /**
     * A write through a [ParcelFileDescriptor]. Closing it is where a
     * cloud provider uploads and reports failure, so close errors reach
     * the pipeline instead of being swallowed.
     */
    private class DescriptorChannel(
        private val descriptor: ParcelFileDescriptor,
    ) : WriteChannel {
        private val out = FileOutputStream(descriptor.fileDescriptor)
        override val stream: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = out.write(b)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = out.write(b, off, len)

                override fun flush() = out.flush()

                override fun close() {
                    out.flush()
                    syncIfPossible()
                    out.close()
                    descriptor.close()
                }
            }

        override fun truncate(size: Long): Boolean =
            try {
                out.channel.truncate(size)
                true
            } catch (_: IOException) {
                false // a pipe from a streaming provider has no length to cut
            }

        override fun close() = stream.close()

        // Cloud providers often hand out a pipe, which cannot be synced; the
        // read-back verify is what proves those writes, so a refusal is fine.
        private fun syncIfPossible() {
            try {
                descriptor.fileDescriptor.sync()
            } catch (_: SyncFailedException) {
                // Not a regular file: nothing to sync.
            }
        }
    }
}

/** A provider that answers null instead of throwing means the document is not there. */
private fun <T> ProviderResult<T?>.orNotFound(): ProviderResult<T> =
    when (this) {
        is ProviderResult.Ok -> value?.let { ProviderResult.Ok(it) } ?: ProviderResult.NotFound
        is ProviderResult.Failure -> this
    }

private fun Cursor.stringOr(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.longOr(
    column: String,
    fallback: Long,
): Long {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else fallback
}

private fun Cursor.intOr(
    column: String,
    fallback: Int,
): Int {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else fallback
}
