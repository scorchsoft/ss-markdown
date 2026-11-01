package com.scorchsoft.ssmarkdown.editor

import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View

/**
 * The floating text toolbar (Cut, Copy, Paste, Select all) shown over a
 * selection, as `TextView` shows it. Items use the platform ids and
 * strings, so they read and translate exactly like every other app's.
 * [onGetContentRect] places the toolbar over the selection (or caret).
 */
internal class TextActionModeCallback(
    private val host: Host,
) : ActionMode.Callback2() {
    /** What the toolbar needs from the editor. */
    interface Host {
        /** True when text is selected (Cut and Copy only make sense then). */
        val hasSelection: Boolean

        /** True when the clipboard holds text to paste. */
        val canPaste: Boolean

        /** Runs a clipboard or selection command; returns true if it did something. */
        fun runCommand(command: EditorCommand): Boolean

        /** The selection's (or caret's) bounds in view px, for placing the toolbar. */
        fun selectionBounds(out: Rect)

        /** The toolbar has gone; forget the mode. */
        fun onActionModeDestroyed()
    }

    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        mode.title = null
        mode.subtitle = null
        populate(menu)
        return true
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        menu.clear()
        populate(menu)
        return true
    }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        val command = ITEMS.firstOrNull { it.id == item.itemId }?.command ?: return false
        host.runCommand(command)
        if (command != EditorCommand.SELECT_ALL) mode.finish() else mode.invalidate()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        host.onActionModeDestroyed()
    }

    override fun onGetContentRect(
        mode: ActionMode,
        view: View,
        outRect: Rect,
    ) {
        host.selectionBounds(outRect)
    }

    private fun populate(menu: Menu) {
        for ((order, item) in ITEMS.withIndex()) {
            val enabled =
                when (item.command) {
                    EditorCommand.CUT, EditorCommand.COPY -> host.hasSelection
                    EditorCommand.PASTE -> host.canPaste
                    else -> true
                }
            if (enabled) {
                menu
                    .add(Menu.NONE, item.id, order, item.title)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            }
        }
    }

    private class Item(
        val id: Int,
        val title: Int,
        val command: EditorCommand,
    )

    private companion object {
        val ITEMS =
            listOf(
                Item(android.R.id.cut, android.R.string.cut, EditorCommand.CUT),
                Item(android.R.id.copy, android.R.string.copy, EditorCommand.COPY),
                Item(android.R.id.paste, android.R.string.paste, EditorCommand.PASTE),
                Item(android.R.id.selectAll, android.R.string.selectAll, EditorCommand.SELECT_ALL),
            )
    }
}
