package com.scorchsoft.ssmarkdown.find

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Match case · Whole word · `.*` (ux-spec "Find & replace"). */
data class FindOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** One match: chars `[start, end)` of the searched text. */
data class FindMatch(
    val start: Int,
    val end: Int,
)

/** What a search found, or why it could not run. */
sealed interface FindResult {
    /** [matches] in document order; [capped] when the search stopped at [FindEngine.MAX_MATCHES]. */
    data class Found(
        val matches: List<FindMatch>,
        val capped: Boolean,
    ) : FindResult

    /** The `.*` pattern does not compile; the bar shows it inline instead of failing. */
    data object InvalidPattern : FindResult
}

/**
 * [text] that gives up when [cancelled] says so: the regex engine reads
 * through `charAt`, and a scan with no match is one long `Matcher.find`
 * call with nowhere else to check. Polled every [POLL_EVERY] reads, so the
 * check costs one decrement per char (performance review PERF-8).
 */
class CancellableText(
    private val text: CharSequence,
    private val cancelled: () -> Boolean,
) : CharSequence {
    private var untilPoll = POLL_EVERY

    /** Chars read so far, for the bounded-work tests. */
    var reads: Long = 0
        private set

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        reads++
        if (--untilPoll == 0) {
            untilPoll = POLL_EVERY
            if (cancelled()) throw SearchCancelled()
        }
        return text[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    /** Thrown out of the matcher when the search is no longer wanted. */
    class SearchCancelled : RuntimeException("search cancelled")

    private companion object {
        const val POLL_EVERY = 16_384
    }
}

/**
 * Searching and replacing, as pure functions over a [CharSequence] (a
 * piece-tree snapshot in the app), so every option combination is a unit
 * test. Plain queries are quoted into a pattern, so one code path serves
 * all three modes and "Whole word" means the same thing in each.
 */
object FindEngine {
    /** The most matches counted; "1 of 10000+" beyond it keeps a pathological query cheap. */
    const val MAX_MATCHES: Int = 10_000

    /** Every match of [query] in [text]. An empty query finds nothing. */
    @Suppress("ReturnCount") // Empty query, bad pattern, cap and done.
    fun find(
        text: CharSequence,
        query: String,
        options: FindOptions,
    ): FindResult {
        if (query.isEmpty()) return FindResult.Found(emptyList(), capped = false)
        val pattern = compile(query, options) ?: return FindResult.InvalidPattern
        val matcher = pattern.matcher(text)
        val matches = ArrayList<FindMatch>()
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) continue // An empty regex match would loop and replace nothing.
            matches += FindMatch(matcher.start(), matcher.end())
            if (matches.size == MAX_MATCHES) return FindResult.Found(matches, capped = true)
        }
        return FindResult.Found(matches, capped = false)
    }

    /**
     * The text that replaces [match] in [text]. With `.*`, `$1` refers to
     * groups as in Java; otherwise [replacement] is literal.
     */
    @Suppress("ReturnCount") // Literal, bad pattern and matched.
    fun replacementFor(
        text: CharSequence,
        match: FindMatch,
        query: String,
        replacement: String,
        options: FindOptions,
    ): String {
        if (!options.regex) return replacement
        val pattern = compile(query, options) ?: return replacement
        val matcher = pattern.matcher(text.subSequence(match.start, match.end))
        return if (matcher.matches()) {
            val out = StringBuffer()
            matcher.appendReplacement(out, replacement)
            out.toString()
        } else {
            replacement
        }
    }

    private fun compile(
        query: String,
        options: FindOptions,
    ): Pattern? {
        val body = if (options.regex) query else Pattern.quote(query)
        val bounded = if (options.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        val flags = if (options.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            Pattern.compile(bounded, flags or Pattern.MULTILINE)
        } catch (_: PatternSyntaxException) {
            null
        }
    }
}
