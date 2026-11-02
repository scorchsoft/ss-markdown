package com.scorchsoft.ssmarkdown.files.memory

/** The memory budget a document opens in (docs/robustness.md Part 2a, "Admission control"). */
enum class Budget {
    /** Everything on. */
    Normal,

    /** Caches capped and live preview off, with a quiet notice. */
    Lean,
}

/** The governor's answer to "may this file open?". */
sealed interface Admission {
    /**
     * Yes, in [budget].
     *
     * @property budget the budget to open in.
     * @property estimatedBytes what the governor expects the document to cost.
     */
    data class Admitted(
        val budget: Budget,
        val estimatedBytes: Long,
    ) : Admission

    /**
     * No: it would not fit. The numbers are for the message "This 180 MB
     * file needs about 400 MB and this phone allows 256 MB", and the UI
     * offers a read-only streamed view instead.
     *
     * @property fileBytes the file's size.
     * @property neededBytes the estimated cost of opening it.
     * @property allowedBytes this app's heap ceiling.
     * @property usableNowBytes what a file may use right now: 70% of the
     *   heap still free with what is already open. When [neededBytes] is
     *   under [allowedBytes] this is the honest reason for the decline.
     */
    data class Declined(
        val fileBytes: Long,
        val neededBytes: Long,
        val allowedBytes: Long,
        val usableNowBytes: Long = allowedBytes,
    ) : Admission
}

/**
 * What the governor sheds under pressure, in this order: the cheapest to
 * rebuild first, and never unsaved work (docs/robustness.md Part 2a,
 * "Under pressure").
 *
 * Only steps that free something in this process are on the ladder. The
 * Preview's diagrams and mounted window live in the WebView's renderer, a
 * separate process the platform reclaims on its own, so the two steps
 * that named them, and shed nothing here, were removed (performance
 * review PERF-G).
 */
enum class ShedStep {
    /** The editor's per-line layouts. */
    LineLayoutCaches,

    /** Tokenizer state beyond the viewport. */
    TokenizerCache,

    /** Clean documents that are not on screen; they reload from disk on demand. */
    InactiveCleanBuffers,
}

/** Something that can give memory back for one [ShedStep]. */
fun interface Shedder {
    /** Releases what [step] covers. Called on the main thread. */
    fun shed(step: ShedStep)
}
