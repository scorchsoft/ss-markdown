package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.ui.strip.StripKey

/**
 * The formatting strip in Preview (ux-spec "Editing in Preview"): the
 * same keys, sent to the page as format actions so they apply to the
 * rendered selection's source range in the document's own style.
 */
object PreviewStripKeys {
    /** The format action behind [key], or null for keys Preview handles otherwise (undo, Done) or not at all. */
    fun actionFor(
        key: StripKey,
        inHeading: Boolean,
    ): FormatAction? =
        when (key) {
            // Heading toggles: a heading becomes a paragraph, anything else a second-level heading.
            StripKey.Heading, StripKey.BlockStyle -> if (inHeading) FormatAction.PARAGRAPH else FormatAction.HEADING_2

            StripKey.Bold -> FormatAction.BOLD

            StripKey.Italic -> FormatAction.ITALIC

            StripKey.Code -> FormatAction.CODE

            StripKey.Link -> FormatAction.LINK

            StripKey.BulletList -> FormatAction.BULLET

            StripKey.Task -> FormatAction.TASK

            StripKey.Quote -> FormatAction.QUOTE

            else -> null
        }
}
