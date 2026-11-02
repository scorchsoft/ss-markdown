package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * A formatting action, as a pure function from (text, selection, style)
 * to an [EditResult]. Commands never mutate: the editor applies the ops
 * as one undo group, Preview maps them back through its source map, and
 * tests compare strings.
 *
 * The text is in memory form, `'\n'` only, as `core:text` holds it: a
 * CRLF file has already been normalised on load and is written back with
 * its own endings on save. A command reads only the lines around the
 * selection (plus a bounded look-around for list context), so it costs
 * the same on a 20 MB file as on a note.
 */
public sealed interface EditCommand {
    /**
     * Runs the command. [selection] must lie within [text]. Fence state is
     * read with [FenceStates.scan]; an editor that keeps a tokenizer should
     * pass its own lookup to the other overload instead.
     */
    public fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle = DocumentStyle.DEFAULT,
    ): EditResult = apply(text, selection, style, FenceStates.scan(text))

    /**
     * Runs the command with [fences] saying which lines are fenced code, so
     * line commands leave code alone and the code-block toggle unwraps the
     * block the caret is really in.
     */
    public fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle,
        fences: FenceStates,
    ): EditResult

    /** Toggles [format] on the selection (B, I, strikethrough, inline code). */
    public data class ToggleInline(
        /** The format to add or remove. */
        public val format: InlineFormat,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { InlineToggle.apply(text, selection, style, format) }
    }

    /** Makes the selected lines ATX headings of [level]; 0 makes them paragraphs again. */
    public data class SetHeading(
        /** 0 (paragraph) to 6. */
        public val level: Int,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { HeadingEdit.set(text, selection, level, fences) }
    }

    /**
     * The ¶ menu: makes every block in the selection a [type], replacing the
     * block syntax it had and keeping its text ([BlockType]).
     */
    public data class SetBlockType(
        /** What the blocks become. */
        public val type: BlockType,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { BlockTypeEdit.apply(text, selection, style, type, fences) }
    }

    /** The H key: paragraph → H1 → H2 → H3 → paragraph. */
    public data object CycleHeading : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { HeadingEdit.cycle(text, selection, fences) }
    }

    /** Toggles a list of [kind] on the selected lines. */
    public data class ToggleList(
        /** Bulleted, numbered or task. */
        public val kind: ListKind,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { ListToggle.apply(text, selection, style, kind, fences) }
    }

    /**
     * Ticks or unticks the task on the line holding [lineOffset], changing
     * exactly one char. The selection is left where it was, since a tap on
     * a checkbox in Preview is not a cursor move.
     */
    public data class ToggleTaskCheckbox(
        /** Any offset on the task's line. */
        public val lineOffset: Int,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { TaskCheckbox.apply(text, selection, lineOffset) }
    }

    /** Adds or removes one level of `> ` on the selected lines. */
    public data object ToggleBlockquote : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { BlockquoteToggle.apply(text, selection) }
    }

    /** Wraps the selected lines in a fenced code block, or unwraps one. */
    public data object ToggleCodeBlock : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { CodeBlockToggle.apply(text, selection, style, fences) }
    }

    /** `[selection](|)`; see [InsertEdit.link]. */
    public data object InsertLink : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { InsertEdit.link(text, selection) }
    }

    /**
     * The Link prompt's Done: `[label](url)` over the link the selection is
     * in, or over the selection ([ExistingLink]). A blank [label] shows the
     * address.
     */
    public data class SetLink(
        /** The link's text, as typed (brackets are escaped). */
        public val label: String,
        /** The destination; spaces or unbalanced parentheses get `<>`. */
        public val url: String,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { LinkEdit.set(text, selection, label, url) }
    }

    /** The Link prompt's Remove link: the link at the selection becomes its text. */
    public data object RemoveLink : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { LinkEdit.remove(text, selection) }
    }

    /** A [columns] × [rows] table skeleton below the caret's line, in the document's table style. */
    public data class InsertTable(
        /** Number of columns. */
        public val columns: Int = 3,
        /** Number of body rows under the header. */
        public val rows: Int = 2,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { InsertEdit.table(text, selection, style, columns, rows) }
    }

    /** Indents the selected lines one level; list items nest under the item above. */
    public data object Indent : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { IndentEdit.indent(text, selection, style, fences) }
    }

    /**
     * Outdents the selected lines one level. On a single top-level list item
     * there is no level left, so it leaves the list: the marker comes off and
     * the line becomes a paragraph, as Enter does on an empty item.
     */
    public data object Outdent : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult =
            checked(text, selection) {
                ListContinuation.outOfTopLevelItem(text, selection, fences)
                    ?: IndentEdit.outdent(text, selection, style, fences)
            }
    }

    /**
     * Renumbers the numbered list around [lineOffset] after an item was
     * deleted or pasted in, so `1. 2. 4.` reads `1. 2. 3.` again. The first
     * item keeps its number, a `1. 1. 1.` list is left alone, and only the
     * numbers below the line can change. The editor runs it after a
     * deletion that joins or removes list lines.
     */
    public data class RenumberList(
        /** Any offset on the first line whose number may now be wrong. */
        public val lineOffset: Int,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult =
            checked(text, selection) {
                require(lineOffset in 0..text.length) { "line offset $lineOffset outside text of ${text.length}" }
                val line = TextLines.lineAt(text, lineOffset)
                val rows = ListNumbering.region(text, line, line, fences)
                val builder = EditBuilder(text)
                val numbers = ListNumbering.changes(rows, line.start, restartAtChange = true)
                for (row in rows) {
                    numbers[row.span.start]?.let {
                        ListNumbering.writeNumber(
                            builder,
                            row.span,
                            row.prefix,
                            it,
                        )
                    }
                }
                builder.buildMapping(selection, caretSticksAfter = false)
            }
    }

    /** Enter, with list and quote continuation. */
    public data object Newline : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { ListContinuation.apply(text, selection, style, fences) }
    }

    /** Types [text] over the selection; [smartPairs] (off by default) closes brackets and backticks. */
    public data class Type(
        /** What was typed. */
        public val text: String,
        /** Insert and step over closers. Off unless the user turned it on. */
        public val smartPairs: Boolean = false,
    ) : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
            fences: FenceStates,
        ): EditResult = checked(text, selection) { TypeText.apply(text, selection, this.text, smartPairs) }
    }
}

private inline fun checked(
    text: CharSequence,
    selection: TextSelection,
    command: () -> EditResult,
): EditResult {
    require(selection.end <= text.length) { "selection $selection outside text of ${text.length}" }
    return command()
}
