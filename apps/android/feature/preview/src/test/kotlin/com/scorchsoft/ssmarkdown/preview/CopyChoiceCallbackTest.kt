package com.scorchsoft.ssmarkdown.preview

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.fakes.RoboMenu

/**
 * Preview's selection menu (Andrew, 2026-11-04): "Copy Markdown" and
 * "Copy formatted" right after Chromium's Copy, which stays; each asks the
 * host for that copy and hides the menu (the host finishes it once the page
 * answers); nothing is added without a selection to copy.
 */
@RunWith(RobolectricTestRunner::class)
class CopyChoiceCallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val markdown = context.getString(R.string.preview_copy_markdown)
    private val formatted = context.getString(R.string.preview_copy_formatted)

    private class Chromium(
        val items: List<Pair<Int, String>>,
    ) : ActionMode.Callback {
        val clicked = mutableListOf<Int>()

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

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private class Mode : ActionMode() {
        var hidden = false

        override fun hide(duration: Long) {
            hidden = true
        }

        override fun finish() = Unit

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

    private val copies = mutableListOf<Boolean>()

    private fun menuFor(chromium: Chromium): Pair<CopyChoiceCallback, RoboMenu> {
        val callback = CopyChoiceCallback(chromium, context.resources) { asFormatted, _ -> copies += asFormatted }
        val menu = RoboMenu(context)
        callback.onCreateActionMode(Mode(), menu)
        return callback to menu
    }

    private fun titles(menu: RoboMenu): List<String> =
        (0 until menu.size()).map(menu::getItem).sortedBy { it.order }.map { it.title.toString() }

    @Test
    fun `with a selection, both copies follow Copy, and Copy stays`() {
        val (_, menu) = menuFor(Chromium(listOf(android.R.id.copy to "Copy", android.R.id.selectAll to "Select all")))
        val shown = titles(menu)
        assertEquals("Copy", shown.first())
        assertTrue(markdown in shown && formatted in shown)
        assertEquals("Select all", shown.last())
    }

    @Test
    fun `each item asks for its copy and hides the menu, never reaching Chromium`() {
        val chromium = Chromium(listOf(android.R.id.copy to "Copy"))
        val (callback, menu) = menuFor(chromium)
        val mode = Mode()
        callback.onActionItemClicked(mode, menu.findItem(CopyChoiceCallback.MARKDOWN_ID))
        callback.onActionItemClicked(mode, menu.findItem(CopyChoiceCallback.FORMATTED_ID))
        assertEquals(listOf(false, true), copies)
        assertTrue(mode.hidden)
        assertTrue(chromium.clicked.isEmpty())
        callback.onActionItemClicked(mode, menu.findItem(android.R.id.copy))
        assertEquals(listOf(android.R.id.copy), chromium.clicked)
    }

    @Test
    fun `no copies without a Copy item, and never twice`() {
        val (_, bare) = menuFor(Chromium(listOf(android.R.id.paste to "Paste")))
        assertFalse(markdown in titles(bare))
        val (callback, menu) = menuFor(Chromium(listOf(android.R.id.copy to "Copy")))
        callback.onPrepareActionMode(Mode(), menu)
        assertEquals(1, titles(menu).count { it == markdown })
    }
}
