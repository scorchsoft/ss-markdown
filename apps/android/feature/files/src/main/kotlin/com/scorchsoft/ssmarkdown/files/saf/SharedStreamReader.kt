package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.FileMetadata
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** What reading a shared stream came to. */
sealed interface SharedContent {
    /**
     * Read in full, as UTF-8 text.
     *
     * @property text the content.
     * @property name the provider's display name, if any.
     */
    data class Text(
        val text: String,
        val name: String?,
    ) : SharedContent

    /**
     * Over the limit: offer to open it as a document instead of buffering it.
     *
     * @property sizeBytes the size known so far (the reported size, or the limit + 1 when a stream overran).
     */
    data class TooLarge(
        val sizeBytes: Long,
    ) : SharedContent

    /** Not valid UTF-8: "This doesn't look like text". */
    data object NotText : SharedContent

    /**
     * The provider failed.
     *
     * @property failure what it said.
     */
    data class Failed(
        val failure: ProviderResult.Failure,
    ) : SharedContent
}

/**
 * Reads a shared stream into memory only after checking its size, and
 * stops at [limitBytes] even when the provider reported no size or a
 * wrong one (edge-cases "Share-in": size is checked before buffering).
 *
 * Blocking: run on the I/O dispatcher.
 */
class SharedStreamReader(
    private val provider: DocumentProvider,
    private val limitBytes: Long = DEFAULT_LIMIT_BYTES,
) {
    /** Reads [uri] if it fits. */
    @Suppress("ReturnCount") // Each check refuses a stream for its own reason before the next step.
    fun read(uri: DocumentUri): SharedContent {
        val metadata = provider.metadata(uri)
        val known = (metadata as? ProviderResult.Ok)?.value
        if (known != null && known.size != FileMetadata.UNKNOWN_SIZE && known.size > limitBytes) {
            return SharedContent.TooLarge(known.size)
        }
        val input =
            when (val opened = provider.openInput(uri)) {
                is ProviderResult.Ok -> opened.value
                is ProviderResult.Failure -> return SharedContent.Failed(opened)
            }
        val bytes =
            when (val read = providerCall { input.use(::readCapped) }) {
                is ProviderResult.Ok -> read.value ?: return SharedContent.TooLarge(limitBytes + 1)
                is ProviderResult.Failure -> return SharedContent.Failed(read)
            }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            SharedContent.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString(), known?.displayName)
        } catch (_: CharacterCodingException) {
            SharedContent.NotText
        }
    }

    /** The whole stream, or null as soon as it passes the limit. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > limitBytes) return null
        }
    }

    /** Defaults. */
    companion object {
        /** 8 MB: generous for a note, far below any phone's heap. */
        const val DEFAULT_LIMIT_BYTES: Long = 8L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
    }
}
