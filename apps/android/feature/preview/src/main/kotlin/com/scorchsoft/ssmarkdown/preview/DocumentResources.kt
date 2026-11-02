package com.scorchsoft.ssmarkdown.preview

import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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

/**
 * Serves `/fonts/<path>` from the app's font store, chosen at request time,
 * so a theme's downloaded or imported fonts reach the page's `@font-face`
 * rules on its own origin (the CSP allows fonts from 'self' only). Font
 * files only, never a path out of the directory.
 */
internal class FontPathHandler(
    private val directory: () -> File?,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val mime = FONT_TYPES[path.substringAfterLast('.', "").lowercase()] ?: return null
        val root = directory() ?: return null
        val file = fileUnder(root, path) ?: return null
        return try {
            WebResourceResponse(mime, null, FileInputStream(file))
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private val FONT_TYPES = mapOf("ttf" to "font/ttf", "otf" to "font/otf", "woff2" to "font/woff2")

        /** [path] under [root], or null if it names anything outside it or nothing at all. */
        fun fileUnder(
            root: File,
            path: String,
        ): File? {
            if (path.split('/').any { it == ".." }) return null
            val base = root.canonicalFile
            val file = File(base, path).canonicalFile
            return file.takeIf { it.isFile && it.toPath().startsWith(base.toPath()) && it != base }
        }
    }
}
