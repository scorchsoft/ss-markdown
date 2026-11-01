package com.scorchsoft.ssmarkdown.files.provider

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A [DocumentProvider] over plain files, for documents that live in app
 * storage: untitled drafts. [resolve] maps a URI to its file, or null when
 * this provider does not own that URI.
 *
 * Plain files go through the same pipeline as SAF documents (backup,
 * write, verify) so drafts get the same guarantees and the same tests.
 */
class FileDocumentProvider(
    private val resolve: (DocumentUri) -> File?,
) : DocumentProvider {
    @Suppress("ReturnCount") // Unowned URI and missing file are both NotFound, found at different steps.
    override fun metadata(uri: DocumentUri): ProviderResult<FileMetadata> {
        val file = resolve(uri) ?: return ProviderResult.NotFound
        return providerCall {
            if (!file.isFile) return ProviderResult.NotFound
            FileMetadata(file.name, file.length(), file.lastModified(), writable = file.canWrite())
        }
    }

    override fun openInput(uri: DocumentUri): ProviderResult<InputStream> {
        val file = resolve(uri) ?: return ProviderResult.NotFound
        return providerCall { file.inputStream() }
    }

    override fun openOutput(
        uri: DocumentUri,
        mode: WriteMode,
    ): ProviderResult<WriteChannel> {
        val file = resolve(uri) ?: return ProviderResult.NotFound
        return providerCall(openingForWrite = true) {
            when (mode) {
                WriteMode.Truncate -> StreamChannel(FileOutputStream(file))
                WriteMode.ReadWriteTruncate -> RandomAccessChannel(RandomAccessFile(file, "rw").apply { setLength(0) })
            }
        }
    }

    private class StreamChannel(
        private val out: FileOutputStream,
    ) : WriteChannel {
        override val stream: OutputStream = SyncOnClose(out)

        override fun truncate(size: Long): Boolean {
            out.channel.truncate(size)
            return true
        }

        override fun close() = stream.close()
    }

    private class RandomAccessChannel(
        private val file: RandomAccessFile,
    ) : WriteChannel {
        override val stream: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = file.write(b)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = file.write(b, off, len)

                override fun close() {
                    file.fd.sync()
                    file.close()
                }
            }

        override fun truncate(size: Long): Boolean {
            file.channel.truncate(size)
            return true
        }

        override fun close() = stream.close()
    }

    /** `fsync` before close, so a verified save is a durable one. */
    private class SyncOnClose(
        private val out: FileOutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = out.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun flush() = out.flush()

        override fun close() {
            out.flush()
            out.fd.sync()
            out.close()
        }
    }
}
