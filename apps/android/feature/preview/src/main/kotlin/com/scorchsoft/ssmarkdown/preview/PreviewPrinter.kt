package com.scorchsoft.ssmarkdown.preview

import android.content.Context
import android.graphics.Color
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
 * fixes the typography in physical units; this picks the paper and the
 * margins.
 *
 * The margins have to come from here. WebView's print adapter lays every
 * page out inside the print attributes' margins and ignores the
 * stylesheet's `@page` margins, and "Save as PDF" reports no margins at all,
 * so without [withPageMargins] the text ran flush to the left edge of the
 * paper (edge-cases "PDF prints flush against the left edge").
 *
 * The paper is white whatever the app's theme. The page's print stylesheet
 * makes the document light, but WebView paints everything outside it (the
 * page margins) in its own background colour, which is the theme's: a dark
 * theme printed dark-grey margins round a white text box (Andrew, 0.2.1,
 * edge-cases "PDF margins print dark in a dark theme"). [print] makes that
 * colour white for the job and puts the screen's colour back afterwards.
 */
internal object PreviewPrinter {
    fun print(
        context: Context,
        webView: WebView,
        jobName: String,
        screenBackground: Int,
        onFinished: () -> Unit,
    ) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return onFinished()
        val finished = onWhitePaper(webView, screenBackground, onFinished)
        val adapter = FinishingAdapter(webView.createPrintDocumentAdapter(jobName), finished)
        val attributes =
            PrintAttributes
                .Builder()
                .setMediaSize(paperFor(Locale.getDefault()))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        manager.print(jobName, adapter, attributes)
    }

    /**
     * Paints [webView]'s background white for printing and returns what to
     * run when the job ends: [screenBackground] back, then [onFinished].
     */
    fun onWhitePaper(
        webView: WebView,
        screenBackground: Int,
        onFinished: () -> Unit,
    ): () -> Unit {
        webView.setBackgroundColor(PAPER)
        return {
            webView.setBackgroundColor(screenBackground)
            onFinished()
        }
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

    /**
     * [attributes] as the dialog chose them, but with page margins of at
     * least 20 mm, and 15 mm at the foot where the page number sits
     * (docs/ux-spec.md, "PDF"). A printer that needs wider margins keeps them.
     */
    fun withPageMargins(attributes: PrintAttributes): PrintAttributes {
        val printer = attributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS
        val margins =
            PrintAttributes.Margins(
                maxOf(printer.leftMils, SIDE_MILS),
                maxOf(printer.topMils, SIDE_MILS),
                maxOf(printer.rightMils, SIDE_MILS),
                maxOf(printer.bottomMils, FOOT_MILS),
            )
        val builder = PrintAttributes.Builder().setMinMargins(margins)
        // Zero means "not chosen yet"; the builder refuses it.
        if (attributes.colorMode != 0) builder.setColorMode(attributes.colorMode)
        attributes.mediaSize?.let(builder::setMediaSize)
        attributes.resolution?.let(builder::setResolution)
        if (attributes.duplexMode != 0) builder.setDuplexMode(attributes.duplexMode)
        return builder.build()
    }

    /** Paper, whatever the theme. */
    const val PAPER = Color.WHITE

    /** 20 mm, the ux-spec page margin, in the thousandths of an inch print attributes use. */
    private const val SIDE_MILS = 787

    /** 15 mm at the foot: the page number sits in it. */
    private const val FOOT_MILS = 591

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
        ) = inner.onLayout(
            oldAttributes?.let(::withPageMargins),
            withPageMargins(newAttributes),
            cancellationSignal,
            callback,
            extras,
        )

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
