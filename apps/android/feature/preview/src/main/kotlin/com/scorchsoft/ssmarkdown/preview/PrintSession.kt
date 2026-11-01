package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.webkit.WebView

/**
 * One print request at a time: the page is asked to render every block, and
 * the system dialog opens only when it says it is ready, so the PDF never has
 * holes where virtualised sections were.
 */
internal class PrintSession(
    private val context: Context,
    private val onFinished: () -> Unit,
) {
    private var pendingJob: String? = null

    fun request(jobName: String) {
        pendingJob = jobName
    }

    /** The page has rendered everything: open the dialog for the pending job, if any. */
    fun onPageReady(webView: WebView?) {
        val job = pendingJob ?: return
        val view = webView ?: return
        pendingJob = null
        PreviewPrinter.print(context, view, job, onFinished)
    }
}
