package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.fencesFor
import com.scorchsoft.ssmarkdown.editor.preview.PreviewStripKeys
import com.scorchsoft.ssmarkdown.markdown.edit.EditCommand
import com.scorchsoft.ssmarkdown.markdown.edit.FormatState
import com.scorchsoft.ssmarkdown.markdown.edit.InlineFormat
import com.scorchsoft.ssmarkdown.markdown.edit.ListKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.ui.strip.BlockChoice
import com.scorchsoft.ssmarkdown.ui.strip.CursorStep
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import com.scorchsoft.ssmarkdown.ui.strip.StripPage
import com.scorchsoft.ssmarkdown.ui.strip.StripPaging

/**
 * The formatting strip's keys, run as `core:markdown` edit commands. Each
 * command first commits the keyboard's composing word (research R6), then
 * applies as one undo step through the editor surface.
 */
class StripController(
    private val host: EditorHost,
) {
    /** The Link key's prompt, in both views. */
    val links: LinkController = LinkController(host)

    /** A strip key was tapped. Paging was applied by the reducer already. */
    fun onKey(key: StripKey) {
        val doc = host.active ?: return
        if (host.state.previewTyping) {
            onPreviewKey(key)
            return
        }
        val surface = doc.surface
        when (key) {
            StripKey.Undo -> surface.undo()

            StripKey.Redo -> surface.redo()

            StripKey.Done -> host.emit(EditorEvent.HideKeyboard)

            StripKey.Symbols -> Unit

            // Where the link goes is part of the tap: ask first (LinkController).
            StripKey.Link -> links.open(surface.selection)

            // The picker opens first; the picture goes where the selection was (PictureController).
            StripKey.Image -> host.pictures.insert(surface.selection)

            else -> commandFor(key)?.let(::run)
        }
        host.refresh()
        refreshPressed()
    }

    /**
     * A strip key while Preview has the caret (ux-spec "Editing in Preview"):
     * formats go to the page, which answers with its selection's source
     * range; Undo is the document's own history; Done goes back to reading.
     */
    private fun onPreviewKey(key: StripKey) {
        val surface = host.active?.surface ?: return
        when (key) {
            StripKey.Undo -> surface.undo()
            StripKey.Redo -> surface.redo()
            StripKey.Done -> host.preview.endEditing().also { host.emit(EditorEvent.HideKeyboard) }
            StripKey.PrevCell -> host.preview.send(HostMessage.TableNav(next = false))
            StripKey.NextCell -> host.preview.send(HostMessage.TableNav(next = true))
            else -> PreviewStripKeys.actionFor(key, host.state.preview.inHeading)?.let(host.preview::format)
        }
        host.refresh()
    }

    /**
     * A ¶ menu choice: every block in the selection becomes [choice]. Code
     * runs the command here; in Preview the page sends its selection and
     * Kotlin runs the same command on it ([PreviewStripKeys.blockAction]).
     */
    fun onBlock(choice: BlockChoice) {
        if (host.active == null) return
        if (host.state.previewTyping) {
            host.preview.format(PreviewStripKeys.blockAction(choice))
        } else {
            run(EditCommand.SetBlockType(PreviewStripKeys.blockType(choice)))
            refreshPressed()
        }
        host.refresh()
    }

    /** A symbols-page key: types [text] over the selection. The page stays (no hidden lock). */
    fun onSymbol(text: String) = run(EditCommand.Type(text))

    /** A cursor key on the symbols page; held, it repeats. */
    fun onCursor(step: CursorStep) {
        val doc = host.active ?: return
        val surface = doc.surface
        surface.flushComposing()
        val caret = surface.selection.end
        val text = doc.text.text
        val length = text.length
        var target = if (step == CursorStep.Left) (caret - 1).coerceAtLeast(0) else (caret + 1).coerceAtMost(length)
        // Never between the halves of a surrogate pair (an emoji): what is typed there splits it for good.
        if (target in 1 until length && text[target].isLowSurrogate() && text[target - 1].isHighSurrogate()) {
            target += if (step == CursorStep.Left) -1 else 1
        }
        surface.select(target, target)
    }

    /** The keyboard typed: a letter ends the symbols page (StripPaging.afterTyped). */
    @Suppress("ReturnCount") // Not on symbols, no document and nothing typed are three reasons to stop.
    fun afterTyped() {
        val strip = host.state.strip
        if (strip.page != StripPage.Symbols) return
        val doc = host.active ?: return
        val caret = doc.surface.selection.end
        if (caret <= 0) return
        val typed = doc.text.text.getText(caret - 1, caret)
        host.update { it.copy(strip = it.strip.copy(page = StripPaging.afterTyped(strip.page, typed))) }
    }

    /** The selection moved: the pressed keys follow it (B is pressed inside bold text). */
    fun onSelectionChanged(
        start: Int,
        end: Int,
    ) {
        refreshPressed(TextSelection(minOf(start, end), maxOf(start, end)))
    }

    @Suppress("ReturnCount") // No document, no selection and a stale one are three reasons to stop.
    private fun refreshPressed(selection: TextSelection? = host.active?.surface?.selection) {
        val doc = host.active ?: return
        val sel = selection ?: return
        val snapshot = doc.text.snapshot()
        if (sel.end > snapshot.length) return
        val format = FormatState.at(snapshot, sel, doc.surface.fences)
        host.update {
            it.copy(
                strip =
                    it.strip.copy(
                        pressed = pressedKeys(format),
                        disabled = disabledKeys(format),
                        block = PreviewStripKeys.choiceFor(format.block),
                        canUndo = doc.text.canUndo,
                        canRedo = doc.text.canRedo,
                    ),
            )
        }
    }

    private fun run(command: EditCommand) {
        val doc = host.active ?: return
        val surface = doc.surface
        surface.flushComposing()
        val snapshot = doc.text.snapshot()
        val selection = surface.selection
        if (selection.end > snapshot.length) return
        val result = command.apply(snapshot, selection, doc.style, surface.fencesFor(snapshot))
        surface.apply(result.ops, result.selection)
    }

    companion object {
        /** The edit command behind each formatting key, or null for keys that are not edits. */
        fun commandFor(key: StripKey): EditCommand? =
            when (key) {
                StripKey.Heading, StripKey.BlockStyle -> EditCommand.CycleHeading
                StripKey.Bold -> EditCommand.ToggleInline(InlineFormat.Bold)
                StripKey.Italic -> EditCommand.ToggleInline(InlineFormat.Italic)
                StripKey.Code -> EditCommand.ToggleInline(InlineFormat.Code)
                StripKey.BulletList -> EditCommand.ToggleList(ListKind.Bullet)
                StripKey.NumberedList -> EditCommand.ToggleList(ListKind.Numbered)
                StripKey.Task -> EditCommand.ToggleList(ListKind.Task)
                StripKey.Link -> EditCommand.InsertLink
                StripKey.Indent -> EditCommand.Indent
                StripKey.Outdent -> EditCommand.Outdent
                StripKey.Table -> EditCommand.InsertTable()
                StripKey.Quote -> EditCommand.ToggleBlockquote
                else -> null
            }

        /**
         * The keys shown but disabled for [format]: Indent where it can do
         * nothing (a paragraph, heading or quote line), ¶ in a table.
         */
        fun disabledKeys(format: FormatState): Set<StripKey> =
            buildSet {
                if (!format.canIndent) add(StripKey.Indent)
                if (format.inTable) add(StripKey.BlockStyle)
            }

        /** The keys shown pressed for [format]. */
        fun pressedKeys(format: FormatState): Set<StripKey> =
            buildSet {
                if (InlineFormat.Bold in format.inline) add(StripKey.Bold)
                if (InlineFormat.Italic in format.inline) add(StripKey.Italic)
                if (InlineFormat.Code in format.inline) add(StripKey.Code)
                if (format.headingLevel > 0) add(StripKey.Heading)
                if (format.list == ListKind.Bullet) add(StripKey.BulletList)
                if (format.list == ListKind.Numbered) add(StripKey.NumberedList)
                if (format.list == ListKind.Task) add(StripKey.Task)
                if (format.quote) add(StripKey.Quote)
            }
    }
}
