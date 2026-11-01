package com.scorchsoft.ssmarkdown.core.perf

import android.os.Trace

/**
 * The few named trace sections the emulator benchmarks measure
 * (`:benchmark`, docs/performance-techniques.md "Emulator measurements").
 * Macrobenchmark's `TraceSectionMetric` reads them from a Perfetto trace of
 * the benchmark build; on a phone that is not being traced each call is one
 * cheap `Trace.isEnabled()` check, so they stay in the shipped code.
 *
 * Every call is guarded, because host-JVM unit tests run against the
 * android.jar stubs, where any `android.os.Trace` call throws: there the
 * sections are simply off.
 */
public object PerfTrace {
    /** One keystroke's edit committed to the document (`EditingSession.commitText`). */
    public const val COMMIT: String = "SSM:commit"

    /** A document opened and bound to the editor, from the request to the editable buffer. */
    public const val OPEN: String = "SSM:open"

    /** A host edit sent to the Preview until the page reports it rendered. */
    public const val PREVIEW_REFRESH: String = "SSM:previewRefresh"

    /** False on the host JVM, where android.jar's stubs throw. */
    private val available: Boolean = runCatching { Trace.isEnabled() }.isSuccess

    /** Whether a trace is being recorded now. */
    public val isTracing: Boolean get() = available && Trace.isEnabled()

    /** Runs [block] inside a synchronous section called [name] on this thread. */
    public inline fun <T> section(
        name: String,
        block: () -> T,
    ): T {
        if (!isTracing) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Starts an asynchronous section [name] that [endAsync] with the same [cookie] closes, on any thread. */
    public fun beginAsync(
        name: String,
        cookie: Int,
    ) {
        if (isTracing) Trace.beginAsyncSection(name, cookie)
    }

    /** Closes the asynchronous section [name] opened with [cookie]. */
    public fun endAsync(
        name: String,
        cookie: Int,
    ) {
        if (isTracing) Trace.endAsyncSection(name, cookie)
    }
}
