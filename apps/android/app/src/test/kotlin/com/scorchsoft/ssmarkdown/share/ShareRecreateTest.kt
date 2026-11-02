package com.scorchsoft.ssmarkdown.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * The share sheet is rebuilt by anything that recreates its Activity: a
 * rotation, a dark-mode switch, a window resize, a process death while it
 * waits. Its state lived in a per-Activity view model, and the shared text
 * was read only on a first create, so the rebuilt sheet came back empty,
 * with Append still enabled: the shared text was lost, and Append wrote a
 * blank append (a date heading, blank lines) into the chosen file.
 */
@RunWith(RobolectricTestRunner::class)
class ShareRecreateTest {
    private fun send(text: String) =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    @Test
    fun `a recreated share sheet still holds the shared text`() {
        val controller = Robolectric.buildActivity(ShareActivity::class.java, send("Quote to keep")).setup()
        ShadowLooper.idleMainLooper()
        assertEquals("Quote to keep", controller.get().sheetState.text)

        controller.recreate()
        ShadowLooper.idleMainLooper()

        assertEquals("Quote to keep", controller.get().sheetState.text)
    }
}
