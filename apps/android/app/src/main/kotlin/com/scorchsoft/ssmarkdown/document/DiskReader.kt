package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads a document's current bytes as text, for "Compare" after a
 * recovery: the file on disk against the restored edits. Never used to
 * open a document (that is the store's job, with its encoding and
 * memory checks); a compare of a file too large for memory is refused.
 */
class DiskReader(
    private val provider: DocumentProvider,
    private val io: CoroutineDispatcher,
    private val limitBytes: Long = DEFAULT_LIMIT_BYTES,
) {
    /** The file's text, or null if it cannot be read or is over the limit. */
    suspend fun read(uri: String): String? =
        withContext(io) {
            when (val input = provider.openInput(DocumentUri(uri))) {
                is ProviderResult.Ok -> readCapped(input.value)
                is ProviderResult.Failure -> null
            }
        }

    private fun readCapped(input: java.io.InputStream): String? =
        try {
            input.use(::readUpToLimit)
        } catch (_: IOException) {
            null
        }

    /** At most [limitBytes] + 1 bytes of [stream], decoded; null when it is over the limit. */
    private fun readUpToLimit(stream: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var read = stream.read(chunk)
        while (read >= 0 && out.size() <= limitBytes) {
            out.write(chunk, 0, read)
            read = stream.read(chunk)
        }
        return if (out.size() > limitBytes) null else out.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** 16 MB: a compare is a diff, and a diff of anything larger is not readable on a phone anyway. */
        const val DEFAULT_LIMIT_BYTES: Long = 16L * 1024 * 1024

        private const val CHUNK_BYTES = 64 * 1024
    }
}
