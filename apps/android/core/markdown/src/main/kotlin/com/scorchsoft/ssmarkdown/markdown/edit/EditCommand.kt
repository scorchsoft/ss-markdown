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
    /** Runs the command. [selection] must lie within [text]. */
    public fun apply(
        text: CharSequence,
        selection: TextSelection,
        style: DocumentStyle = DocumentStyle.DEFAULT,
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
        ): EditResult = checked(text, selection) { HeadingEdit.set(text, selection, level) }
    }

    /** The H key: paragraph → H1 → H2 → H3 → paragraph. */
    public data object CycleHeading : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { HeadingEdit.cycle(text, selection) }
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
        ): EditResult = checked(text, selection) { ListToggle.apply(text, selection, style, kind) }
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
        ): EditResult = checked(text, selection) { TaskCheckbox.apply(text, selection, lineOffset) }
    }

    /** Adds or removes one level of `> ` on the selected lines. */
    public data object ToggleBlockquote : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { BlockquoteToggle.apply(text, selection) }
    }

    /** Wraps the selected lines in a fenced code block, or unwraps one. */
    public data object ToggleCodeBlock : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { CodeBlockToggle.apply(text, selection, style) }
    }

    /** `[selection](|)`; see [InsertEdit.link]. */
    public data object InsertLink : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { InsertEdit.link(text, selection) }
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
        ): EditResult = checked(text, selection) { InsertEdit.table(text, selection, style, columns, rows) }
    }

    /** Indents the selected lines one level; list items nest under the item above. */
    public data object Indent : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { IndentEdit.indent(text, selection, style) }
    }

    /** Outdents the selected lines one level. */
    public data object Outdent : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { IndentEdit.outdent(text, selection, style) }
    }

    /** Enter, with list and quote continuation. */
    public data object Newline : EditCommand {
        override fun apply(
            text: CharSequence,
            selection: TextSelection,
            style: DocumentStyle,
        ): EditResult = checked(text, selection) { ListContinuation.apply(text, selection, style) }
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
