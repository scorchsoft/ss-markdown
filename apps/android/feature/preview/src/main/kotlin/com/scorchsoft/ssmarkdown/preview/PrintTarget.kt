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
 * What can print the Preview: [PreviewView], or a test's stand-in. The host
 * prints only through a target that [isOnScreen], because a page that is not
 * drawn (Code view, another window's stopped Activity) may never finish
 * rendering.
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
}
