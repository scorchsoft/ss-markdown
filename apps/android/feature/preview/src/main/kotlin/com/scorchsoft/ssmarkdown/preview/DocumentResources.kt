package com.scorchsoft.ssmarkdown.preview

import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.InputStream

/**
 * Opens files next to the document (relative images), for the page's `/doc/`
 * paths. The host decides what is reachable, typically only files inside the
 * folder the user granted; returning null leaves the image broken.
 */
fun interface DocumentResourceResolver {
    fun open(relativePath: String): InputStream?
}

/** Serves `/doc/<path>` from a [DocumentResourceResolver] chosen at request time. */
internal class DocumentPathHandler(
    private val resolver: () -> DocumentResourceResolver?,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val extension = path.substringAfterLast('.', "").lowercase()
        // Only images, and never a path that climbs out of the document's folder.
        val mime =
            MimeTypeMap
                .getSingleton()
                .getMimeTypeFromExtension(extension)
                ?.takeIf { it.startsWith("image/") && path.split('/').none { part -> part == ".." } }
        val stream = mime?.let { resolver()?.open(path) }
        return stream?.let { WebResourceResponse(mime, null, it) }
    }
}
