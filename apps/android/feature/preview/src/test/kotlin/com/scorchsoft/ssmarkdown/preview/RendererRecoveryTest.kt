package com.scorchsoft.ssmarkdown.preview

import android.print.PrintAttributes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RendererRecoveryTest {
    @Test
    fun `a crash restarts normally, an out-of-memory kill restarts lean`() {
        val r = RendererRecovery()
        assertEquals(RecoveryAction.Restart(lean = false), r.onRendererGone(0, didCrash = true))
        assertEquals(RecoveryAction.Restart(lean = true), r.onRendererGone(120_000, didCrash = false))
        // Lean sticks for the session.
        assertEquals(RecoveryAction.Restart(lean = true), r.onRendererGone(240_000, didCrash = true))
    }

    @Test
    fun `two deaths within a minute pause the preview until retry`() {
        val r = RendererRecovery()
        assertEquals(RecoveryAction.Restart(false), r.onRendererGone(1_000, true))
        assertEquals(RecoveryAction.Pause, r.onRendererGone(30_000, true))
        r.reset()
        assertEquals(RecoveryAction.Restart(false), r.onRendererGone(31_000, true))
    }

    @Test
    fun `print paper follows the locale`() {
        assertEquals(PrintAttributes.MediaSize.NA_LETTER, PreviewPrinter.paperFor(Locale.US))
        assertEquals(PrintAttributes.MediaSize.NA_LETTER, PreviewPrinter.paperFor(Locale.CANADA))
        assertEquals(PrintAttributes.MediaSize.ISO_A4, PreviewPrinter.paperFor(Locale.UK))
    }
}
