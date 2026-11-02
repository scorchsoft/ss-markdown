package com.scorchsoft.ssmarkdown.files.saf

/**
 * The name "Save as…" and "Create" prefill: the document's first heading,
 * made safe for any provider's file system, with `.md` on the end.
 * "Untitled.md" when there is no heading worth using.
 */
object SuggestedFileName {
    private const val MAX_STEM_CHARS = 60
    private const val LINES_SCANNED = 200
    private const val FALLBACK = "Untitled"
    private val ATX = Regex("^ {0,3}#{1,6}[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val SETEXT_UNDERLINE = Regex("^ {0,3}(=+|-+)[ \\t]*$")
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val INLINE_MARKS = Regex("[*_`~\\[\\]]")
    private val SPACES = Regex("\\s+")

    /** A file name for [text]. */
    fun forText(text: CharSequence): String = "${stem(text)}.md"

    private fun stem(text: CharSequence): String {
        val lines = text.lineSequence().take(LINES_SCANNED).toList()
        val heading = firstHeading(lines) ?: return FALLBACK
        val cleaned =
            heading
                .replace(INLINE_MARKS, "")
                .replace(UNSAFE, " ")
                .replace(SPACES, " ")
                .trim()
                .trim('.')
                .take(MAX_STEM_CHARS)
                .trim()
        return cleaned.ifEmpty { FALLBACK }
    }

    @Suppress("ReturnCount") // The first ATX or setext heading wins.
    private fun firstHeading(lines: List<String>): String? {
        var inFence = false
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            if (inFence) return@forEachIndexed
            ATX.find(line)?.let { return it.groupValues[1] }
            val next = lines.getOrNull(index + 1)
            if (line.isNotBlank() && next != null && SETEXT_UNDERLINE.matches(next)) return line.trim()
        }
        return null
    }
}
