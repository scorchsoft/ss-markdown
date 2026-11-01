package com.scorchsoft.ssmarkdown.files.document

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.memory.Admission
import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.memory.GovernedInputStream
import com.scorchsoft.ssmarkdown.files.memory.LoadAbortedForMemory
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderFailures
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A file read from disk, before the store decides what to do with it. */
internal sealed interface LoadResult {
    /**
     * Read and decoded.
     *
     * @property document the text, ready to edit (or to show read-only).
     * @property fingerprint size, `lastModified` and SHA-256 of exactly the bytes read.
     * @property metadata what the provider reported.
     * @property readOnly why it may not be edited, if it may not.
     * @property budget the memory budget admission chose.
     */
    class Loaded(
        val document: TextDocument,
        val fingerprint: BaseFingerprint,
        val metadata: FileMetadata,
        val readOnly: ReadOnlyReason?,
        val budget: Budget,
    ) : LoadResult

    /** Would not fit in memory. */
    data class Declined(
        val admission: Admission.Declined,
    ) : LoadResult

    /** Not UTF-8 by its BOM. */
    data class UnsupportedEncoding(
        val encoding: String,
    ) : LoadResult

    /** The provider failed. */
    data class Failed(
        val failure: ProviderResult.Failure,
    ) : LoadResult
}

/**
 * Reads a document in one streamed pass: admission control first, then
 * bytes flow through the memory poll, the SHA-256 and (when given) into
 * a staged base copy while `core:text` decodes them. No whole-file
 * `String` or byte array is ever built for a valid file.
 *
 * Blocking: run on the I/O dispatcher.
 */
internal class DocumentLoader(
    private val provider: DocumentProvider,
    private val governor: MemoryGovernor,
) {
    @Suppress("ReturnCount") // Metadata, admission and open each fail in their own way before decoding.
    fun load(
        uri: DocumentUri,
        copyTo: BaseCopyStore.StagedCopy? = null,
    ): LoadResult {
        val metadata =
            when (val result = provider.metadata(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return LoadResult.Failed(result)
            }
        val admission = governor.admit(metadata.size.coerceAtLeast(0))
        if (admission is Admission.Declined) return LoadResult.Declined(admission)
        val budget = (admission as Admission.Admitted).budget
        val input =
            when (val result = provider.openInput(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return LoadResult.Failed(result)
            }
        val read = providerCall { input.use { decode(it, copyTo?.stream) } }
        return when (read) {
            is ProviderResult.Ok -> {
                finish(uri, read.value, metadata, budget)
            }

            is ProviderResult.Failure -> {
                val outOfMemory = (read as? ProviderResult.Failed)?.cause is LoadAbortedForMemory
                if (outOfMemory) LoadResult.Declined(governor.decline(metadata.size)) else LoadResult.Failed(read)
            }
        }
    }

    private class Decoding(
        val result: DecodeResult,
        val digest: ByteDigest,
    )

    private fun decode(
        raw: InputStream,
        copyTo: OutputStream?,
    ): Decoding {
        val hashing = HashingInputStream(GovernedInputStream(raw, governor), copyTo)
        val result = Utf8Decoder.decode(hashing)
        if (result is DecodeResult.ReadFailed) throw result.cause
        hashing.drain() // A malformed file stops the decoder early; the hash covers every byte regardless.
        return Decoding(result, hashing.digest())
    }

    private fun finish(
        uri: DocumentUri,
        decoding: Decoding,
        metadata: FileMetadata,
        budget: Budget,
    ): LoadResult {
        val digest = decoding.digest
        val fingerprint = BaseFingerprint(digest.size, metadata.lastModified, digest.sha256)
        val writable = if (metadata.writable) null else ReadOnlyReason.NoWriteAccess
        return when (val result = decoding.result) {
            is DecodeResult.Decoded -> {
                val document = TextDocument.from(result)
                val reason = if (looksBinary(document)) ReadOnlyReason.NotText else writable
                LoadResult.Loaded(document, fingerprint, metadata, reason, budget)
            }

            is DecodeResult.Malformed -> {
                loadLossy(uri, fingerprint, metadata, budget, ReadOnlyReason.NotUtf8(result.byteOffset))
            }

            is DecodeResult.UnsupportedEncoding -> {
                LoadResult.UnsupportedEncoding(result.encoding)
            }

            is DecodeResult.ReadFailed -> {
                LoadResult.Failed(ProviderFailures.classify(result.cause))
            }
        }
    }

    /** Shows invalid UTF-8 with replacement characters, read-only, so it can never be saved over. */
    private fun loadLossy(
        uri: DocumentUri,
        fingerprint: BaseFingerprint,
        metadata: FileMetadata,
        budget: Budget,
        reason: ReadOnlyReason,
    ): LoadResult {
        val text =
            when (val input = provider.openInput(uri)) {
                is ProviderResult.Ok -> providerCall { input.value.use { it.reader(Charsets.UTF_8).readText() } }
                is ProviderResult.Failure -> input
            }
        return when (text) {
            is ProviderResult.Ok -> {
                LoadResult.Loaded(
                    TextDocument(PieceTree.of(text.value)),
                    fingerprint,
                    metadata,
                    reason,
                    budget,
                )
            }

            is ProviderResult.Failure -> {
                LoadResult.Failed(text)
            }
        }
    }

    /** NUL in the first 64K chars: "This doesn't look like text". */
    private fun looksBinary(document: TextDocument): Boolean {
        val text = document.text
        val end = minOf(text.length, BINARY_PROBE_CHARS)
        return (0 until end).any { text[it] == '\u0000' }
    }

    /** Hashes and optionally copies every byte that passes through. */
    private class HashingInputStream(
        input: InputStream,
        private val copyTo: OutputStream?,
    ) : FilterInputStream(input) {
        private val sha: MessageDigest = ByteDigest.sha256()
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                sha.update(b.toByte())
                copyTo?.write(b)
                count++
            }
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                sha.update(b, off, n)
                copyTo?.write(b, off, n)
                count += n
            }
            return n
        }

        override fun skip(n: Long): Long = 0 // Never skip: every byte must be hashed.

        fun drain() {
            val buffer = ByteArray(DRAIN_BUFFER)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }

        fun digest(): ByteDigest = ByteDigest(count, sha.digest())
    }

    private companion object {
        const val BINARY_PROBE_CHARS = 64 * 1024
        const val DRAIN_BUFFER = 64 * 1024
    }
}
