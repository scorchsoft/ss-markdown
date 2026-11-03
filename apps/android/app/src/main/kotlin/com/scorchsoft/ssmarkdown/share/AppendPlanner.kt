package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.settings.AppendPosition
import com.scorchsoft.ssmarkdown.settings.AppendPrefix

/**
 * One insertion: [text] at [offset].
 */
data class Insertion(
    val offset: Int,
    val text: String,
)

/**
 * Where shared text goes in a file, and with what around it (ux-spec
 * "Share into SS Markdown"). Pure, so Bottom/Top and both prefixes are
 * unit tests. The rest of the file is never touched: only one insertion.
 */
object AppendPlanner {
    /**
     * The insertion that adds [shared] to [existing].
     *
     * @param dateHeading the "### <date time>" text, used only with [AppendPrefix.DateHeading].
     */
    fun plan(
        existing: CharSequence,
        shared: String,
        position: AppendPosition,
        prefix: AppendPrefix,
        dateHeading: String,
    ): Insertion {
        val heading = if (prefix == AppendPrefix.DateHeading) "### $dateHeading\n\n" else ""
        val body = heading + shared.trimEnd('\n') + "\n"
        return when (position) {
            AppendPosition.Bottom -> Insertion(existing.length, separatorAfter(existing) + body)
            AppendPosition.Top -> Insertion(TOP, if (existing.isEmpty()) body else body + "\n")
        }
    }

    /**
     * True when [shared] already sits where [plan] would put it in
     * [existing]: on lines of its own, within [WINDOW] chars past its own
     * length of the end (Bottom) or of the insertion point (Top, which is
     * the start of the file: front matter included, as [plan] inserts).
     * Line endings are normalised (CRLF to LF) and blank space around the
     * text is ignored. Reads only that window, so a 20 MB file costs a few
     * hundred chars. Blank text is never a duplicate.
     */
    fun alreadyThere(
        existing: CharSequence,
        shared: String,
        position: AppendPosition,
    ): Boolean {
        val needle = normalise(shared).trim()
        if (needle.isEmpty()) return false
        // A CRLF file holds one more char per line than the normalised text.
        val span = needle.length + needle.count { it == '\n' } + 1 + WINDOW
        val (start, end) =
            when (position) {
                AppendPosition.Bottom -> (existing.length - span).coerceAtLeast(0) to existing.length
                AppendPosition.Top -> TOP to (TOP + span).coerceAtMost(existing.length)
            }
        if (end - start < needle.length) return false
        val window = normalise(existing.subSequence(start, end).toString())
        var at = window.indexOf(needle)
        while (at >= 0) {
            if (ownLines(window, at, at + needle.length)) return true
            at = window.indexOf(needle, at + 1)
        }
        return false
    }

    /** True when only blank space shares a line with `[from, to)` of [text]. */
    private fun ownLines(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        val lineStart = text.lastIndexOf('\n', from - 1) + 1
        val lineEnd = text.indexOf('\n', to).let { if (it < 0) text.length else it }
        return text.substring(lineStart, from).isBlank() && text.substring(to, lineEnd).isBlank()
    }

    private fun normalise(text: String): String = text.replace("\r\n", "\n")

    /** Where a Top append goes. */
    private const val TOP = 0

    /** How far past the shared text's own length the check looks: room for a date heading and blank lines. */
    private const val WINDOW = 512

    /** A blank line between the old text and the new, without doubling one that is already there. */
    private fun separatorAfter(existing: CharSequence): String =
        when {
            existing.isEmpty() || existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
}
