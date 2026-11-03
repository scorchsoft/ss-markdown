package com.scorchsoft.ssmarkdown.preview

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.feature.preview.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.fakes.RoboMenu

/**
 * Preview's "Paste as plain text" in the WebView's selection menu
 * (EB-CLIP-14): our item right after Paste for a formatted clip and never
 * beside Chromium's own, and either one sends the clip to the page instead
 * of letting Chromium paste.
 */
@RunWith(RobolectricTestRunner::class)
class PlainPasteCallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val plainTitle = context.getString(R.string.preview_paste_plain)

    /** Plays Chromium's callback: the items it would show, and the clicks it was asked to handle. */
    private class Chromium(
        val items: List<Pair<Int, String>>,
    ) : ActionMode.Callback {
        val clicked = mutableListOf<Int>()
        var destroyed = 0

        override fun onCreateActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean {
            items.forEachIndexed { order, (id, title) -> menu.add(Menu.NONE, id, order, title) }
            return true
        }

        override fun onPrepareActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean = false

        override fun onActionItemClicked(
            mode: ActionMode,
            item: MenuItem,
        ): Boolean {
            clicked += item.itemId
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            destroyed++
        }
    }

    private class Mode : ActionMode() {
        var hidden = false
        var finished = false

        override fun hide(duration: Long) {
            hidden = true
        }

        override fun finish() {
            finished = true
        }

        override fun setTitle(title: CharSequence?) = Unit

        override fun setTitle(resId: Int) = Unit

        override fun setSubtitle(subtitle: CharSequence?) = Unit

        override fun setSubtitle(resId: Int) = Unit

        override fun setCustomView(view: View?) = Unit

        override fun invalidate() = Unit

        override fun getMenu(): Menu? = null

        override fun getTitle(): CharSequence? = null

        override fun getSubtitle(): CharSequence? = null

        override fun getCustomView(): View? = null

        override fun getMenuInflater(): MenuInflater? = null
    }

    private val editing =
        listOf(
            android.R.id.copy to "Copy",
            android.R.id.paste to "Paste",
            android.R.id.selectAll to "Select all",
        )
    private var pasted = 0
    private val scheduled = mutableListOf<() -> Unit>()

    private fun menuFor(
        chromium: Chromium,
        rich: Boolean,
    ): Pair<PlainPasteCallback, RoboMenu> {
        val callback =
            PlainPasteCallback(
                chromium,
                context.resources,
                { rich },
                { pasted++ },
                { _, action -> scheduled += action },
            )
        val menu = RoboMenu(context)
        callback.onCreateActionMode(Mode(), menu)
        return callback to menu
    }

    private fun titles(menu: Menu): List<String> = (0 until menu.size()).map { menu.getItem(it).title.toString() }

    @Test
    fun `while editing a formatted clip, the item follows Paste`() {
        val (_, menu) = menuFor(Chromium(editing), rich = true)
        assertEquals(listOf("Copy", "Paste", plainTitle, "Select all"), titles(menu.sorted()))
        assertEquals(plainTitle, menu.findItem(android.R.id.pasteAsPlainText).title.toString())
    }

    @Test
    fun `no item for a plain clip, or while only reading`() {
        assertFalse(plainTitle in titles(menuFor(Chromium(editing), rich = false).second))
        val reading = listOf(android.R.id.copy to "Copy", android.R.id.selectAll to "Select all")
        assertFalse(plainTitle in titles(menuFor(Chromium(reading), rich = true).second))
    }

    @Test
    fun `Chromium's own item is never doubled, and clicking it pastes through the page`() {
        val chromiumPlain = View.generateViewId()
        val chromium = Chromium(editing + (chromiumPlain to "Paste as plain text"))
        val (callback, menu) = menuFor(chromium, rich = true)
        assertEquals(1, titles(menu).count { it.equals(plainTitle, ignoreCase = true) })
        assertTrue(callback.onPrepareActionMode(Mode(), menu).not() || titles(menu).count { it == plainTitle } == 1)

        val mode = Mode()
        assertTrue(callback.onActionItemClicked(mode, menu.findItem(chromiumPlain)))
        assertEquals(1, pasted)
        assertTrue("Chromium's paste never runs", chromium.clicked.isEmpty())
        assertTrue(mode.hidden)
        assertFalse("not finished before the page pasted", mode.finished)
        scheduled.forEach { it() }
        assertTrue(mode.finished)
    }

    @Test
    fun `our item pastes through the page, other items stay Chromium's`() {
        val chromium = Chromium(editing)
        val (callback, menu) = menuFor(chromium, rich = true)
        callback.onActionItemClicked(Mode(), menu.findItem(android.R.id.pasteAsPlainText))
        assertEquals(1, pasted)
        callback.onActionItemClicked(Mode(), menu.findItem(android.R.id.paste))
        assertEquals(listOf(android.R.id.paste), chromium.clicked)

        // A menu that went away first is not finished again.
        val mode = Mode()
        callback.onActionItemClicked(mode, menu.findItem(android.R.id.pasteAsPlainText))
        callback.onDestroyActionMode(mode)
        scheduled.forEach { it() }
        assertFalse(mode.finished)
        assertEquals(1, chromium.destroyed)
    }

    @Test
    fun `the clip is read as its two flavours, and judged formatted by HTML or Markdown`() {
        clipboard.setPrimaryClip(ClipData.newHtmlText(null, "a b", "<b>a</b> b"))
        assertEquals("a b" to "<b>a</b> b", PlainPasteCallback.readClip(context))
        assertTrue(PlainPasteCallback.clipIsRich(context))
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "# Title"))
        assertEquals("# Title" to "", PlainPasteCallback.readClip(context))
        assertTrue(PlainPasteCallback.clipIsRich(context))
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "just words"))
        assertFalse(PlainPasteCallback.clipIsRich(context))
        clipboard.setPrimaryClip(
            ClipData(
                ClipDescription(null, arrayOf(ClipDescription.MIMETYPE_TEXT_HTML)),
                ClipData.Item(null, "<i>x</i>"),
            ),
        )
        assertEquals("" to "<i>x</i>", PlainPasteCallback.readClip(context))
        clipboard.clearPrimaryClip()
        assertNull(PlainPasteCallback.readClip(context))
        assertFalse(PlainPasteCallback.clipIsRich(context))
    }

    /** The menu's items in display order. */
    private fun RoboMenu.sorted(): Menu {
        val out = RoboMenu(context)
        (0 until size())
            .map(
                ::getItem,
            ).sortedBy { it.order }
            .forEach { out.add(it.groupId, it.itemId, it.order, it.title) }
        return out
    }
}
