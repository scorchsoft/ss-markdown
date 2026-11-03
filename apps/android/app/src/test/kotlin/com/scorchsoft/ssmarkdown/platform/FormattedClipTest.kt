package com.scorchsoft.ssmarkdown.platform

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The clip "Copy formatted text" leaves: one item, MIME type `text/html`,
 * holding the HTML for apps that paste formatting and the plain-text twin
 * (no Markdown symbols) for apps that paste text.
 */
@RunWith(RobolectricTestRunner::class)
class FormattedClipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the clip carries HTML and its plain-text twin`() {
        val copy = FormattedCopy("<h1 style=\"font-size:20px\">Title</h1><ul><li>One</li></ul>", "Title\n\n• One")

        runBlocking { MediaActions(context, Dispatchers.Unconfined).copyFormatted(copy) }

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: error("nothing copied")
        assertEquals(1, clip.itemCount)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
        assertEquals(copy.html, clip.getItemAt(0).htmlText)
        assertEquals("Title\n\n• One", clip.getItemAt(0).text.toString())
    }
}
