package com.scorchsoft.ssmarkdown.preview

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Why a picture beside the document can or cannot be shown, as the page's
 * placeholder words it (docs/ux-spec.md, "Pictures"). [wire] is the name on
 * the bridge (web/src/core/imageStatus.ts).
 */
enum class ImageStatus(
    val wire: String,
) {
    /** Found, an image, and small enough. */
    OK("ok"),

    /** Nothing by that name beside the document, or the path leaves its folder (SEC-9). */
    NOT_FOUND("notFound"),

    /** The app cannot see the document's folder: the user can grant it ("Allow access"). */
    NO_ACCESS("noAccess"),

    /** Found, but not a picture. */
    NOT_IMAGE("notImage"),

    /** Found, but larger than a picture the preview loads (SEC-8). */
    TOO_LARGE("tooLarge"),
}

/**
 * Opens files next to the document (relative images), for the page's `/doc/`
 * paths, the viewer and Export images. The host decides what is reachable,
 * typically only files inside the folder the user granted; returning null
 * leaves a placeholder that says why ([status]).
 *
 * A relative path is the one written in the Markdown (`../img/a%20b.png`),
 * percent escapes and all.
 */
fun interface DocumentResourceResolver {
    fun open(relativePath: String): InputStream?

    /** Why [relativePath] can or cannot be shown. Called off the main thread; may touch storage. */
    fun status(relativePath: String): ImageStatus = open(relativePath)?.use { ImageStatus.OK } ?: ImageStatus.NOT_FOUND
}

/**
 * The page's names for pictures beside the document (web/src/core/imagePaths.ts):
 * the number of folders climbed, then the path, because a URL parser would
 * fold `..` away. `1/img/a.png` is `../img/a.png`.
 */
object DocPaths {
    /** The most folders a path may climb; anything more is junk from a hostile page. */
    private const val MAX_CLIMBS = 64

    /**
     * The relative path [key] names, percent-encoded like the Markdown it
     * came from; null for anything malformed. A [decoded] key (from
     * `Uri.getPath`, in the path handler) is encoded again, so a file called
     * `50%.png` is not decoded twice.
     */
    fun relative(
        key: String,
        decoded: Boolean = false,
    ): String? {
        val climbs = key.substringBefore('/', "").toIntOrNull()?.takeIf { it in 0..MAX_CLIMBS }
        val parts =
            key
                .substringAfter('/', "")
                .split('/')
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() && it.none { part -> part == "." || part == ".." } }
        if (climbs == null || parts == null) return null
        val path = if (decoded) parts.joinToString("/") { Uri.encode(it) } else parts.joinToString("/")
        return "../".repeat(climbs) + path
    }
}

/** Serves `/doc/<climbs>/<path>` from a [DocumentResourceResolver] chosen at request time. */
internal class DocumentPathHandler(
    private val resolver: () -> DocumentResourceResolver?,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        // Only images; the resolver keeps the path inside the granted folder (SEC-9).
        val mime =
            MimeTypeMap
                .getSingleton()
                .getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
                ?.takeIf { it.startsWith("image/") }
        val relative = DocPaths.relative(path, decoded = true)
        val stream = if (mime != null && relative != null) resolver()?.open(relative) else null
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
