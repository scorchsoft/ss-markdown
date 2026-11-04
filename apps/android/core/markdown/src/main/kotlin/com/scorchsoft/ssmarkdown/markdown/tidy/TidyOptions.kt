package com.scorchsoft.ssmarkdown.markdown.tidy

/**
 * The conventions Tidy follows where Markdown leaves a choice (Settings →
 * Tidy; Andrew, 2026-11-04: "pick a convention and have it in the
 * settings"). Both render the same either way; they are matters of taste.
 */
public data class TidyOptions(
    /**
     * True: numbered lists count up from their first number. False: a list
     * whose items all carry the same number (`1. 1. 1.`, a style some write
     * on purpose so items can move freely) is left as written; other lists
     * still count up.
     */
    val countUpRepeatedLists: Boolean = true,
    /** True: a run of blank lines becomes one. False: the spacing is kept (Preview draws it). */
    val collapseBlankLines: Boolean = true,
)
