package com.scorchsoft.ssmarkdown.files.save

import com.scorchsoft.ssmarkdown.files.fingerprint.ByteDigest
import com.scorchsoft.ssmarkdown.files.fingerprint.HashingOutputStream
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.WriteChannel
import com.scorchsoft.ssmarkdown.files.provider.WriteMode
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import java.io.IOException
import java.io.OutputStream

/** How one attempt to write a document went. */
internal sealed interface WriteAttempt {
    /** Everything was written and the stream closed cleanly. */
    data class Written(
        val digest: ByteDigest,
    ) : WriteAttempt

    /** The provider refused to open the document; the file was not touched. */
    data class NotOpened(
        val failure: ProviderResult.Failure,
    ) : WriteAttempt

    /** The document was opened (and so possibly truncated) and then the write failed. */
    data class FailedAfterOpen(
        val failure: ProviderResult.Failure,
    ) : WriteAttempt
}

/**
 * Writes bytes to a provider in truncating mode: `"wt"` first, then
 * `"rwt"` plus an explicit `FileChannel.truncate` if the provider rejects
 * `"wt"` or [forceExplicitTruncate] asks for it (docs/robustness.md §2).
 */
internal class ProviderWriter(
    private val provider: DocumentProvider,
) {
    fun write(
        uri: DocumentUri,
        forceExplicitTruncate: Boolean = false,
        body: (OutputStream) -> Unit,
    ): WriteAttempt {
        if (!forceExplicitTruncate) {
            val first = attempt(uri, WriteMode.Truncate, body)
            if (first !is WriteAttempt.NotOpened || first.failure != ProviderResult.ModeUnsupported) return first
        }
        return attempt(uri, WriteMode.ReadWriteTruncate, body)
    }

    private fun attempt(
        uri: DocumentUri,
        mode: WriteMode,
        body: (OutputStream) -> Unit,
    ): WriteAttempt {
        val channel =
            when (val opened = provider.openOutput(uri, mode)) {
                is ProviderResult.Ok -> opened.value
                is ProviderResult.Failure -> return WriteAttempt.NotOpened(opened)
            }
        val result = providerCall { writeAndClose(channel, mode, body) }
        return when (result) {
            is ProviderResult.Ok -> WriteAttempt.Written(result.value)
            is ProviderResult.Failure -> WriteAttempt.FailedAfterOpen(result).also { closeQuietly(channel) }
        }
    }

    private fun writeAndClose(
        channel: WriteChannel,
        mode: WriteMode,
        body: (OutputStream) -> Unit,
    ): ByteDigest {
        val hashing = HashingOutputStream(channel.stream)
        body(hashing)
        hashing.flush()
        val digest = hashing.result()
        if (mode == WriteMode.ReadWriteTruncate && !channel.truncate(digest.size)) {
            throw IOException("provider cannot truncate after an \"rwt\" write")
        }
        channel.close() // Where cloud providers upload, and so where they fail.
        return digest
    }

    private fun closeQuietly(channel: WriteChannel) {
        providerCall { channel.close() }
    }
}
