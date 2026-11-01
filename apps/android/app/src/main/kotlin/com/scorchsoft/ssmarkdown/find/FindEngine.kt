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
