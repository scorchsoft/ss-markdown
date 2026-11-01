package com.scorchsoft.ssmarkdown.about

/**
 * One bundled component on the Open-source licences screen.
 *
 * @property source where it comes from, shown as text (the app is offline; nothing is fetched).
 * @property textAsset the APK asset holding the full licence text, or null when the row has none.
 *   Shipping the text, not just its name, is what MIT, BSD, Apache and OFL all ask of a binary.
 */
data class Licence(
    val name: String,
    val licence: String,
    val source: String,
    val textAsset: String? = null,
)

/**
 * Reads `assets/licences.txt`, a hand-kept list in step with NOTICE: one
 * `name | licence | source | text asset` per line, `#` for comments. The
 * fourth field is optional so a row can be listed before its text exists,
 * but `LicenceTextsTest` fails the build if any shipped row lacks one.
 */
object Licences {
    /** The entries in [text]; malformed lines are skipped rather than failing the screen. */
    fun parse(text: String): List<Licence> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                when (parts.size) {
                    FIELDS -> Licence(parts[0], parts[1], parts[2])
                    FIELDS + 1 -> Licence(parts[0], parts[1], parts[2], parts[FIELDS].ifEmpty { null })
                    else -> null
                }
            }.toList()

    /**
     * A licence text split into paragraphs at blank lines, so the text
     * screen can lay out an 85 KB notice lazily rather than as one block.
     * Hard-wrapped lines inside a paragraph are kept as written: licence
     * texts are legal documents and are shown exactly as shipped.
     */
    fun paragraphs(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .split(Regex("\n[ \t]*\n"))
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }

    private const val FIELDS = 3
}
