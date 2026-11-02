package com.scorchsoft.ssmarkdown.files.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.scorchsoft.ssmarkdown.files.provider.DocumentProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.provider.WriteMode
import com.scorchsoft.ssmarkdown.files.provider.providerCall
import java.io.IOException

/**
 * Create, rename, duplicate and delete inside a picked folder, through
 * `DocumentsContract`. Each returns a [ProviderResult]; a provider that
 * does not support an operation answers [ProviderResult.ModeUnsupported]
 * or a failure, never a crash. Blocking: run on the I/O dispatcher.
 *
 * Renaming or deleting a file that is open is the caller's to guard: an
 * open dirty buffer keeps its text either way, and the store's next check
 * reports the file missing.
 */
class DocumentActions(
    private val resolver: ContentResolver,
    private val provider: DocumentProvider,
) {
    /** Creates an empty Markdown file named [displayName] in [parent] (a document URI within a tree). */
    fun create(
        parent: DocumentUri,
        displayName: String,
        mimeType: String = MarkdownFiles.CREATE_MIME_TYPE,
    ): ProviderResult<DocumentUri> =
        providerCall {
            DocumentsContract.createDocument(resolver, Uri.parse(parent.value), mimeType, displayName)
        }.mapNotNull()

    /**
     * Renames [uri]; the provider may return a new URI (the old one then
     * stops working). `renameDocument` answers null both when the id did
     * not change and when the rename failed quietly, so a null is checked
     * against the file's name before it is called a success.
     */
    fun rename(
        uri: DocumentUri,
        displayName: String,
    ): ProviderResult<DocumentUri> =
        when (
            val result =
                providerCall {
                    DocumentsContract.renameDocument(
                        resolver,
                        Uri.parse(uri.value),
                        displayName,
                    )
                }
        ) {
            is ProviderResult.Ok -> {
                val renamed = result.value
                if (renamed !=
                    null
                ) {
                    ProviderResult.Ok(DocumentUri(renamed.toString()))
                } else {
                    confirmRenamed(uri, displayName)
                }
            }

            is ProviderResult.Failure -> {
                result
            }
        }

    private fun confirmRenamed(
        uri: DocumentUri,
        displayName: String,
    ): ProviderResult<DocumentUri> =
        when (val metadata = provider.metadata(uri)) {
            is ProviderResult.Ok if metadata.value.displayName == displayName -> ProviderResult.Ok(uri)
            is ProviderResult.Ok -> ProviderResult.Failed(IOException("the provider did not rename $uri"))
            is ProviderResult.Failure -> metadata
        }

    /** Deletes [uri]. */
    fun delete(uri: DocumentUri): ProviderResult<Boolean> =
        providerCall { DocumentsContract.deleteDocument(resolver, Uri.parse(uri.value)) }

    /**
     * Copies [uri] into [parent] as [displayName]: a byte-for-byte copy
     * through streams, which works on every provider (`copyDocument` is
     * optional and many do not implement it).
     */
    @Suppress("ReturnCount") // Create, open-in and open-out each fail on their own.
    fun duplicate(
        uri: DocumentUri,
        parent: DocumentUri,
        displayName: String,
    ): ProviderResult<DocumentUri> {
        val created =
            when (val result = create(parent, displayName)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return result
            }
        val input =
            when (val result = provider.openInput(uri)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return result
            }
        val output =
            when (val result = provider.openOutput(created, WriteMode.Truncate)) {
                is ProviderResult.Ok -> result.value
                is ProviderResult.Failure -> return result.also { input.close() }
            }
        return providerCall {
            input.use { source -> output.use { target -> source.copyTo(target.stream) } }
            created
        }
    }

    private fun <T : Any> ProviderResult<T?>.mapNotNull(): ProviderResult<DocumentUri> =
        when (this) {
            is ProviderResult.Ok -> {
                value?.let { ProviderResult.Ok(DocumentUri(it.toString())) }
                    ?: ProviderResult.NotFound
            }

            is ProviderResult.Failure -> {
                this
            }
        }
}
