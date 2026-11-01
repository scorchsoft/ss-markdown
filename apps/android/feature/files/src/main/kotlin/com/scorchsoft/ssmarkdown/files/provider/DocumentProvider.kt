package com.scorchsoft.ssmarkdown.files.provider

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * The three things the store needs from wherever a document lives:
 * metadata, a stream in and a stream out.
 *
 * It is an interface so the save pipeline can be proven against a fake
 * that fails mid-write, ignores truncation or loses its permission
 * (docs/robustness.md Part 3, "Save-fault injection"). The real one is
 * [ContentResolverProvider]; drafts use [FileDocumentProvider].
 *
 * Every call blocks on I/O: call it from the I/O dispatcher, never the main thread.
 */
interface DocumentProvider {
    /** Size, `lastModified` and name, without reading the bytes. */
    fun metadata(uri: DocumentUri): ProviderResult<FileMetadata>

    /** A stream of the document's bytes. The caller closes it. */
    fun openInput(uri: DocumentUri): ProviderResult<InputStream>

    /** A stream that replaces the document's bytes; see [WriteMode]. The caller closes it. */
    fun openOutput(
        uri: DocumentUri,
        mode: WriteMode,
    ): ProviderResult<WriteChannel>
}

/**
 * How a document is opened for writing (docs/robustness.md §2).
 *
 * @property code the mode string handed to `ContentResolver`.
 */
enum class WriteMode(
    val code: String,
) {
    /** `"wt"`: write and truncate. Never plain `"w"`, which some providers do not truncate. */
    Truncate("wt"),

    /** `"rwt"`: the fallback when a provider rejects `"wt"`; followed by an explicit [WriteChannel.truncate]. */
    ReadWriteTruncate("rwt"),
}

/** An open write: the stream, plus an explicit truncate for providers that ignore the mode's. */
interface WriteChannel : Closeable {
    /** Where the bytes go. */
    val stream: OutputStream

    /**
     * Cuts the document to [size] bytes (`FileChannel.truncate`). Returns
     * false when the underlying descriptor cannot be truncated.
     */
    fun truncate(size: Long): Boolean
}
