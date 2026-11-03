package com.scorchsoft.ssmarkdown.preview

import android.graphics.Color
import android.print.PrintAttributes
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * edge-cases "PDF prints flush against the left edge of the paper": the
 * WebView's print adapter lays the page out inside the print attributes'
 * margins and ignores the stylesheet's `@page` margins, and "Save as PDF"
 * reports no margins at all. The adapter the dialog drives therefore lays
 * the page out with the ux-spec margins (20 mm, 15 mm at the foot) itself.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewPrinterTest {
    private fun attributes(margins: PrintAttributes.Margins): PrintAttributes =
        PrintAttributes
            .Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(margins)
            .build()

    @Test
    fun `Save as PDF with no margins still lays the page out 20 mm in, 15 mm at the foot`() {
        val laidOut = PreviewPrinter.withPageMargins(attributes(PrintAttributes.Margins.NO_MARGINS))

        val margins = laidOut.minMargins!!
        assertEquals(787, margins.leftMils)
        assertEquals(787, margins.topMils)
        assertEquals(787, margins.rightMils)
        assertEquals(591, margins.bottomMils)
        // Everything else the dialog chose is passed through untouched.
        assertSame(PrintAttributes.MediaSize.ISO_A4, laidOut.mediaSize)
        assertEquals(PrintAttributes.COLOR_MODE_COLOR, laidOut.colorMode)
        assertEquals(300, laidOut.resolution!!.horizontalDpi)
    }

    @Test
    fun `a printer that needs wider margins keeps them`() {
        val laidOut = PreviewPrinter.withPageMargins(attributes(PrintAttributes.Margins(1_000, 500, 1_000, 900)))

        val margins = laidOut.minMargins!!
        assertEquals(1_000, margins.leftMils)
        assertEquals(787, margins.topMils)
        assertEquals(1_000, margins.rightMils)
        assertEquals(900, margins.bottomMils)
    }

    /**
     * edge-cases "PDF margins print dark in a dark theme": WebView paints the
     * page margins in its own background colour, so a job prints on white
     * whatever the theme, and the screen gets its colour back afterwards.
     */
    @Test
    fun `a dark theme prints on white paper and gets its colour back after the job`() {
        val web = WebView(ApplicationProvider.getApplicationContext())
        val dark = Color.rgb(0x1c, 0x1c, 0x1e)
        web.setBackgroundColor(dark)
        var finished = 0
        val done = PreviewPrinter.onWhitePaper(web, dark) { finished++ }
        assertEquals(Color.WHITE, shadowOf(web).backgroundColor)
        assertEquals(0, finished)
        done()
        assertEquals(dark, shadowOf(web).backgroundColor)
        assertEquals(1, finished)
    }
}
