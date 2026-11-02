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
            AppendPosition.Top -> Insertion(0, if (existing.isEmpty()) body else body + "\n")
        }
    }

    /** A blank line between the old text and the new, without doubling one that is already there. */
    private fun separatorAfter(existing: CharSequence): String =
        when {
            existing.isEmpty() || existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
}
