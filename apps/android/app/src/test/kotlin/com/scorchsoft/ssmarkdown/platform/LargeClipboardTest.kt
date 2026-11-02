package com.scorchsoft.ssmarkdown.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.MenuItem
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.FileProviderCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * edge-cases "Large clipboard or share" and robustness.md §5: a 5 MB
 * document never goes through Binder as a string. Copy and Share hand it
 * over as a file (a FileProvider URI), and the intent that crosses the
 * process boundary stays tiny, so there is no `TransactionTooLargeException`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LargeClipboardTest {
    @get:Rule val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun freshFileProvider() = FileProviderCache.reset()

    private fun editorWith(text: String): EditorFixture =
        EditorFixture(temp.root, UnconfinedTestDispatcher()).also { it.vm.openDocument(it.file("big.md", text)) }

    @Test
    fun `Copy and Share of a 5 MB document go as a file, never as text`() {
        val f = editorWith(FIVE_MB_TEXT)
        f.vm.onAction(EditorAction.CopyTapped)
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.Share))
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.CopyRichText))

        assertEquals(3, f.events.count { it is EditorEvent.ShareAsFile })
        assertTrue(f.events.none { it is EditorEvent.CopyText || it is EditorEvent.ShareText })
        assertTrue(f.events.none { it is EditorEvent.CopyRichText })
    }

    @Test
    fun `a small document still copies and shares as text`() {
        val f = editorWith("# Small\n")
        f.vm.onAction(EditorAction.CopyTapped)
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.Share))

        assertTrue(f.events.any { it is EditorEvent.CopyText })
        assertTrue(f.events.any { it is EditorEvent.ShareText })
        assertTrue(f.events.none { it is EditorEvent.ShareAsFile })
    }

    @Test
    fun `sharing 5 MB as a file writes it whole and sends only a URI across Binder`() {
        runBlocking { PlatformActions(context, Dispatchers.Unconfined).shareAsFile("big.md", FIVE_MB_TEXT) }

        val written = File(File(context.cacheDir, "shared"), "big.md")
        assertEquals(FIVE_MB_TEXT.length.toLong(), written.length())
        val chooser = shadowOf(context as Application).nextStartedActivity ?: error("nothing was shared")
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        assertNull("the text itself never rides in the intent", send!!.getCharSequenceExtra(Intent.EXTRA_TEXT))
        assertNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        val parcel = Parcel.obtain()
        try {
            chooser.writeToParcel(parcel, 0)
            assertTrue("intent is ${parcel.dataSize()} bytes", parcel.dataSize() < BINDER_SAFE_BYTES)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        val FIVE_MB_TEXT = "Line of a large Markdown document.\n".repeat(5 * 1024 * 1024 / 35)

        /** Far below the ~1 MB Binder transaction buffer, shared by the whole process. */
        const val BINDER_SAFE_BYTES = 16 * 1024
    }
}
