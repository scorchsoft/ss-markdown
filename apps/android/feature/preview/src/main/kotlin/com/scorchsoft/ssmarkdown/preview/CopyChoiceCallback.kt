package com.scorchsoft.ssmarkdown.preview

import android.content.res.Resources
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.scorchsoft.ssmarkdown.feature.preview.R

/**
 * Adds "Copy Markdown" and "Copy formatted text" after Copy in the WebView's
 * text-selection menu (Andrew, 2026-11-04: copy just the selected part,
 * either way, as the header's Copy chooser does for the whole file).
 * Chromium's own Copy stays: it carries both, so a paste back into Preview
 * keeps the formatting. Either item runs [copy] and hides the menu; the menu
 * is finished once the page has answered ([finish] from the host), so the
 * selection is still there when the page reads it.
 */
internal class CopyChoiceCallback(
    private val delegate: ActionMode.Callback,
    private val resources: Resources,
    private val copy: (formatted: Boolean, mode: ActionMode) -> Unit,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val shown = delegate.onCreateActionMode(mode, menu)
        if (shown) addItems(menu)
        return shown
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val changed = delegate.onPrepareActionMode(mode, menu)
        return addItems(menu) || changed
    }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean =
        when (item.itemId) {
            MARKDOWN_ID -> true.also { run(mode, formatted = false) }
            FORMATTED_ID -> true.also { run(mode, formatted = true) }
            else -> delegate.onActionItemClicked(mode, item)
        }

    private fun run(
        mode: ActionMode,
        formatted: Boolean,
    ) {
        mode.hide(HIDE_MILLIS)
        copy(formatted, mode)
    }

    override fun onDestroyActionMode(mode: ActionMode) = delegate.onDestroyActionMode(mode)

    override fun onGetContentRect(
        mode: ActionMode,
        view: View,
        outRect: Rect,
    ) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
    }

    /** After Copy, when the menu has one (a selection) and not ours yet; true if the menu changed. */
    private fun addItems(menu: Menu): Boolean {
        val items = (0 until menu.size()).map(menu::getItem)
        val copyItem = items.firstOrNull(::isCopy) ?: return false
        if (items.any { it.itemId == MARKDOWN_ID }) return false
        val flags = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
        menu
            .add(copyItem.groupId, MARKDOWN_ID, copyItem.order, resources.getString(R.string.preview_copy_markdown))
            .setShowAsAction(flags)
        menu
            .add(copyItem.groupId, FORMATTED_ID, copyItem.order, resources.getString(R.string.preview_copy_formatted))
            .setShowAsAction(flags)
        return true
    }

    private fun isCopy(item: MenuItem): Boolean =
        item.itemId == android.R.id.copy ||
            entryName(item.itemId)?.endsWith("_copy") == true ||
            item.title?.toString() == resources.getString(android.R.string.copy)

    private fun entryName(id: Int): String? =
        try {
            if (id == 0) null else resources.getResourceEntryName(id)
        } catch (_: Resources.NotFoundException) {
            null
        }

    companion object {
        val MARKDOWN_ID: Int = R.id.preview_copy_markdown
        val FORMATTED_ID: Int = R.id.preview_copy_formatted

        /** Long enough for the page to answer; the host finishes the menu when it does. */
        private const val HIDE_MILLIS = 2_000L
    }
}
