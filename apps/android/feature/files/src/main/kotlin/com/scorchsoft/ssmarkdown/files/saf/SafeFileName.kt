package com.scorchsoft.ssmarkdown.files.saf

import java.io.File

/**
 * Makes a name from outside the app safe to use as one file name inside
 * an app directory (SEC-1).
 *
 * A provider's `DISPLAY_NAME` is whatever the provider says, so a hostile
 * app can answer `../files/settings.xml`. Every name that reaches
 * `File(dir, name)` goes through here first: the history export, the
 * share-as-file copy, the media cache.
 */
object SafeFileName {
    /** Path separators, Windows-reserved characters, NUL and every control character. */
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u007f]")

    /** Long enough for any real name, short enough for every file system's 255-byte limit in UTF-8. */
    const val MAX_CHARS: Int = 80

    /** What an empty or all-dots name becomes. */
    const val FALLBACK: String = "untitled"

    /**
     * [name] with every unsafe character replaced by `-`, leading dots and
     * surrounding spaces removed (so it is never `.`, `..` or hidden), and
     * cut to [MAX_CHARS] chars without splitting a surrogate pair. Never empty.
     */
    fun of(name: String?): String {
        val cleaned =
            name
                .orEmpty()
                .replace(UNSAFE, "-")
                .trim()
                .trimStart('.')
                .trim()
        val cut = if (cleaned.length <= MAX_CHARS) cleaned else cleaned.take(safeCut(cleaned))
        return cut.trimEnd().ifEmpty { FALLBACK }
    }

    /**
     * `File(directory, of(name))`, checked: the result is always a direct
     * child of [directory], whatever [name] was.
     *
     * @throws IllegalArgumentException if it somehow is not, which [of] should make impossible.
     */
    fun inside(
        directory: File,
        name: String?,
    ): File {
        val file = File(directory, of(name))
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "name escapes its directory" }
        return file
    }

    private fun safeCut(text: String): Int =
        if (Character.isHighSurrogate(text[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
}
