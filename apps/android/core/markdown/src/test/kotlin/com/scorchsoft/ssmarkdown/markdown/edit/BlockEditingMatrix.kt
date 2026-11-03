package com.scorchsoft.ssmarkdown.markdown.edit

/**
 * The block-editing matrix: every block type (lists of each kind, nested
 * and quoted lists, tasks, quotes, alerts, fenced code, headings, tables,
 * hard breaks) at every position that matters (first, middle, last item,
 * nested, empty, caret mid-text, a selection across items), under every
 * action that reshapes blocks (Enter, Backspace at the start, Outdent,
 * Indent, the Bullet and Numbered keys, the ¶ choices).
 *
 * `‸` is the caret and `«…»` a selection, before and after. The rows
 * marked [MatrixRow.preview] are also written to
 * `feature/preview/web/test/vectors/block-editing.json`, which the page's
 * Playwright suite drives through real key presses on the built page, so
 * Code and Preview are held to the same table (docs/edge-cases.md).
 */
object BlockEditingMatrix {
    /** What the row does, and how Preview's e2e performs it. */
    enum class Action(
        val wire: String,
    ) {
        Enter("enter"),
        Backspace("backspace"),
        Outdent("outdent"),
        Indent("indent"),
        Bullet("bullet"),
        Numbered("numbered"),
        BlockParagraph("blockParagraph"),
        BlockHeading1("blockHeading1"),
        BlockHeading2("blockHeading2"),
        BlockBullet("blockBullet"),
        BlockNumbered("blockNumbered"),
        BlockQuote("blockQuote"),
        BlockCode("blockCode"),

        /** `}` typed (Preview: the key pressed). */
        TypeBrace("type}"),
    }

    /**
     * One case. [previewAfter] is Preview's answer where it rightly differs
     * from Code's (Enter in a paragraph or heading starts a new paragraph in
     * Preview, a raw line in Code); null means "the same as [after]". [preview]
     * is false where Preview cannot do the action: a selection across two
     * blocks (a Preview editing session holds one block), Backspace joining
     * two blocks (EB-LIST-33, not Preview's yet), a table (its own tests).
     */
    data class MatrixRow(
        val id: String,
        val action: Action,
        val before: String,
        val after: String,
        val preview: Boolean = true,
        val previewAfter: String? = null,
    )

    private fun enter(
        id: String,
        before: String,
        after: String,
        preview: Boolean = true,
        previewAfter: String? = null,
    ) = MatrixRow(id, Action.Enter, before, after, preview, previewAfter)

    private fun row(
        id: String,
        action: Action,
        before: String,
        after: String,
        preview: Boolean = true,
    ) = MatrixRow(id, action, before, after, preview)

    val rows: List<MatrixRow> =
        listOf(
            // Andrew's report, 0.2.2: Enter at the end of the last bullet; on item 3 of six numbered.
            enter("EB-LIST-01 andrew-1 bullet last item", "- one\n- two\n- three‸", "- one\n- two\n- three\n- ‸"),
            enter(
                "andrew-3 numbered item 3 of 6",
                "1. one\n2. two\n3. three‸\n4. four\n5. five\n6. six",
                "1. one\n2. two\n3. three\n4. ‸\n5. four\n6. five\n7. six",
            ),
            // Enter in a non-empty item: a new item of the same kind, directly after it.
            enter("bullet first", "- a‸\n- b\n- c", "- a\n- ‸\n- b\n- c"),
            enter("EB-LIST-16 bullet middle", "- a\n- b‸\n- c", "- a\n- b\n- ‸\n- c"),
            enter("EB-LIST-02 bullet star keeps its char", "* a\n* b‸", "* a\n* b\n* ‸"),
            enter("EB-LIST-02 bullet plus keeps its char", "+ a‸\n+ b", "+ a\n+ ‸\n+ b"),
            enter("bullet before a paragraph", "- a\n- b‸\n\nAfter.", "- a\n- b\n- ‸\n\nAfter."),
            enter("EB-NUM-03 numbered first", "1. a‸\n2. b\n3. c", "1. a\n2. ‸\n3. b\n4. c"),
            enter("numbered middle", "1. a\n2. b‸\n3. c\n4. d", "1. a\n2. b\n3. ‸\n4. c\n5. d"),
            enter("EB-NUM-01 numbered last", "1. a\n2. b‸", "1. a\n2. b\n3. ‸"),
            enter("EB-NUM-05 numbered keeps start and paren", "5) a‸\n6) b", "5) a\n6) ‸\n7) b"),
            enter("numbered loose list", "1. a\n\n2. b‸\n\n3. c", "1. a\n\n2. b\n3. ‸\n\n4. c"),
            enter("EB-LIST-03 bullet caret mid-text splits", "- ab‸cd\n- e", "- ab\n- ‸cd\n- e"),
            enter("numbered caret mid-text splits", "1. ab‸cd\n2. e", "1. ab\n2. ‸cd\n3. e"),
            enter("EB-TASK-02 task makes an unchecked task", "- [x] a‸\n- [ ] b", "- [x] a\n- [ ] ‸\n- [ ] b"),
            enter("task caret mid-text splits", "- [ ] ab‸cd", "- [ ] ab\n- [ ] ‸cd"),
            enter("EB-TASK-12 numbered task", "1. [x] a‸\n2. [ ] b", "1. [x] a\n2. [ ] ‸\n3. [ ] b"),
            enter("nested bullet in numbered", "1. a\n   - b‸\n2. c", "1. a\n   - b\n   - ‸\n2. c"),
            enter(
                "EB-LISTQ-05 nested numbered in bullet",
                "- a\n  1. b‸\n  2. c\n- d",
                "- a\n  1. b\n  2. ‸\n  3. c\n- d",
            ),
            enter("EB-LISTQ-01 bullet in quote", "> - a‸\n> - b", "> - a\n> - ‸\n> - b"),
            enter("numbered in quote", "> 1. a‸\n> 2. b", "> 1. a\n> 2. ‸\n> 3. b"),
            enter("EB-LIST-08 selection across items", "- a«b\n- c»d\n- e", "- a\n- ‸d\n- e", preview = false),
            enter("selection removing numbered items", "1. «a\n2. b\n3. c»\n4. d", "1. \n2. ‸\n3. d", preview = false),
            // A line continuing an item (after a hard break) is still that item.
            enter("hard break backslash in item", "- a\\\n  b‸", "- a\\\n  b\n- ‸"),
            enter("hard break spaces in item", "- a  \n  b‸\n- c", "- a  \n  b\n- ‸\n- c"),
            enter("hard break in numbered item", "1. a\\\n   b‸\n2. c", "1. a\\\n   b\n2. ‸\n3. c"),
            enter(
                "EB-LIST-11 indented paragraph of an item keeps its indent",
                "- a\n\n  more‸",
                "- a\n\n  more\n  ‸",
                preview = false,
            ),
            enter("EB-LIST-12 lazy line of an item is a plain newline", "- a\nlazy‸", "- a\nlazy\n‸", preview = false),
            enter("hard break in paragraph", "a\\\nb‸", "a\\\nb\n‸", previewAfter = "a\\\nb\n\n‸"),
            // Enter on an empty item: it becomes a paragraph.
            enter("EB-LIST-05 empty last bullet", "- a\n- ‸", "- a\n\n‸"),
            enter("EB-LIST-07 empty middle bullet splits the list", "- a\n- ‸\n- b", "- a\n\n‸\n- b"),
            enter(
                "EB-NUM-08 empty middle numbered continues numbering",
                "1. a\n2. ‸\n3. b\n4. c",
                "1. a\n\n‸\n\n2. b\n3. c",
            ),
            enter("empty middle paren list from 3", "3) a\n4) ‸\n5) b", "3) a\n\n‸\n\n4) b"),
            enter("empty first numbered", "1. ‸\n2. b", "‸\n1. b"),
            enter("empty lone item", "- ‸", "‸"),
            enter("EB-TASK-04 empty task", "- [ ] a\n- [ ] ‸", "- [ ] a\n\n‸"),
            enter("EB-LIST-06 empty nested outdents first", "- a\n  - ‸", "- a\n- ‸"),
            enter("EB-NUM-10 empty nested numbered outdents first", "1. a\n   1. ‸\n2. b", "1. a\n2. ‸\n3. b"),
            enter("EB-LISTQ-02 empty item in quote", "> - a\n> - ‸", "> - a\n>\n> ‸"),
            // Quotes and alerts.
            enter("EB-QUOTE-01 quote continues", "> a‸", "> a\n> ‸", previewAfter = "> a\n>\n> ‸"),
            enter("EB-QUOTE-02 empty quote line ends the quote", "> a\n> ‸", "> a\n\n‸"),
            enter("empty quote after a quote break", "> a\n>\n> ‸", "> a\n\n‸"),
            enter("empty quote line in the middle", "> a\n> ‸\n> b", "> a\n\n‸\n> b"),
            enter("lone empty quote", "> ‸", "‸"),
            enter("EB-QUOTE-04 empty nested quote steps out one level", "> > a\n> > ‸", "> > a\n> ‸"),
            enter(
                "EB-QUOTE-13 alert continues",
                "> [!NOTE]\n> Text‸",
                "> [!NOTE]\n> Text\n> ‸",
                previewAfter = "> [!NOTE]\n> Text\n>\n> ‸",
            ),
            enter("empty alert line ends it", "> [!NOTE]\n> Text\n> ‸", "> [!NOTE]\n> Text\n\n‸"),
            // Fenced code: never a list marker, indentation kept.
            enter("EB-CODE-01 code keeps indentation", "```\n  code‸\n```", "```\n  code\n  ‸\n```"),
            enter("EB-CODE-02 code never continues a list", "```\n- item‸\n```", "```\n- item\n‸\n```"),
            enter("code never numbers", "```\n1. x‸\n```", "```\n1. x\n‸\n```"),
            enter(
                "EB-CODE-05 code exit on a second empty last line",
                "```\na\n\n‸\n```",
                "```\na\n\n\n‸\n```",
                previewAfter = "```\na\n```\n‸",
            ),
            enter("code after a brace adds a unit", "```js\nif (x) {‸\n```", "```js\nif (x) {\n    ‸\n```"),
            enter(
                "code after a brace in a two-space block",
                "```js\nf() {\n  a\n  b {‸\n```",
                "```js\nf() {\n  a\n  b {\n    ‸\n```",
            ),
            enter("code after a colon in python", "```python\ndef f():‸\n```", "```python\ndef f():\n    ‸\n```"),
            enter("code after a colon, no language", "```\na:‸\n```", "```\na:\n‸\n```"),
            enter("code after a tab-indented bracket", "```\n\tx = [‸\n```", "```\n\tx = [\n\t\t‸\n```"),
            // Headings and paragraphs.
            enter("EB-HEAD-01 heading end", "# Title‸", "# Title\n‸", previewAfter = "# Title\n\n‸"),
            enter("EB-HEAD-02 heading middle splits", "# Ti‸tle", "# Ti\n‸tle", previewAfter = "# Ti\n\n‸tle"),
            enter("paragraph end", "Text‸", "Text\n‸", previewAfter = "Text\n\n‸"),
            // Tables keep today's behaviour (Preview moves to the next cell instead: its own tests).
            enter(
                "table row end",
                "| a | b |\n| - | - |\n| 1 | 2 |‸",
                "| a | b |\n| - | - |\n| 1 | 2 |\n‸",
                preview = false,
            ),
            // Backspace at the very start of an item's text: the marker goes, the text stays put.
            row("backspace last bullet", Action.Backspace, "- a\n- ‸b", "- a\n\n‸b"),
            row("EB-LIST-30 backspace first bullet", Action.Backspace, "- ‸a\n- b", "‸a\n- b"),
            row("backspace middle numbered", Action.Backspace, "1. a\n2. ‸b\n3. c", "1. a\n\n‸b\n\n2. c"),
            row(
                "EB-TASK-05 backspace task loses its box first",
                Action.Backspace,
                "- [ ] a\n- [ ] ‸b",
                "- [ ] a\n- ‸b",
            ),
            row("EB-LIST-31 backspace nested makes a paragraph", Action.Backspace, "- a\n  - ‸b", "- a\n\n‸b"),
            row("EB-LIST-32 backspace empty item", Action.Backspace, "- a\n- ‸", "- a\n\n‸"),
            row("backspace item in quote", Action.Backspace, "> - a\n> - ‸b", "> - a\n>\n> ‸b"),
            row("backspace again merges as normal", Action.Backspace, "- a\n\n‸b", "- a\n‸b", preview = false),
            row("backspace mid-text deletes a char", Action.Backspace, "- a‸b", "- ‸b"),
            row(
                "backspace in code deletes a char",
                Action.Backspace,
                "```\n- ‸a\n```",
                "```\n-‸a\n```",
                preview = false,
            ),
            // Outdent (strip key, Shift+Tab).
            row("EB-LIST-24 outdent nested", Action.Outdent, "- a\n  - b‸", "- a\n- b‸"),
            row("outdent nested numbered", Action.Outdent, "1. a\n   1. b‸\n2. c", "1. a\n2. b‸\n3. c"),
            row("EB-LIST-25 outdent top-level middle bullet", Action.Outdent, "- a\n- b‸\n- c", "- a\n\nb‸\n- c"),
            row(
                "outdent top-level middle numbered",
                Action.Outdent,
                "1. a\n2. b‸\n3. c\n4. d",
                "1. a\n\nb‸\n\n2. c\n3. d",
            ),
            row("outdent last item", Action.Outdent, "- a\n- b‸", "- a\n\nb‸"),
            row("outdent first numbered", Action.Outdent, "1. a‸\n2. b", "a‸\n1. b"),
            // Indent.
            row("EB-LIST-22 indent bullet", Action.Indent, "- a\n- b‸", "- a\n  - b‸"),
            row("EB-LIST-23 indent first item does nothing", Action.Indent, "- a‸\n- b", "- a‸\n- b"),
            row("indent leaves a paragraph alone", Action.Indent, "pl‸ain", "pl‸ain"),
            // Indent and Outdent in fenced code: one unit of the block's own, never list nesting.
            row("EB-CODE-03 indent code line", Action.Indent, "```\nfoo‸\n```", "```\n    foo‸\n```"),
            row("indent code at line start", Action.Indent, "```\n‸foo\n```", "```\n    ‸foo\n```"),
            row("indent code two-space block", Action.Indent, "```\na\n  b\nc‸\n```", "```\na\n  b\n  c‸\n```"),
            row("indent code tab block", Action.Indent, "```\n\ta\nb‸\n```", "```\n\ta\n\tb‸\n```"),
            row("indent code selection", Action.Indent, "```\n«a\nb»\n```", "```\n«    a\n    b»\n```"),
            row("indent code list-like line", Action.Indent, "```\n- a‸\n```", "```\n    - a‸\n```"),
            row("EB-CODE-04 outdent code line", Action.Outdent, "```\n    a‸\n```", "```\na‸\n```"),
            row("outdent code by its unit", Action.Outdent, "```\na\n  b\n    c‸\n```", "```\na\n  b\n  c‸\n```"),
            row("outdent code tab", Action.Outdent, "```\n\ta‸\n```", "```\na‸\n```"),
            row("outdent code list-like line stays code", Action.Outdent, "```\n- a‸\n```", "```\n- a‸\n```"),
            row(
                "type closer dedents in code",
                Action.TypeBrace,
                "```js\nif (x) {\n    ‸\n```",
                "```js\nif (x) {\n}‸\n```",
            ),
            row("type closer after text stays", Action.TypeBrace, "```js\n  a‸\n```", "```js\n  a}‸\n```"),
            row("type closer outside code stays", Action.TypeBrace, "    ‸", "    }‸", preview = false),
            row("EB-NUM-11 indent numbered", Action.Indent, "1. a\n2. b‸\n3. c", "1. a\n   1. b‸\n2. c"),
            // The Bullet key.
            row("bullet key on a plain line", Action.Bullet, "a‸", "- a‸"),
            row("EB-LIST-38 bullet key converts a numbered list", Action.Bullet, "1. a\n2. b‸\n3. c", "- a\n- b‸\n- c"),
            row("bullet key toggles off mid-list", Action.Bullet, "- a\n- b‸\n- c", "- a\n\nb‸\n- c"),
            row("EB-LIST-36 bullet key on selected lines", Action.Bullet, "«a\nb»", "«- a\n- b»"),
            row(
                "EB-LIST-37 bullet key between paragraphs",
                Action.Bullet,
                "One\nTwo‸\nThree",
                "One\n\n- Two‸\n\nThree",
            ),
            row(
                "bullet key on a nested numbered list",
                Action.Bullet,
                "- a\n  1. b‸\n  2. c\n- d",
                "- a\n  - b‸\n  - c\n- d",
            ),
            row("bullet key in a quote", Action.Bullet, "> 1. a‸\n> 2. b", "> - a‸\n> - b"),
            row("bullet key keeps task boxes", Action.Bullet, "1. [ ] a‸\n2. [x] b", "- [ ] a‸\n- [x] b"),
            row("bullet key moves nested lines", Action.Bullet, "1. a‸\n   - b\n2. c", "- a‸\n  - b\n- c"),
            // The Numbered key.
            row("numbered key on a plain line", Action.Numbered, "a‸", "1. a‸"),
            row(
                "EB-NUM-12 numbered key converts a bullet list",
                Action.Numbered,
                "- a\n- b‸\n- c",
                "1. a\n2. b‸\n3. c",
            ),
            row(
                "EB-NUM-13 numbered key toggles off mid-list",
                Action.Numbered,
                "1. a\n2. b‸\n3. c",
                "1. a\n\nb‸\n\n2. c",
            ),
            row("andrew-2 numbered key continues the list", Action.Numbered, "1. a\n2. b\n\nc‸", "1. a\n2. b\n\n3. c‸"),
            row("numbered key after a bullet list", Action.Numbered, "- a\n- b\n\nc‸", "- a\n- b\n\n1. c‸"),
            row("numbered key on selected items", Action.Numbered, "- «a\n- b»", "1. «a\n2. b»", preview = false),
            row(
                "numbered key on a nested bullet list",
                Action.Numbered,
                "1. a\n   - b‸\n   - c\n2. d",
                "1. a\n   1. b‸\n   2. c\n2. d",
            ),
            row("numbered key moves nested lines", Action.Numbered, "- a‸\n  - b\n- c", "1. a‸\n   - b\n2. c"),
            row("numbered key keeps task boxes", Action.Numbered, "- [ ] a‸\n- [x] b", "1. [ ] a‸\n2. [x] b"),
            row(
                "numbered key stops at another list",
                Action.Numbered,
                "- a\n- b‸\n\n* c",
                "1. a\n2. b‸\n\n* c",
            ),
            // The ¶ menu, mid-list.
            row("block numbered mid bullet list", Action.BlockNumbered, "- a\n- b‸\n- c", "- a\n1. b‸\n- c"),
            row("block paragraph mid numbered list", Action.BlockParagraph, "1. a\n2. b‸\n3. c", "1. a\n\nb‸\n\n2. c"),
            row("block bullet mid numbered list", Action.BlockBullet, "1. a\n2. b‸\n3. c", "1. a\n- b‸\n\n2. c"),
            row("block heading mid bullet list", Action.BlockHeading2, "- a\n- b‸\n- c", "- a\n## b‸\n- c"),
            row("block numbered on a numbered item", Action.BlockNumbered, "1. a\n2. b‸\n3. c", "1. a\n2. b‸\n3. c"),
            row("block quote mid list", Action.BlockQuote, "- a\n- b‸\n- c", "- a\n> b‸\n- c"),
            row("block paragraph multi-line item", Action.BlockParagraph, "- a\n  b‸\n- c", "- a\n\nb‸\n- c"),
            // The ¶ menu in fenced code: line-scoped (Andrew's second report).
            row("block paragraph code last line", Action.BlockParagraph, "```\na\nb‸\n```", "```\na\n```\nb‸"),
            row("block paragraph code empty last line", Action.BlockParagraph, "```\na\n‸\n```", "```\na\n```\n‸"),
            row(
                "EB-BLOCK-02 block paragraph code middle line",
                Action.BlockParagraph,
                "```js\na\nb‸\nc\n```",
                "```js\na\n```\nb‸\n```js\nc\n```",
            ),
            row("block paragraph code first line", Action.BlockParagraph, "```js\na‸\nb\n```", "a‸\n```js\nb\n```"),
            row("block paragraph whole code block", Action.BlockParagraph, "```\n«a\nb»\n```", "«a\nb»"),
            row(
                "block paragraph some code lines",
                Action.BlockParagraph,
                "```\na\n«b\nc»\nd\n```",
                "```\na\n```\n«b\nc»\n```\nd\n```",
            ),
            row("block heading code last line", Action.BlockHeading1, "```\na\nb‸\n```", "```\na\n```\n# b‸"),
            row(
                "block paragraph code with text after",
                Action.BlockParagraph,
                "```\na\nb‸\n```\nafter",
                "```\na\n```\nb‸\n\nafter",
            ),
            row(
                "block paragraph tilde fence",
                Action.BlockParagraph,
                "~~~~ py\na\nb‸\nc\n~~~~",
                "~~~~ py\na\n~~~~\nb‸\n~~~~ py\nc\n~~~~",
            ),
            row(
                "block paragraph code first line under text",
                Action.BlockParagraph,
                "x\n\n```\na‸\nb\n```",
                "x\n\na‸\n```\nb\n```",
            ),
            row("block code joins the block above", Action.BlockCode, "```\na\n```\nb‸", "```\na\nb‸\n```"),
            row("block code joins the block below", Action.BlockCode, "b‸\n```py\nc\n```", "```py\nb‸\nc\n```"),
            row(
                "block code joins both blocks",
                Action.BlockCode,
                "```\na\n```\nb‸\n```\nc\n```",
                "```\na\nb‸\nc\n```",
            ),
            row(
                "EB-BLOCK-10 block heading leaves a table alone",
                Action.BlockHeading2,
                "| a | b |\n| - | - |\n| 1 | 2‸ |",
                "| a | b |\n| - | - |\n| 1 | 2‸ |",
                preview = false,
            ),
            // The ¶ menu in quotes and alerts: line-scoped too.
            row(
                "EB-BLOCK-03 block paragraph one quote line",
                Action.BlockParagraph,
                "> a\n> b‸\n> c",
                "> a\n\nb‸\n> c",
            ),
            row(
                "block paragraph alert line",
                Action.BlockParagraph,
                "> [!NOTE]\n> a\n> b‸",
                "> [!NOTE]\n> a\n\nb‸",
            ),
        )

    /** The command a row runs in Code. Backspace is special: see [BlockEditingMatrixTest]. */
    @Suppress("CyclomaticComplexMethod") // One branch per action.
    fun command(action: Action): EditCommand =
        when (action) {
            Action.Enter -> EditCommand.Newline
            Action.Backspace -> EditCommand.BackspaceAtItemStart
            Action.Outdent -> EditCommand.Outdent
            Action.Indent -> EditCommand.Indent
            Action.Bullet -> EditCommand.ToggleList(ListKind.Bullet)
            Action.Numbered -> EditCommand.ToggleList(ListKind.Numbered)
            Action.BlockParagraph -> EditCommand.SetBlockType(BlockType.Paragraph)
            Action.BlockHeading1 -> EditCommand.SetBlockType(BlockType.Heading1)
            Action.BlockHeading2 -> EditCommand.SetBlockType(BlockType.Heading2)
            Action.BlockBullet -> EditCommand.SetBlockType(BlockType.Bullet)
            Action.BlockNumbered -> EditCommand.SetBlockType(BlockType.Numbered)
            Action.BlockQuote -> EditCommand.SetBlockType(BlockType.Quote)
            Action.BlockCode -> EditCommand.SetBlockType(BlockType.CodeBlock)
            Action.TypeBrace -> EditCommand.Type("}")
        }
}
