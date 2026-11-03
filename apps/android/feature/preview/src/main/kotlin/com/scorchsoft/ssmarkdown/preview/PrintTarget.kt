package com.scorchsoft.ssmarkdown.preview

/** How a print request ended. */
enum class PrintOutcome {
    /** The system print dialog opened, for the document that was asked for. */
    Opened,

    /** The page was given a document before it was ready: nothing was printed. */
    DocumentChanged,

    /** The page was destroyed, or could not open the dialog. */
    PageGone,

    /** The page did not finish rendering in time. */
    TimedOut,

    /** A newer print request replaced this one. */
    Superseded,

    /** The caller stopped waiting. */
    Cancelled,
}

/**
 * "Copy formatted text": [html] with inline light-theme styles only, for apps
 * that paste formatting, and [text], the same words without Markdown symbols,
 * for apps that paste text. [truncated] when the page stopped early to keep
 * both within the clipboard's budget.
 */
data class FormattedCopy(
    val html: String,
    val text: String,
    val truncated: Boolean = false,
)

/**
 * What can print the Preview, or copy it as formatted text: [PreviewView], or
 * a test's stand-in. The host asks only a target that [isOnScreen], because a
 * page that is not drawn (Code view, another window's stopped Activity) may
 * never finish rendering.
 */
interface PrintTarget {
    /** Attached to a visible window and laid out: the page is running. */
    val isOnScreen: Boolean

    /**
     * Renders every block of the document the page holds now, then opens the
     * system print dialog. Ends early, opening nothing, if the page is given
     * a document or destroyed first, or does not answer in time.
     */
    suspend fun print(jobName: String): PrintOutcome

    /**
     * Source lines `[fromLine, toLine)` of the document the page holds now, as
     * formatted text within [maxChars] (HTML and plain text together), or null
     * when the page cannot give it. A target that cannot copy answers null.
     */
    suspend fun exportFormatted(
        fromLine: Int,
        toLine: Int,
        maxChars: Int,
    ): FormattedCopy? = null
}
