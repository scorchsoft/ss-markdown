package com.scorchsoft.ssmarkdown.files.provider

import java.io.InputStream

/**
 * Sends drafts to [drafts] and every other URI to [documents], so the
 * store sees one provider and treats an untitled note exactly like a file.
 */
class RoutingDocumentProvider(
    private val drafts: DocumentProvider,
    private val documents: DocumentProvider,
) : DocumentProvider {
    override fun metadata(uri: DocumentUri): ProviderResult<FileMetadata> = route(uri).metadata(uri)

    override fun openInput(uri: DocumentUri): ProviderResult<InputStream> = route(uri).openInput(uri)

    override fun openOutput(
        uri: DocumentUri,
        mode: WriteMode,
    ): ProviderResult<WriteChannel> = route(uri).openOutput(uri, mode)

    private fun route(uri: DocumentUri) = if (uri.isDraft) drafts else documents
}
