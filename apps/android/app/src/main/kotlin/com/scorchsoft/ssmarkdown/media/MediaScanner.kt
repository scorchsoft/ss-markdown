package com.scorchsoft.ssmarkdown.media

/** What a [MediaItem] is: a diagram the preview draws, or a picture the document embeds. */
enum class MediaType { Diagram, Picture }

/**
 * One image in the document, in document order.
 *
 * @property line the source line it starts on: a diagram's opening fence, a picture's line.
 * @property title a diagram's first meaningful line ("flowchart LR") or its `title`; a picture's alt text or file name.
 * @property source a picture's path or URL as written; null for diagrams.
 */
data class MediaItem(
    val type: MediaType,
    val line: Int,
    val title: String,
    val source: String? = null,
)

/**
 * Finds every diagram and picture in a Markdown text, for the viewer's
 * filmstrip, Export images and the Mermaid sheet. It reads the source,
 * not the page, because the page renders only what is near the screen.
 *
 * Fences are tracked so a picture inside a code block is not a picture,
 * and only ```mermaid fences count as diagrams.
 */
object MediaScanner {
    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})\s*([\w-]*)""")
    private val picture = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val diagramTitle = Regex("""^\s*title\s*:?\s*(.+)$""")

    /** Every diagram and picture in [text], in order. */
    fun scan(text: CharSequence): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        val lines = text.lines()
        var open: String? = null
        var diagram: Int? = null
        var title: String? = null
        lines.forEachIndexed { index, line ->
            val match = fence.find(line)
            when {
                open == null && match != null -> {
                    open = match.groupValues[1]
                    if (match.groupValues[2].equals("mermaid", ignoreCase = true)) diagram = index
                    title = null
                }

                closes(open, match) -> {
                    diagram?.let { items += MediaItem(MediaType.Diagram, it, title ?: DIAGRAM) }
                    open = null
                    diagram = null
                }

                open != null -> {
                    if (diagram != null) title = betterTitle(title, line)
                }

                else -> {
                    picture.findAll(line).forEach { items += pictureAt(index, it) }
                }
            }
        }
        // An unclosed fence still renders to the end of the file.
        diagram?.let { items += MediaItem(MediaType.Diagram, it, title ?: DIAGRAM) }
        return items
    }

    /** A closing fence: the same character, at least as long, with no info string. */
    private fun closes(
        open: String?,
        match: MatchResult?,
    ): Boolean {
        val marker = match?.groupValues?.get(1) ?: return false
        return open != null &&
            marker[0] == open[0] &&
            marker.length >= open.length &&
            match.groupValues[2].isEmpty()
    }

    private fun betterTitle(
        current: String?,
        line: String,
    ): String? {
        val explicit =
            diagramTitle
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.trim()
        return when {
            explicit != null -> explicit
            current == null && line.isNotBlank() && !line.trimStart().startsWith("%%") -> line.trim()
            else -> current
        }
    }

    private fun pictureAt(
        line: Int,
        match: MatchResult,
    ): MediaItem {
        val alt = match.groupValues[1].trim()
        val source = match.groupValues[2]
        val name = source.substringAfterLast('/').substringBefore('?')
        return MediaItem(MediaType.Picture, line, alt.ifEmpty { name }, source)
    }

    /** The title of a diagram with nothing better to go on. */
    const val DIAGRAM: String = "Diagram"
}
