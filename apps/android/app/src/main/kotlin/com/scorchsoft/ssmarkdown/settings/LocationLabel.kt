package com.scorchsoft.ssmarkdown.settings

import java.net.URLDecoder

/**
 * The short "where" under a recent file's name ("Documents/specs",
 * "Drive", "Downloads"), worked out from its document URI alone: no
 * provider query, so it is free to compute for every row and works for a
 * file whose provider is gone.
 *
 * External-storage document ids read `primary:Documents/specs/a.md`, so
 * their folder path is shown; other providers are named by what they are.
 * Anything unrecognised gets an empty label rather than a guess.
 */
object LocationLabel {
    private val providers =
        mapOf(
            "com.google.android.apps.docs.storage" to "Drive",
            "com.android.providers.downloads.documents" to "Downloads",
            "com.android.providers.media.documents" to "Media",
            "com.microsoft.skydrive.content.StorageAccessProvider" to "OneDrive",
            "com.dropbox.product.android.dbapp.document_provider.documents" to "Dropbox",
        )

    /** The label for [uri]; empty when nothing honest can be said. */
    fun of(uri: String): String {
        val authority = uri.substringAfter("content://", "").substringBefore('/')
        return when (authority) {
            "" -> ""
            "com.android.externalstorage.documents" -> externalStorage(uri)
            else -> providers[authority].orEmpty()
        }
    }

    private fun externalStorage(uri: String): String {
        // The last path segment is the document id, URL-encoded: "primary%3ADocuments%2Fspecs%2Fa.md".
        val id = decode(uri.substringAfterLast('/'))
        val path = id.substringAfter(':', "")
        val folder = path.substringBeforeLast('/', "")
        return when {
            folder.isNotEmpty() -> folder
            id.startsWith("primary:") -> "Internal storage"
            else -> ""
        }
    }

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text, "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }
}
