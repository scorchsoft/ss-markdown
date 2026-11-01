package com.scorchsoft.ssmarkdown.files.fakes

import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.WriteChannel
import com.scorchsoft.ssmarkdown.files.provider.WriteMode
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory SAF provider with fault injection: the "fake provider"
 * of docs/robustness.md Part 3. Writes land in the file byte by byte as
 * they happen, so a failure part way really does leave a partial file
 * unless the pipeline repairs it.
 */
class FakeDocumentProvider : DocumentProvider {
    private class FakeFile(
        var bytes: ByteArray,
        var lastModified: Long,
        var writable: Boolean = true,
    )

    private val files = HashMap<DocumentUri, FakeFile>()

    /** Advances on every write; starts off a whole second so times are "precise" unless a test says otherwise. */
    var clock: Long = 1_700_000_000_123

    /** When set, every file reports this `lastModified` (0, or a coarse whole second). */
    var fixedLastModified: Long? = null

    /** URIs whose grant has been revoked: every call throws `SecurityException`. */
    val revoked = HashSet<DocumentUri>()

    /** The next write throws after this many bytes (one-shot). */
    var failNextWriteAfter: Int? = null

    /** The next write reports ENOSPC after this many bytes (one-shot). */
    var storageFullAfter: Int? = null

    /** `"wt"` is refused, as some providers do. */
    var rejectTruncateMode = false

    /** Opening for write never truncates (the plain-"w" bug); explicit truncate works unless [truncateUnsupported]. */
    var ignoreTruncation = false

    /** `FileChannel.truncate` is not available (a pipe). */
    var truncateUnsupported = false

    /** The next write's close flips a byte (one-shot): a provider that stores something else. */
    var corruptNextWrite = false

    /** The next close throws (one-shot), as a cloud provider does when the upload fails. */
    var failNextClose = false

    /** Each read sleeps this long: a slow cloud provider. */
    var readDelayMillis = 0L

    /** Called before every byte written: a test advances its clock here to fake a slow upload. */
    var onWriteByte: (() -> Unit)? = null

    /** Called on every open for reading: a test advances its clock here to fake a slow download. */
    var onOpenInput: (() -> Unit)? = null

    /** Writes opened so far, for asserting nothing was written. */
    var writesOpened = 0
        private set

    fun put(
        uri: DocumentUri,
        bytes: ByteArray,
        writable: Boolean = true,
    ) {
        files[uri] = FakeFile(bytes.copyOf(), tick(), writable)
    }

    fun put(
        uri: String,
        text: String,
    ): DocumentUri = DocumentUri(uri).also { put(it, text.toByteArray()) }

    /** Another app writes the file. [lastModified] lets a test fake a same-second change. */
    fun externalWrite(
        uri: DocumentUri,
        bytes: ByteArray,
        lastModified: Long? = null,
    ) {
        val file = files.getValue(uri)
        file.bytes = bytes.copyOf()
        file.lastModified = lastModified ?: tick()
    }

    fun delete(uri: DocumentUri) {
        files.remove(uri)
    }

    fun rename(
        from: DocumentUri,
        to: DocumentUri,
    ) {
        files.remove(from)?.let { files[to] = it }
    }

    fun bytes(uri: DocumentUri): ByteArray = files.getValue(uri).bytes.copyOf()

    fun text(uri: DocumentUri): String = String(bytes(uri), Charsets.UTF_8)

    fun lastModified(uri: DocumentUri): Long = files.getValue(uri).lastModified

    fun exists(uri: DocumentUri): Boolean = uri in files

    override fun metadata(uri: DocumentUri): ProviderResult<FileMetadata> =
        providerCall {
            val file = find(uri)
            FileMetadata(
                uri.value.substringAfterLast('/'),
                file.bytes.size.toLong(),
                reported(file),
                writable = file.writable,
            )
        }

    override fun openInput(uri: DocumentUri): ProviderResult<InputStream> =
        providerCall {
            val file = find(uri)
            if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
            onOpenInput?.invoke()
            ByteArrayInputStream(file.bytes.copyOf())
        }

    override fun openOutput(
        uri: DocumentUri,
        mode: WriteMode,
    ): ProviderResult<WriteChannel> =
        providerCall(openingForWrite = true) {
            val file = find(uri)
            if (!file.writable) throw SecurityException("no write grant")
            require(mode != WriteMode.Truncate || !rejectTruncateMode) { "Unsupported mode: wt" }
            writesOpened++
            if (!ignoreTruncation) file.bytes = ByteArray(0)
            FakeChannel(file)
        }

    private fun find(uri: DocumentUri): FakeFile {
        if (uri in revoked) throw SecurityException("Permission Denial: $uri")
        return files[uri] ?: throw FileNotFoundException("No such document: $uri")
    }

    private fun reported(file: FakeFile) = fixedLastModified ?: file.lastModified

    private fun tick(): Long {
        clock += 1_009
        return clock
    }

    private inner class FakeChannel(
        private val file: FakeFile,
    ) : WriteChannel {
        private var position = 0
        private var written = 0
        private val failAfter = failNextWriteAfter.also { failNextWriteAfter = null }
        private val fullAfter = storageFullAfter.also { storageFullAfter = null }
        private val corrupt = corruptNextWrite.also { corruptNextWrite = false }
        private val closeFails = failNextClose.also { failNextClose = false }

        override val stream: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) {
                    onWriteByte?.invoke()
                    if (failAfter != null && written >= failAfter) throw IOException("connection reset")
                    if (fullAfter != null &&
                        written >= fullAfter
                    ) {
                        throw IOException("write failed: ENOSPC (No space left on device)")
                    }
                    if (position >= file.bytes.size) file.bytes = file.bytes.copyOf(position + 1)
                    file.bytes[position++] = b.toByte()
                    written++
                }

                override fun close() = finish()
            }

        override fun truncate(size: Long): Boolean {
            if (truncateUnsupported) return false
            file.bytes = file.bytes.copyOf(size.toInt())
            return true
        }

        override fun close() = stream.close()

        private var closed = false

        private fun finish() {
            if (closed) return
            closed = true
            if (closeFails) throw IOException("upload failed")
            if (corrupt && file.bytes.isNotEmpty()) file.bytes[0] = (file.bytes[0] + 1).toByte()
            file.lastModified = tick()
        }
    }
}
