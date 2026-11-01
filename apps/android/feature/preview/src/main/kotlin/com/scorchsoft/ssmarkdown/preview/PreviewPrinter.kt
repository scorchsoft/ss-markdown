package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import java.util.Locale

/**
 * Opens the system print dialog for the preview, so "Save as PDF" and real
 * printers both work (docs/ux-spec.md, "PDF"). The page's print stylesheet
 * fixes the typography in physical units; this only picks the paper.
 */
internal object PreviewPrinter {
    fun print(
        context: Context,
        webView: WebView,
        jobName: String,
        onFinished: () -> Unit,
    ) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return onFinished()
        val adapter = FinishingAdapter(webView.createPrintDocumentAdapter(jobName), onFinished)
        val attributes =
            PrintAttributes
                .Builder()
                .setMediaSize(paperFor(Locale.getDefault()))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        manager.print(jobName, adapter, attributes)
    }

    /** US Letter in the US and Canada, A4 everywhere else. */
    fun paperFor(locale: Locale): PrintAttributes.MediaSize =
        if (locale.country in
            setOf("US", "CA")
        ) {
            PrintAttributes.MediaSize.NA_LETTER
        } else {
            PrintAttributes.MediaSize.ISO_A4
        }

    /** Delegates to WebView's adapter and tells the preview when the job is done. */
    private class FinishingAdapter(
        private val inner: PrintDocumentAdapter,
        private val onFinished: () -> Unit,
    ) : PrintDocumentAdapter() {
        override fun onStart() = inner.onStart()

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) = inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) = inner.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            inner.onFinish()
            onFinished()
        }
    }
}
