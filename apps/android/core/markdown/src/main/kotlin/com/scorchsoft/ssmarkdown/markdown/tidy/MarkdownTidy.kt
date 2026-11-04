package com.scorchsoft.ssmarkdown.markdown.tidy

/**
 * Tidy: deterministic clean-ups that never change how the document
 * renders (markdown-it with CommonMark, GFM and the Preview's plugins),
 * except that runs of extra blank lines collapse, which Preview draws as
 * empty space.
 *
 * The rules are [TidyRule]'s. Code, maths, HTML, front matter, tables and
 * anything Tidy cannot read for certain are left exactly as written; when
 * in doubt a rule does nothing. Every plan is checked before it is
 * returned ([isFaithful]), and a plan that fails the check, or any input
 * that trips the scanner, gives an empty plan rather than a wrong one.
 * Files with carriage returns are left alone. One pass is linear in the
 * text, so a 10,000-line file or a 200 KB line costs milliseconds.
 */
public object MarkdownTidy {
    /** What tidying [text] would change. Never throws; an empty plan means "already tidy" or "not safe to". */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A bug must never cost the user their document.
    public fun plan(
        text: String,
        options: TidyOptions = TidyOptions(),
    ): TidyPlan {
        if (text.isEmpty() || text.contains('\r')) return TidyPlan.EMPTY
        return try {
            planned(text, options)
        } catch (e: RuntimeException) {
            TidyPlan.EMPTY
        } catch (e: StackOverflowError) {
            TidyPlan.EMPTY
        }
    }

    private fun planned(
        text: String,
        options: TidyOptions,
    ): TidyPlan {
        val (plan, fence) = unguarded(text, options) ?: return TidyPlan.EMPTY
        if (plan.isEmpty) return TidyPlan.EMPTY
        return if (isFaithful(text, plan.applyTo(text), fence)) plan else TidyPlan.EMPTY
    }

    /** The plan before the safety check, with the closing fence it appends; null when two edits overlap. For tests. */
    internal fun unguarded(
        text: String,
        options: TidyOptions = TidyOptions(),
    ): Pair<TidyPlan, String?>? {
        val pass = TidyPass(text, options).run()
        val edits = merged(pass.edits) ?: return null
        return TidyPlan(edits, pass.counts.toMap()) to pass.appendedFence
    }

    /** Sorts [edits] and joins those that touch; null if two overlap, which no rule should ever cause. */
    private fun merged(edits: List<TidyEdit>): List<TidyEdit>? {
        val out = ArrayList<TidyEdit>(edits.size)
        for (edit in edits.sortedWith(compareBy({ it.start }, { it.end }))) {
            val last = out.lastOrNull()
            when {
                last == null || edit.start > last.end -> {
                    out += edit
                }

                edit.start == last.end -> {
                    out[out.size - 1] =
                        TidyEdit(last.start, edit.end, last.replacement + edit.replacement)
                }

                else -> {
                    return null
                }
            }
        }
        return out
    }

    /**
     * The safety check: the text's non-blank lines, with what Tidy may
     * change normalised away (trailing spaces, the spaces after a heading's
     * `#`, list numbers, the appended closing fence), must be the same
     * before and after.
     */
    internal fun isFaithful(
        before: String,
        after: String,
        appendedFence: String?,
    ): Boolean {
        val old = normalised(before)
        val new = normalised(after).toMutableList()
        if (appendedFence != null && new.lastOrNull() == normalise(appendedFence)) new.removeAt(new.size - 1)
        return old == new
    }

    private fun normalised(text: String): List<String> =
        text.split('\n').filterNot { TidyGrammar.isBlank(it) }.map { normalise(it) }

    private val HEADING_GAP = Regex("^( {0,3}#{1,6}) +")
    private val NUMBER = Regex("^( *)\\d{1,9}([.)])(?=[ \\t]|$)")

    private fun normalise(line: String): String {
        val trimmed = line.trimEnd(' ', '\t')
        return NUMBER.replaceFirst(HEADING_GAP.replaceFirst(trimmed, "$1 "), "$1#$2")
    }
}
