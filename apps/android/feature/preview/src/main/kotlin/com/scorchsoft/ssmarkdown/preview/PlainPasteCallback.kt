package com.scorchsoft.ssmarkdown.preview

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.scorchsoft.ssmarkdown.feature.preview.R
import com.scorchsoft.ssmarkdown.markdown.paste.PasteClip

/**
 * Wraps the WebView's text-selection menu so "Paste as plain text"
 * (docs/ux-spec.md, "Copy and paste") reaches the page as itself. Chromium
 * shows its own item only when the clip has HTML, and its paste then carries
 * nothing the page could tell from an ordinary plain-text paste. So:
 *
 * - while editing (the menu has Paste) and the clip is formatted (HTML, or
 *   text holding Markdown: [PasteClip.isRich]), the menu has the item right
 *   after Paste: Chromium's own if it shows one, ours otherwise, never both;
 * - either item runs [pastePlain], which sends the clip to the page as a
 *   `pastePlain` message, instead of Chromium's paste.
 *
 * The menu is hidden at once and finished a moment later: finishing it at
 * once would have Chromium collapse the selection before the page had
 * pasted over it.
 */
internal class PlainPasteCallback(
    private val delegate: ActionMode.Callback,
    private val resources: Resources,
    private val isRich: () -> Boolean,
    private val pastePlain: () -> Unit,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
) : ActionMode.Callback2() {
    private var destroyed = false

    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val shown = delegate.onCreateActionMode(mode, menu)
        if (shown) addItem(menu)
        return shown
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val changed = delegate.onPrepareActionMode(mode, menu)
        return addItem(menu) || changed
    }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        if (!isPlainPaste(item)) return delegate.onActionItemClicked(mode, item)
        pastePlain()
        mode.hide(HIDE_MILLIS)
        schedule(FINISH_MILLIS) { if (!destroyed) mode.finish() }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        destroyed = true
        delegate.onDestroyActionMode(mode)
    }

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

    /** Adds our item after Paste when it belongs and Chromium has none; true if the menu changed. */
    private fun addItem(menu: Menu): Boolean {
        val items = (0 until menu.size()).map(menu::getItem)
        val paste = items.firstOrNull(::isPaste) ?: return false
        if (items.any(::isPlainPaste) || !isRich()) return false
        menu
            .add(paste.groupId, ITEM_ID, paste.order, resources.getString(R.string.preview_paste_plain))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    private fun isPaste(item: MenuItem): Boolean =
        item.itemId == android.R.id.paste ||
            entryName(item.itemId)?.endsWith("_paste") == true ||
            item.title?.toString() == resources.getString(android.R.string.paste)

    /** Ours, or Chromium's own "Paste as plain text" (its id is the WebView package's, so by name or title). */
    private fun isPlainPaste(item: MenuItem): Boolean =
        item.itemId == ITEM_ID ||
            entryName(item.itemId)?.endsWith("paste_as_plain_text") == true ||
            item.title?.toString().equals(
                resources.getString(android.R.string.paste_as_plain_text),
                ignoreCase = true,
            ) ||
            item.title?.toString() == resources.getString(R.string.preview_paste_plain)

    private fun entryName(id: Int): String? =
        try {
            if (id == 0) null else resources.getResourceEntryName(id)
        } catch (_: Resources.NotFoundException) {
            null
        }

    companion object {
        /** The platform's id, as `TextView` uses for the same item. */
        val ITEM_ID: Int = android.R.id.pasteAsPlainText

        private const val HIDE_MILLIS = 1_000L
        private const val FINISH_MILLIS = 300L

        /** The clip as "Paste as plain text" sends it: its plain text and HTML flavours ("" for none). */
        fun readClip(context: Context): Pair<String, String>? {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
            val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
            val html = item.htmlText.orEmpty()
            val text = item.text?.toString() ?: if (html.isEmpty()) item.coerceToText(context).toString() else ""
            return text to html
        }

        /** True when the clip is formatted, so "Paste as plain text" differs from Paste. */
        fun clipIsRich(context: Context): Boolean {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            val description = clipboard.primaryClipDescription ?: return false
            if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return true
            val text =
                clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text ?: return false
            return PasteClip.isRich(text.take(PasteClip.RICH_SCAN_CHARS).toString(), null)
        }
    }
}
