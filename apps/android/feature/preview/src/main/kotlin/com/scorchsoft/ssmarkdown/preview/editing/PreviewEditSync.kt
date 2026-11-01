package com.scorchsoft.ssmarkdown.preview.editing

import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.LineChange
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit

/**
 * Kotlin's half of the edit protocol: revisions, and rebasing of edits made in
 * Preview over changes made here that the page had not seen yet. The page's
 * half is `web/src/core/editSync.ts`; together they are a two-party Jupiter
 * system (Nichols et al., 1995).
 *
 * - Every change gets the next revision. The page hears of each one exactly
 *   once: as an [HostMessage.Edit] for a change made here, or as an
 *   [HostMessage.Ack] for one of its own.
 * - A page edit carries `baseRev`, the last revision the page had seen. Host
 *   changes after it are ones the page did not know about; the page edit is
 *   transformed over them, and they over it, so typing in Preview while the
 *   Code pane (or undo, or a format command) changes the text loses nothing.
 *   The old behaviour, rejecting a stale edit and reloading, is gone.
 * - Only when rebasing is impossible (the page claims a revision that never
 *   existed, or its edits do not fit the text) does [onPageEdits] answer
 *   [PageEditOutcome.Resync]; the host then reloads the page from its text.
 *
 * Pure Kotlin with no Android types, so it is tested on the JVM.
 */
class PreviewEditSync(
    /** Host changes the page has not acknowledged seeing, beyond which it resyncs instead. */
    private val maxUnseen: Int = DEFAULT_MAX_UNSEEN,
) {
    /** The current revision: the number the last change was given. */
    var rev: Int = 0
        private set

    private class Unseen(
        val rev: Int,
        var edits: List<TextEdit>,
    )

    /** Host changes sent to the page that it has not built on yet, transformed as page edits arrive. */
    private val unseen = ArrayDeque<Unseen>()

    /** The newest change dropped from [unseen] for space; a page behind it cannot be rebased. */
    private var droppedThrough = 0

    /** A [HostMessage.Load] of [text]: a fresh start for the page at a new revision. */
    fun load(
        text: CharSequence,
        anchorLine: Int? = null,
    ): HostMessage.Load {
        rev++
        return HostMessage.Load(rev, text, anchorLine)
    }

    /**
     * The host changed the text: [edits] (sorted, non-overlapping) were applied
     * to [before]. Returns the [HostMessage.Edit] to send, carrying both line
     * changes and the character edits the page needs to rebase its own.
     */
    fun onHostEdits(
        before: PreviewText,
        edits: List<TextEdit>,
    ): HostMessage.Edit? {
        if (edits.isEmpty()) return null
        require(TextTransform.isValid(edits, before.length)) { "host edits out of order or range: $edits" }
        rev++
        unseen.addLast(Unseen(rev, edits))
        while (unseen.size > maxUnseen) droppedThrough = unseen.removeFirst().rev
        return HostMessage.Edit(rev, lineChanges(before, edits), edits)
    }

    /**
     * Edits from the page (`sourceEdit` or `taskToggle`), made on the text at
     * [baseRev] plus the page's own earlier edits. [length] is the host text's
     * length now, to validate the result.
     */
    fun onPageEdits(
        baseRev: Int,
        edits: List<TextEdit>,
        length: Int,
    ): PageEditOutcome {
        val problem =
            when {
                baseRev > rev || !TextTransform.isValid(edits) -> "bad base $baseRev or edits $edits"
                baseRev < droppedThrough -> "page is at $baseRev, history kept from $droppedThrough"
                else -> null
            }
        if (problem != null) return PageEditOutcome.Resync(problem)
        while (unseen.isNotEmpty() && unseen.first().rev <= baseRev) unseen.removeFirst()
        var incoming = edits
        for (u in unseen) {
            val t = TextTransform.transform(u.edits, incoming)
            u.edits = t.host
            incoming = t.page
        }
        return if (TextTransform.isValid(incoming, length)) {
            rev++
            PageEditOutcome.Apply(incoming, HostMessage.Ack(rev))
        } else {
            PageEditOutcome.Resync("edits outside the text")
        }
    }

    /** Maps an offset the page took at [baseRev] (a selection) onto the host's text now. */
    fun mapFromPage(
        offset: Int,
        baseRev: Int,
    ): Int? {
        if (baseRev > rev || baseRev < droppedThrough) return null
        return unseen.filter { it.rev > baseRev }.fold(offset) { at, u -> TextTransform.mapOffset(at, u.edits) }
    }

    companion object {
        /** Enough for any burst of typing in Code while one page message is in flight. */
        const val DEFAULT_MAX_UNSEEN: Int = 1024

        /**
         * Line changes for [edits] on [before], in descending order so each
         * applies to the result of the previous one. Edits that share a line
         * travel as one change, so a line is never rewritten from stale text.
         */
        fun lineChanges(
            before: PreviewText,
            edits: List<TextEdit>,
        ): List<LineChange> {
            val groups = ArrayList<MutableList<TextEdit>>()
            var lastLine = -1
            for (e in edits) {
                val first = before.lineOf(e.from)
                val group = groups.lastOrNull()
                if (group != null && first <= lastLine) group += e else groups += mutableListOf(e)
                lastLine = before.lineOf(e.to)
            }
            return groups.asReversed().map { group ->
                val fromLine = before.lineOf(group.first().from)
                val toLine = before.lineOf(group.last().to) + 1
                val start = before.lineStart(fromLine)
                val end = before.lineEnd(toLine - 1)
                val text = StringBuilder(before.slice(start, end))
                for (e in group.asReversed()) text.replace(e.from - start, e.to - start, e.insert)
                LineChange(fromLine, toLine, text.split('\n'))
            }
        }
    }
}

/** What to do with edits that arrived from the page. */
sealed interface PageEditOutcome {
    /** Apply [edits] (rebased onto the host's text now, sorted), then send [ack]. */
    data class Apply(
        val edits: List<TextEdit>,
        val ack: HostMessage.Ack,
    ) : PageEditOutcome

    /** Rebasing is impossible: send a fresh [PreviewEditSync.load]. The page's edits already left it. */
    data class Resync(
        val reason: String,
    ) : PageEditOutcome
}
