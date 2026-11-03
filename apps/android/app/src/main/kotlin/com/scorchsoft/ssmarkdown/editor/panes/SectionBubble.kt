package com.scorchsoft.ssmarkdown.editor.panes

import com.scorchsoft.ssmarkdown.core.ui.components.FastScrollState
import com.scorchsoft.ssmarkdown.document.OutlineTracker

/**
 * Keeps a [FastScrollState]'s bubble on the section under a pane's top
 * line (docs/ux-spec.md, "Fast scroll"): the heading the Navigator's
 * outline has for it, or, where no heading is above the top, the line
 * number when [lineFallback] is set (Code) and no bubble when it is not
 * (Preview). Allocates only when what the bubble says changes.
 */
internal class SectionBubble(
    private val state: FastScrollState,
    private val lineFallback: ((Int) -> String)?,
) {
    private var shownLine = -1

    /** The pane's top is [line] of a document whose outline is [outline]. */
    fun update(
        outline: OutlineTracker?,
        line: Int,
    ) {
        val index = outline?.sectionIndexAt(line) ?: FastScrollState.NO_SECTION
        if (index >= 0) {
            shownLine = -1
            if (index != state.sectionKey ||
                state.section == null
            ) {
                state.setSection(index, outline?.headingAt(index)?.text)
            }
            return
        }
        val label = lineFallback ?: return state.setSection(FastScrollState.NO_SECTION, null)
        if (line == shownLine && state.sectionKey == FastScrollState.NO_SECTION) return
        shownLine = line
        state.setSection(FastScrollState.NO_SECTION, label(line))
    }
}
