# Editing behaviours

The catalogue of editing behaviours a user expects from a Markdown
editor, block by block and action by action, in both editing surfaces:

- **Code**: the raw Markdown in `MarkdownCodeView`, driven by the edit
  commands in `core/markdown` (`…/markdown/edit`).
- **Preview**: tap-to-edit on the rendered page
  (`feature/preview/web/src`), which maps every edit onto the source.

**How it is used.** This is the source of the conformance test matrix.
Every row has an ID (`EB-LIST-01`…) and a **Status**: the test ID that
proves it, or `untested`. A follow-up maps each row to a test (host JVM
for Code, the page's unit/e2e suites for Preview, the CI emulator for IME
rows marked device-only) and fixes what fails. A row that turns out to be
wrong is changed here first, with the reason, then in code. A bug found
in the wild adds a row here *and* in `edge-cases.md`.

It complements, and does not repeat, `ux-spec.md` (what each control
does), `edge-cases.md` (hostile cases already handled) and
`robustness.md` (never lose work). Where this file and `ux-spec.md`
disagree, `ux-spec.md` wins until the row is reconciled.

## Notation

| Mark | Meaning |
|---|---|
| `‸` | the caret |
| `«…»` | the selection |
| `⏎` | a line break in the source |
| `·` | a space that matters (indentation, trailing spaces) |
| `→` | a tab character |
| **same** | Preview produces the same source text as Code, and renders it |
| **n/a** | the action or situation cannot happen in that surface (reason given) |

Priority: **P1** users hit it daily and a failure feels broken; **P2**
common; **P3** rare. Sources are the short keys in [Sources](#sources);
"brainstorm" means no source was found and the row comes from working
through the block or action ourselves.

The rules every row inherits, so they are not repeated in each one:

1. **Byte-faithful.** An edit changes only the lines (in Preview, the
   characters) it has to. The document's own style is kept: bullet char,
   `.` or `)` delimiter, `*` or `_`, padding, line endings.
2. **One action, one undo step**, and the caret and selection come back
   where they were on undo.
3. **Fence-aware.** No list, quote, heading or inline command acts on
   lines inside a fenced or indented code block, front matter or a math
   block, unless the row says so.
4. **The composing word is committed first** (R6) before any strip key,
   shortcut or Enter acts.
5. **Both surfaces give the same text.** Preview asks Kotlin to run
   Code's command where one exists, so the Preview column only differs
   where the surface truly differs.

## Lists: bullets and structure

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-LIST-01 | `- item‸` | Enter | `- item⏎- ‸` (same bullet char) | A new empty bullet below, caret in it | [CM], [MAIO], [GDOCS] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-01 andrew-1 bullet last item` |
| EB-LIST-02 | `* item‸` / `+ item‸` | Enter | Continues with the same char (`* `, `+ `) | same | [CM], [MAIO] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-02 bullet star keeps its char`; `EB-LIST-02 bullet plus keeps its char` |
| EB-LIST-03 | `- ite‸m` | Enter | Splits: `- ite⏎- ‸m` | Item splits in two at the caret | [CM], [PM] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-03 bullet caret mid-text splits` |
| EB-LIST-04 | `- ‸item` (caret right after the marker) | Enter | `- ⏎- ‸item`: an empty item above, text moves down with the caret | same | [MAIO], [CM] | P1 | untested |
| EB-LIST-05 | `- a⏎- ‸` (empty item, top level) | Enter | Marker removed; caret on a plain line, separated from the list by a blank line so typing is a paragraph, not a lazy line of `a` | The empty item disappears, caret in a new paragraph below the list | [CM], [MAIO], [GDOCS], [NOTION], [OB-54358], [BEAR-10240] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-05 empty last bullet` |
| EB-LIST-06 | `- a⏎··- ‸` (empty nested item) | Enter | Outdents one level: `- a⏎- ‸` | same | [MAIO], [PM], [GDOCS] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-06 empty nested outdents first` |
| EB-LIST-07 | Empty item in the middle: `- a⏎- ‸⏎- c` | Enter | Splits the list: `- a⏎⏎‸⏎- c` (two lists around a paragraph; a bullet list may follow a paragraph line directly); a numbered tail continues the count after a blank line, see EB-NUM-08 (changed 2026-11-03, Andrew's list brief) | same | [CM], [PM] (liftOutOfList splits) | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-07 empty middle bullet splits the list` |
| EB-LIST-08 | `- a⏎- «b»` | Enter | Selection replaced, then EB-LIST-01/03 applies | same | [PM] (splitListItem deletes the range) | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-08 selection across items` |
| EB-LIST-09 | `- item‸` with trailing spaces `- item··‸` | Enter | Trailing spaces on the old line kept (they are a hard break; rule 1); new item below | same | brainstorm, [CMS] §6.7 | P3 | untested |
| EB-LIST-10 | `-‸` or `- ` typed at line start then nothing | Enter | A line `-` alone is not an item with content; Enter on `- ‸` with no list above removes the marker and leaves an empty line | same (n/a until typed) | [CM] ("deletes the first list marker") | P2 | untested |
| EB-LIST-11 | `- a⏎··continuation‸` (indented paragraph of an item) | Enter | New line keeps the item's content indent `··‸`, no marker | Enter inside a multi-paragraph item makes a new paragraph in the same item | [CM] ("continues list items") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-11 indented paragraph of an item keeps its indent` |
| EB-LIST-12 | `- a⏎lazy line‸` (lazy continuation, no indent) | Enter | Plain newline, no marker added, no indent | Same paragraph of the item gets a new line (soft break) | [CM] ("continues dedented list items") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-12 lazy line of an item is a plain newline` |
| EB-LIST-13 | `- a⏎‸bc` (caret before the item's content column) | Enter | Plain newline; the list is not continued | same | [CM] ("cursor before the markup depth") | P2 | untested |
| EB-LIST-14 | `- [link](x)‸` / `- [test]‸` (bracket text that is not a task) | Enter | `- ⏎`: a bullet, never `- [ ]` | same | [MAIO] (#122) | P2 | untested |
| EB-LIST-15 | Loose list `- a⏎⏎- b‸` | Enter | Keeps looseness: `- a⏎⏎- b⏎⏎- ‸` | same | [CM] ("preserve non-tight list") | P2 | untested |
| EB-LIST-16 | Tight list `- a⏎- b‸` | Enter | Stays tight: no blank line inserted | same | [CM] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-16 bullet middle` |
| EB-LIST-17 | Tight two-item list, Enter on an empty second item | Enter | Exits (EB-LIST-05). We do **not** follow CodeMirror's default of turning a tight list loose here | same | [CM] (`nonTightLists` option); decision D11 | P2 | untested |
| EB-LIST-18 | `-   item‸` (marker followed by 3 spaces) | Enter | Keeps the item's text column: `-   ‸` | same | [CM] (" *  one"), [MAIO] ("1.  item1") | P3 | untested |
| EB-LIST-19 | ` - one‸` (list indented by one space) | Enter | Keeps the leading space: ` - ‸` | same | [CM] | P3 | untested |
| EB-LIST-20 | Tab-indented nested list `→- two‸` | Enter | Continues with a tab: `→- ‸` | same | [CM] ("handles tab-indentation") | P3 | untested |
| EB-LIST-21 | `- a⏎  - b‸` (nested) | Enter | `  - ‸` at the same nesting | same | [CM], [MAIO] | P1 | untested |
| EB-LIST-22 | `- a⏎- b‸` | Indent (strip, Tab) | `- a⏎  - b‸`, indented under `a` by `a`'s content width | Item nests under the one above | [PM] (sinkListItem), [MAIO], [GDOCS] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-22 indent bullet` |
| EB-LIST-23 | First item of a list `- ‸a` (no item above) | Indent | No change (an item cannot nest without a parent; four spaces at top would make an indented code block). The key gives a short "Can't indent the first item" announcement | same | [PM] (first item cannot sink); [MAIO] indents anyway; decision D3 | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-23 indent first item does nothing` |
| EB-LIST-24 | `- a⏎  - b‸` | Outdent (strip, Shift+Tab) | `- a⏎- b‸` | same | [PM] (liftListItem), [GDOCS] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-24 outdent nested` |
| EB-LIST-25 | `- a‸` top level | Outdent | The marker goes; `a` becomes a paragraph, separated by blank lines from list items around it; a numbered tail renumbers | same | [PM], [SSM] ux-spec | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-25 outdent top-level middle bullet` |
| EB-LIST-26 | Item with children `- a⏎- b‸⏎  - c` | Indent `b` | `b` and its child `c` move together: `  - b⏎    - c` | same | [PM] (sinks the range with its content), [GDOCS] | P1 | untested |
| EB-LIST-27 | `- a⏎  - b‸⏎  - c` (b has a following sibling) | Outdent `b` | `- a⏎- b⏎  - c`: following siblings become `b`'s children (they stay below it, at their depth) | same | [PM] (liftToOuterList) | P2 | untested |
| EB-LIST-28 | Selection over three items `«- a⏎- b⏎- c»` | Indent / Outdent | Every selected item moves one level; the selection grows to keep the same text selected | same | [PM], [MAIO] | P2 | untested |
| EB-LIST-29 | Item whose continuation lines are indented `- a⏎  more‸` | Indent | Continuation lines move with the item (the whole item, not only its first line) | same | brainstorm, [CMS] §5.2 | P2 | untested |
| EB-LIST-30 | `- ‸item` | Backspace | Marker removed. First item of a list, or the only item: `‸item` as a paragraph (blank line kept/added against the list). A later item: marker becomes indentation, so the text joins the item above as its continuation paragraph; a second Backspace removes the indent | The bullet goes; the text stays where it was | [CM] (deleteMarkupBackward), [MAIO], [GDOCS]; decision D2 | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-30 backspace first bullet` |
| EB-LIST-31 | `··- ‸item` (nested) | Backspace | Removes the marker and the indent: `‸item` is a plain paragraph (D2, decided 2026-11-03; the continuation step is not used) | same | [CM] ("one level"), [OB-104451] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-31 backspace nested makes a paragraph` |
| EB-LIST-32 | `- a⏎- ‸` (empty item) | Backspace | Removes the marker; a second Backspace joins the line to `a`'s end | same | [CM], [PM] (joinBackward) | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-32 backspace empty item` |
| EB-LIST-33 | Paragraph `‸text` right after a list `- a⏎‸text` (lazy line) | Backspace | Joins to `a`: `- a‸text` | Paragraph joins into the last item | [PM] (joinBackward) | P2 | untested |
| EB-LIST-34 | `- a‸⏎- b` | Delete (forward) | Joins: `- a‸b` (the second marker goes) | same | [PM] (joinForward) | P2 | untested |
| EB-LIST-35 | `«- a⏎- b»` | Bullet key | Removes the bullets (toggle off): `a⏎b` | same | [MTE] ("toggle off, multiple lines") | P1 | untested |
| EB-LIST-36 | Two plain paragraphs selected `«One⏎Two»` | Bullet key | `- One⏎- Two`; partly selected lines are included whole | same | [MTE] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-36 bullet key on selected lines` |
| EB-LIST-37 | Plain line between paragraphs `One⏎Two‸⏎Three` | Bullet key | Only that line: `One⏎⏎- Two‸⏎⏎Three` (blank lines added so it is a list, not a lazy line) | same | [MTE] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-37 bullet key between paragraphs` |
| EB-LIST-38 | `«1. a⏎2. b»` | Bullet key | Switches type in place: `- a⏎- b` (no stacking `- 1. a`) | same | [MTE] ("switching styles replaces the prefix") | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LIST-38 bullet key converts a numbered list` |
| EB-LIST-39 | Mixed selection: some lines bullets, some plain | Bullet key | All become bullets (toggle on wins when not all are on) | same | [PM] (wrapInList); brainstorm | P2 | untested |
| EB-LIST-40 | Blank lines inside the selection `«a⏎⏎b»` | Bullet key | Blank lines stay blank, never `- ` empty items; the list becomes loose | same | [MTE] (blank lines in quote case); brainstorm | P2 | untested |
| EB-LIST-41 | Bullet key on a heading line `## Title‸` | Bullet key | Heading syntax replaced: `- Title` | same | [SSM] ux-spec (¶ replaces syntax) | P2 | untested |
| EB-LIST-42 | Item with bullet `- a` inside a document whose style is `*` | Bullet key on a new line | New bullets use the document's dominant char (`*`) | same | [SSM] ux-spec (document style) | P2 | untested |
| EB-LIST-43 | Line `-not a list` (no space) or `-1` / `--` | Enter | Not a list; plain newline | same | [CMS] §5.2 (marker needs a space) | P2 | untested |
| EB-LIST-44 | `- ‸` and a thematic break look-alike `- - -` | Enter | A line of `- - -` is a thematic break, never continued as a list | same | [CMS] §4.1 | P3 | untested |
| EB-LIST-45 | Item that starts with a fence `- ```lang‸` | Enter | The fence opens inside the item; Enter adds a plain indented line, never a `- ` | same | [CM] ("doesn't take effect in fenced code"), [OB-96116] | P2 | untested |
| EB-LIST-46 | Code block inside an item, caret on the line after its closing fence | Enter | No stray `- ` is inserted after an indented code block | same | [OB-96585], [OB-96116] | P2 | untested |
| EB-LIST-47 | `- a‸` | Shift+Enter (hardware) or the keyboard's newline-without-continue | Code: newline with the item's content indent and no marker (a continuation line) | A visible line break inside the item (written as a hard break in the document's style) | [OB-96130], [GDOCS]; decision D14 | P2 | untested |
| EB-LIST-48 | List with two-space vs four-space nesting in the same file | Indent | New nesting uses the parent item's content column, not a fixed width | same | [CMS] §5.2; brainstorm | P2 | untested |
| EB-LIST-49 | Tap the bullet glyph in Preview | Tap | n/a (no glyph) | Caret goes to the start of the item text; the marker is never editable text | brainstorm | P2 | untested |
| EB-LIST-50 | Empty list item rendered in Preview (`- ` alone) | Tap it | n/a | Caret lands in the empty item; typing fills it | brainstorm | P2 | untested |

## Lists: numbered

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-NUM-01 | `1. one‸` | Enter | `1. one⏎2. ‸` | same | [CM], [MAIO] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-01 numbered last` |
| EB-NUM-02 | `2) one‸` (paren delimiter) | Enter | `2) one⏎3) ‸` | same | [CM], [CMS] §5.2 | P2 | untested |
| EB-NUM-03 | `1. one‸⏎2. two` (insert in middle) | Enter | Following items renumber: `1. one⏎2. ‸⏎3. two` | same | [CM] ("renumbers following ordered list items") | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-03 numbered first` |
| EB-NUM-04 | `9.  nine‸` | Enter | `10. ‸`: the text column is kept where possible (one space less after a wider number) | same | [MAIO] ("9.  item9") | P3 | untested |
| EB-NUM-05 | List starting at 5: `5. a‸` | Enter | `6. ‸`; the start number is kept (it is meaningful) | Renders from 5 and continues | [CMS] §5.3 (start number) | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-05 numbered keeps start and paren` |
| EB-NUM-06 | All-ones list `1. a⏎1. b‸` | Enter | `1. ‸` (the style "every item is 1." is kept; nothing renumbered) | same | [SSM] edge-cases (renumbering) | P2 | untested |
| EB-NUM-07 | Run with a discontinuity `1. a‸⏎2. b⏎3. c⏎1. d` | Enter | Renumbering stops at the discontinuity: `…4. c⏎1. d` | same | [CM] ("stops renumbering on discontinuities") | P3 | untested |
| EB-NUM-08 | `1. a⏎2. ‸⏎3. c` empty middle item | Enter | Splits the list; the tail continues the count: `1. a⏎⏎‸⏎⏎2. c` (a list starting at 2 cannot follow a paragraph line directly, hence the blank line; changed 2026-11-03 from "restarts at 1", Andrew's list brief) | same | [CM] ("renumbers after removed markers"), [SSM] | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-08 empty middle numbered continues numbering` |
| EB-NUM-09 | Delete a whole item line `1. a⏎«2. b⏎»3. c` | Cut / Backspace | Remaining items renumber: `1. a⏎2. c` | Deleting an item renumbers | brainstorm, [SSM] edge-cases | P2 | untested |
| EB-NUM-10 | Nested numbered `1. a⏎   1. b⏎   2. ‸` | Enter | Outdents one level and continues the outer count: `2. ‸` | same | [CM] ("lift out of one list level and renumber") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-10 empty nested numbered outdents first` |
| EB-NUM-11 | `1. a⏎2. b‸` | Indent | `b` becomes `1.` of a new sublist under `a`; outer list closes the gap | same | [SSM] edge-cases, [PM] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-11 indent numbered` |
| EB-NUM-12 | `«- a⏎- b⏎- c»` | Numbered key | `1. a⏎2. b⏎3. c` | same | [MTE] ("numbers increment across lines") | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-12 numbered key converts a bullet list` |
| EB-NUM-13 | `1. a⏎2. b‸` | Numbered key | Toggles off (removes the numbers) for the caret's item or the selection | same | [MTE] | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-NUM-13 numbered key toggles off mid-list` |
| EB-NUM-14 | `1. ‸` | Backspace | Removes the whole number marker in one go (not digit by digit) | same | [CM] ("clears number markers in one go") | P1 | untested |
| EB-NUM-15 | A paragraph `2024. was a year` | Enter at end | Not treated as a list continuation (number over 9 digits or a paragraph that cannot interrupt) only when the line really parses as a list item | same | [CMS] §5.2 (max 9 digits; only `1` interrupts a paragraph) | P3 | untested |
| EB-NUM-16 | User manually renumbers a list badly (`1. 3. 7.`) | Save / switch view | Numbers are never rewritten on save or view switch; only an edit to the list renumbers the run it touched | same | [MT2479], [GH39057] | P1 | untested |

## Lists: tasks

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-TASK-01 | `- [ ] buy milk‸` | Enter | `- [ ] ‸` | New unchecked task | [MAIO], [CM] | P1 | untested |
| EB-TASK-02 | `- [x] done‸` | Enter | The new task is unchecked: `- [ ] ‸` | same | [MAIO] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-TASK-02 task makes an unchecked task` |
| EB-TASK-03 | `- [x] ‸done` (caret at text start) | Enter | Empty unchecked task above; `done` keeps its `[x]` | same | [MAIO] | P2 | untested |
| EB-TASK-04 | `- [ ] ‸` (empty task) | Enter | Exits the list like EB-LIST-05 | same | [MAIO], brainstorm | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-TASK-04 empty task` |
| EB-TASK-05 | `- [ ] ‸item` | Backspace | Removes the checkbox only: `- ‸item` (a second Backspace removes the bullet) | The checkbox goes, the bullet stays | [MAIO] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-TASK-05 backspace task loses its box first` |
| EB-TASK-06 | `··- [ ] ‸item` (nested) | Backspace | `··- ‸item` | same | [MAIO] | P2 | untested |
| EB-TASK-07 | Nested task `- [ ] a⏎  - [ ] b‸` | Enter | `  - [ ] ‸` at the same depth | same | [CM] ("nested task lists at the right level") | P1 | untested |
| EB-TASK-08 | `- [ ] item` | Tap the checkbox | n/a | Toggles to `[x]`, one character changed, no keyboard, no caret move | [SSM] R12 | P1 | untested |
| EB-TASK-09 | Caret in `- [ ] item‸` | Task key | Removes the task box, leaving `- item‸` (the key toggles "is a task", it does not tick). Ticking is a tap in Preview or Ctrl+Enter | same | [MTE], [MAIO]; brainstorm | P2 | untested |
| EB-TASK-10 | Selection over tasks with mixed state | Toggle done (Ctrl+Enter) | If any is unchecked, all become checked; else all unchecked. Only lines touched by the selection | same | [MAIO] ("check multiple lines", "only touch lines that have selections") | P3 | untested |
| EB-TASK-11 | `* [X] Done` (capital X, `*` bullet) | Tap / toggle | Unchecks to `* [ ] Done`; checking writes `x` unless the file uses `X` | same | [GFM] §5.3; [SSM] rule 1 | P3 | untested |
| EB-TASK-12 | `1. [ ] numbered task` | Enter | `2. [ ] ‸` | same | [GFM] §5.3, brainstorm | P3 | BlockEditingMatrixTest, e2e/blockEditing: `EB-TASK-12 numbered task` |
| EB-TASK-13 | Plain paragraph `buy milk‸` | Task key | `- [ ] buy milk` | same | [SSM] ux-spec | P1 | untested |
| EB-TASK-14 | Bullet `- buy milk‸` | Task key | `- [ ] buy milk` (bullet kept, box added) | same | [SSM] ux-spec, brainstorm | P1 | untested |
| EB-TASK-15 | `- [ ] item` with a link `- [ ] see [doc](x)` | Tap the link text in Preview | n/a | Opens the link chip; never toggles the box | [SSM] ux-spec (gestures) | P2 | untested |

## Lists inside quotes and mixed nesting

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-LISTQ-01 | `> - one‸` | Enter | `> - one⏎> - ‸` | same | [CM] ("lists inside blockquotes") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LISTQ-01 bullet in quote` |
| EB-LISTQ-02 | `> - one⏎> - ‸` | Enter | Exits the list but stays in the quote: `> - one⏎>⏎> ‸` | same | [CM] ("move list markup inside a blockquote") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LISTQ-02 empty item in quote` |
| EB-LISTQ-03 | `> - ‸` | Backspace | Removes the list marker, keeps the quote: `> ‸` | same | [CM] ("deletes nested list markers") | P2 | untested |
| EB-LISTQ-04 | `- one⏎   > quoted‸` (quote inside an item) | Enter | `   > ‸` (keeps both the item indent and the quote) | same | [CM] ("leave space before nested blockquotes") | P3 | untested |
| EB-LISTQ-05 | `- a⏎  1. b‸` (numbered inside bullets) | Enter | `  2. ‸` | same | [CM] ("continue nested lists") | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-LISTQ-05 nested numbered in bullet` |
| EB-LISTQ-06 | `> 1. one⏎>⏎> 2. two‸` (loose list in quote) | Enter | `>⏎> 3. ‸` | same | [CM] ("preserve list tightness inside a blockquote") | P3 | untested |

## Block type (¶ menu) and conversions

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-BLOCK-01 | Caret in one line of a three-line paragraph | ¶ → Heading 2 | Only the caret's line becomes `## line`; the lines above and below stay paragraphs, separated by blank lines where they would otherwise join | same | [SSM] ux-spec; known case | P1 | untested |
| EB-BLOCK-02 | Caret on one line inside a fenced code block | ¶ → Paragraph | Splits the fence: the code above and below stays fenced (fences closed and reopened with the same info string), the line comes out as a paragraph | same | known case | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-BLOCK-02 block paragraph code middle line` |
| EB-BLOCK-03 | Caret on one line of a multi-line quote | ¶ → Paragraph | Splits the quote around that line | same | known case | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-BLOCK-03 block paragraph one quote line` |
| EB-BLOCK-04 | `- [x] done` | ¶ → Heading 1 | `# done` (task state dropped, text kept) | same | [SSM] ux-spec | P2 | untested |
| EB-BLOCK-05 | `## Title` | ¶ → Task | `- [ ] Title` | same | [SSM] ux-spec | P2 | untested |
| EB-BLOCK-06 | Selection over a heading, a list and a paragraph | ¶ → Quote | Every selected block is quoted, each line `> `; blank lines between them become `>` so it is one quote | same | [MTE] (multi-line quote), [SSM] | P2 | untested |
| EB-BLOCK-07 | Selection of three lines | ¶ → Code block | One fenced block around them; the fence is longer than any backtick run inside | same | [SSM] ux-spec, [CMS] §4.5 | P1 | untested |
| EB-BLOCK-08 | Caret in a fenced block | ¶ → Code block (already ticked) | No change; never nests fences | same | [SSM] ux-spec | P2 | untested |
| EB-BLOCK-09 | Setext heading `Title⏎=====` | ¶ → Paragraph | Both lines handled: underline removed, `Title` stays | same | [CMS] §4.3 | P2 | untested |
| EB-BLOCK-10 | Caret in a table row | ¶ | ¶ is disabled in a table: converting one row would break it | same | brainstorm | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-BLOCK-10 block heading leaves a table alone` |
| EB-BLOCK-11 | ¶ menu opened | Look at it | The caret's current type is ticked (a nested item ticks its list type) | same | [SSM] ux-spec | P2 | untested |
| EB-BLOCK-12 | `# Title #` (closing sequence) | ¶ → Heading 2 | `## Title` (closing hashes dropped), never `## Title #` with a stray mark | same | [CMS] §4.2 | P3 | untested |
| EB-BLOCK-13 | Line `#hashtag` or `1. 5 litres` | ¶ → Paragraph | Text that would still parse as syntax after a conversion is escaped (`\#hashtag`, `1\. 5`); text the user typed is never escaped behind their back | same | [CMS] §4.2, §5.2 | P3 | untested |
| EB-BLOCK-14 | Selection spanning a list and a fenced block | ¶ → Bulleted list | Code lines come out of the fence as items, taken literally; no fence line becomes an item | same | [SSM] ux-spec | P3 | untested |

## Code blocks

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-CODE-01 | Inside a fence `····foo()‸` | Enter | New line keeps the current line's leading whitespace; never adds a list marker or `> ` | Enter is a newline in the block, never a new paragraph | [CM], [PM] (newlineInCode), [OB-96116] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-CODE-01 code keeps indentation` |
| EB-CODE-02 | Inside a fence, line `- not a list‸` | Enter | Plain newline (no list continuation) | same | [SSM] edge-cases (FenceAwareEdit) | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-CODE-02 code never continues a list` |
| EB-CODE-03 | Inside a fence | Tab (hardware) or Indent key | Inserts indentation at the caret, or indents every selected line, in the block's own style (tab or spaces) | same | [TY-CF], [TT] (`enableTabIndentation`); decision D3 | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-CODE-03 indent code line` |
| EB-CODE-04 | Inside a fence | Shift+Tab or Outdent | Removes one indent level from the line(s) | same | [TY-CF] | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-CODE-04 outdent code line` |
| EB-CODE-05 | Last line of a fenced block, nothing after it | Leave the block | Arrow down passes the closing fence naturally (it is visible text) | Three ways out: Enter on an empty last line when the line before is also empty (both removed, new paragraph below); Shift+Enter or Ctrl+Enter at once; a tap below the block makes a paragraph there | [TT] (exitOnTripleEnter, exitOnArrowDown), [PM] (exitCode); decision D4 | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-CODE-05 code exit on a second empty last line` |
| EB-CODE-06 | A code block is the first block of the document | Put a paragraph above it | Type above the opening fence | Arrow up or a tap above the block makes a paragraph before it | [TT] (exitOnArrowUp) | P3 | untested |
| EB-CODE-07 | Empty fenced block, caret inside | Backspace | Deletes characters normally; the fence lines are text | The empty block becomes an empty paragraph (both fences removed) | [TT] (Backspace clears an empty block) | P2 | untested |
| EB-CODE-08 | Empty line, type ```` ``` ```` | Enter | A closing fence is added and the caret sits between: ```` ```⏎‸⏎``` ```` — only when no fence below would pair with it | Typing ```` ``` ```` then space or Enter in an empty paragraph makes a code block | [TY-CF], [TT] (input rule) | P1 | untested |
| EB-CODE-09 | Type ```` ```python ```` | Enter | As EB-CODE-08, keeping `python` as the info string | Code block tagged `python` | [TT], [TY-CF] | P2 | untested |
| EB-CODE-10 | ```` ``` ```` typed above existing text with no close | Type | Everything below is code until closed (CommonMark); highlighting converges without lag | Same rendering | [CMS] §4.5, [SSM] edge-cases | P2 | untested |
| EB-CODE-11 | `~~~` fence | Enter, Code key, ¶ | Treated exactly as a backtick fence; a closing fence we write matches it (`~~~`) | same | [CMS] §4.5 | P2 | untested |
| EB-CODE-12 | Four-backtick fence holding a line of three | Edit the inner line | The inner line is content, not a close; no command treats it as one | same | [CMS] §4.5 | P2 | untested |
| EB-CODE-13 | `puts «'Hi'»` | Code key | Inline code: ``puts `'Hi'` `` | same | [MTE] | P1 | untested |
| EB-CODE-14 | Selection across several lines | Code key | Fenced block around the lines | same | [MTE] | P1 | untested |
| EB-CODE-15 | Caret in a fenced block, nothing selected | Code key | Unwraps the block (fences removed, text kept) | same | [MTE] ("toggle off fenced block"), [SSM] edge-cases | P2 | untested |
| EB-CODE-16 | `` `in‸line` `` | Code key | Removes the backticks around that span | Code toggle shows pressed; tap removes the code mark | [MTE] ("toggle off inline") | P1 | untested |
| EB-CODE-17 | Caret inside a fence | Bold, Italic or Link key | Disabled: never inserts markers into code | Disabled while the caret is in a code block | rule 3; brainstorm | P1 | untested |
| EB-CODE-18 | Indented code block (4 spaces) | Enter | Next line keeps the 4-space indent | same | [CMS] §4.4 | P3 | untested |
| EB-CODE-19 | Typing `"` or `--` in code | Type | The app never applies smart punctuation in code (the keyboard's own is the user's) | Code spans and blocks never get smart typography in render or edit | [MK] #2819, [SSM] R9 | P1 | untested |
| EB-CODE-20 | Code block in Preview | Its copy button | n/a (no button in Code) | Copies the exact source of the block body | [SSM] R9 | P1 | untested |
| EB-CODE-21 | Long code line in Preview | Type at its end | n/a | The block scrolls sideways inside itself and keeps the caret in view | [SSM] ux-spec (wrap modes) | P2 | untested |
| EB-CODE-22 | ```` ```js ```` | Change the info string | Highlighting switches language | A body edit never loses the info string | brainstorm | P3 | untested |
| EB-CODE-23 | ``a`b`` selected | Code key | Uses a longer delimiter with padding: ``` ``a`b`` ``` | same | [CMS] §6.1 | P3 | untested |
| EB-CODE-24 | Paste code into a fenced block | Paste | Pasted literally: no smart quotes, no link conversion, no list continuation, tabs kept | Pasted as plain text into the block, indentation kept | known case; [MK] #2819 | P1 | `PasteMatrixTest` (FENCE), `e2e/paste.test.ts` (EB-LINK-08 / EB-CODE-24) |

## Quotes and alerts

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-QUOTE-01 | `> one‸` | Enter | `> one⏎> ‸` | New line in the same quote | [CM], [MAIO] | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-QUOTE-01 quote continues` |
| EB-QUOTE-02 | `> one⏎> ‸` (empty quoted line) | Enter | Ends the quote: the empty `> ` line is removed and the caret sits on a plain line after a blank line | The empty line leaves the quote as a paragraph | [CM] (two empty lines), [NOTION]; decision D5 | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-QUOTE-02 empty quote line ends the quote` |
| EB-QUOTE-03 | `> > one‸` | Enter | `> > one⏎> > ‸` | same | [CM] | P2 | untested |
| EB-QUOTE-04 | `> > ‸` (empty nested line) | Enter | Steps out one level: `> ‸` | same | [CM] | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-QUOTE-04 empty nested quote steps out one level` |
| EB-QUOTE-05 | `>>one‸` (no-space style) | Enter | `>>‸`: the file's spacing is kept | same | [CM] | P3 | untested |
| EB-QUOTE-06 | `> ‸` | Backspace | Removes one quote level (`> > ‸` → `> ‸`; `> ‸` → `‸`) | The line leaves the quote | [CM] | P1 | untested |
| EB-QUOTE-07 | `>···‸` (extra spaces) | Backspace | Deletes the trailing spaces back to `> ‸` first | same | [CM] ("deletes trailing whitespace") | P3 | untested |
| EB-QUOTE-08 | `> ‸text` | Backspace | Removes `> `; the line leaves the quote (a blank line added if it would be a lazy line) | same | [CM], [GDOCS] | P2 | untested |
| EB-QUOTE-09 | `«Hey,⏎⏎Thanks»` | Quote key | `> Hey,⏎>⏎> Thanks` (blank lines become `>`) | same | [MTE] | P1 | untested |
| EB-QUOTE-10 | Quote with caret or selection in it | Quote key | Toggles off: markers removed from every line of the block | same | [MTE] | P1 | untested |
| EB-QUOTE-11 | `dog‸` at the end of a paragraph line | Quote key | Quotes only that line, separated from the paragraph by a blank line | same | [MTE] | P2 | untested |
| EB-QUOTE-12 | Lazy line `> one⏎two‸` | Enter | Plain newline; `> ` is not added to a lazy line | Same paragraph of the quote | [CMS] §5.1 | P3 | untested |
| EB-QUOTE-13 | Alert `> [!NOTE]⏎> text‸` | Enter | `> ‸`; the `[!NOTE]` line is never duplicated | The alert keeps its icon and title; Enter adds a body line | [GFM-ALERT] | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-QUOTE-13 alert continues` |
| EB-QUOTE-14 | Alert in Preview | Tap the "Note" title | n/a | The type line is not editable text; the caret goes to the body | [GFM-ALERT]; brainstorm | P2 | untested |
| EB-QUOTE-15 | `> [!WARNING]‸` | Enter | `> ‸` (first body line) | same | [GFM-ALERT] | P3 | untested |
| EB-QUOTE-16 | `> ## Title‸` | Enter | `> ‸`: a paragraph line in the quote, not another heading | same | brainstorm | P3 | untested |
| EB-QUOTE-17 | `> > text‸` | Enter | Never inserts `- ` | same | [OB-95842] | P2 | untested |

## Headings

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-HEAD-01 | `## Title‸` | Enter | A plain paragraph line, never a second heading | New paragraph below the heading | [GDOCS], [NOTION], [PM] (splitBlock) | P1 | BlockEditingMatrixTest, e2e/blockEditing: `EB-HEAD-01 heading end` |
| EB-HEAD-02 | `## Ti‸tle` | Enter | `## Ti⏎‸tle`; the second half is a paragraph | same | [PM] (splitBlock) | P2 | BlockEditingMatrixTest, e2e/blockEditing: `EB-HEAD-02 heading middle splits` |
| EB-HEAD-03 | `## ‸Title` | Enter | An empty line above; the heading is unchanged and moves down with the caret | same | [PM], [GDOCS] | P2 | untested |
| EB-HEAD-04 | `## ‸Title` | Backspace | Deletes one character of the marker (it is text) | The heading becomes a paragraph `Title` | [GDOCS], [NOTION], [PM] | P2 | untested |
| EB-HEAD-05 | Empty paragraph | Type `# ` | It is a heading as typed | `# ` at the start of an empty paragraph makes Heading 1; Backspace straight after (or Undo) gives back the literal `# ` | [TT] (input rules), [NOTION] | P2 | untested |
| EB-HEAD-06 | `#Title` (no space) | Render, ¶ | Not a heading; ¶ shows Paragraph | Shown as text | [CMS] §4.2 | P2 | untested |
| EB-HEAD-07 | Setext `Title‸⏎-----` | Enter | Treat like EB-HEAD-01: new paragraph after the underline (a line inserted between would break the heading) | same | [CMS] §4.3; brainstorm | P3 | untested |
| EB-HEAD-08 | Paragraph line, user types `---` on the next line | Type | It becomes a setext H2 (CommonMark); outline updates; nothing auto-fixed | Renders as a heading | [CMS] §4.3 | P3 | untested |
| EB-HEAD-09 | `## A **b**‸` | Bold, Italic | Work as in a paragraph | same | brainstorm | P3 | untested |
| EB-HEAD-10 | `## Title` | ¶ → Heading 2 again | No change; never `#### Title` | same | [SSM] | P2 | untested |
| EB-HEAD-11 | `###### Six` | ¶ | Shown as a heading of level 6 even though the menu offers 1–3; picking one replaces `######` | same | [CMS] §4.2 | P3 | untested |
| EB-HEAD-12 | `   ## Indented` (up to 3 spaces) | ¶ → Paragraph | Marker removed, indentation dropped only if it would make code | same | [CMS] §4.2 | P3 | untested |

## Tables

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-TABLE-01 | Caret on an empty line | Table key | A small valid GFM table (header, delimiter, one row) with blank lines around it; caret in the first header cell | Table appears with the caret in its first cell | [SSM] ux-spec | P1 | untested |
| EB-TABLE-02 | Table key on a line with text | Table key | The table goes below the line, never inside the paragraph | same | brainstorm | P2 | untested |
| EB-TABLE-03 | Caret in a cell | Tab / Shift+Tab (hardware) | Next / previous cell, selecting its text | Next / Prev cell keys and Tab do the same | [SSM] ux-spec; brainstorm | P2 | untested |
| EB-TABLE-04 | Last cell of the last row | Next cell | Adds a row and moves into it | same | [SSM] ux-spec | P1 | untested |
| EB-TABLE-05 | Caret in a cell | Enter | Moves to the same column in the next row (adding one at the end); never writes a line break inside the row | Same; Shift+Enter writes `<br>` | [GFM] §4.10 (one row per line); brainstorm | P1 | untested |
| EB-TABLE-06 | Caret in a cell | Type `\|` | Literal (the user is editing syntax) | Written as `\|` so the cell does not split | [GFM] §4.10, [SSM] ux-spec | P1 | untested |
| EB-TABLE-07 | Multi-line clipboard | Paste into a cell | Pasted as is | Lines joined with `<br>`, pipes escaped, so the table survives | known case (paste per block) | P1 | `unit/paste.test.ts` (EB-TABLE-07), `e2e/paste.test.ts` |
| EB-TABLE-08 | Padded table | Type in a cell | Nothing re-padded automatically | Only the edited column is re-padded, by display width | [SSM] ux-spec | P2 | untested |
| EB-TABLE-09 | Only one body row | Delete row | A header-only table remains | same | [SSM] ux-spec | P2 | untested |
| EB-TABLE-10 | Column | Align centre | Only that column's delimiter changes (`:---:`) | same | [SSM] ux-spec | P2 | untested |
| EB-TABLE-11 | Text in a cell | Bold key | `**text**` inside the cell only | same | [SSM] edge-cases | P1 | untested |
| EB-TABLE-12 | Selection across cells | Delete or type | Ordinary text edit (the user owns the syntax) | Clears the cells' content, never a pipe; typing goes into the first cell | brainstorm | P2 | untested |
| EB-TABLE-13 | Start of the first cell | Backspace | Deletes the `\|` (it is text) | Nothing happens; never joins with the block above | [PM] (joinBackward stops at isolating nodes) | P2 | untested |
| EB-TABLE-14 | Line straight after a table | Type text | It becomes a body row (GFM); so every command that adds a paragraph after a table adds a blank line first | Typing below a table starts a paragraph, never a row | [GFM] §4.10 | P2 | untested |
| EB-TABLE-15 | Table without outer pipes | Add or delete a column | Stays valid (a pipe added where an outer cell is empty) | same | [SSM] ux-spec | P3 | untested |
| EB-TABLE-16 | Wide table in Preview | Swipe sideways, then tap | n/a | Scrolls in its own box; a tap within 500 ms of the swipe does not start editing | [SSM] ux-spec | P2 | untested |
| EB-TABLE-17 | Any table op | Undo | One step restores the table exactly | same | rule 2 | P1 | untested |
| EB-TABLE-18 | Selection of tab- or comma-separated lines | Table key | Converts them to a table, one column per field | same | brainstorm | P3 | untested |

## Inline formatting

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-INLINE-01 | `The «quick» brown` | Bold | `The **«quick»** brown`; the same text stays selected | Selected text turns bold, still selected | [MTE], [MAIO] | P1 | untested |
| EB-INLINE-02 | `**«quick»**` (inner text selected) | Bold | Toggles off: `«quick»` | same | [MTE], [MAIO] | P1 | untested |
| EB-INLINE-03 | `«**quick**»` (markers inside the selection) | Bold | Toggles off: `«quick»` | n/a (markers are not selectable in Preview) | [MTE] ("selected outer text") | P1 | untested |
| EB-INLINE-04 | `The qui‸ck brown` (caret in a word) | Bold | Wraps the word: `The **qui‸ck** brown`, caret stays at the same letter | Same word turns bold | [MTE], [MAIO]; decision D6 | P1 | untested |
| EB-INLINE-05 | `**qui‸ck**` | Bold | Unwraps: `qui‸ck` | Bold shows pressed; tap removes it from the word | [MTE], [MAIO] | P1 | untested |
| EB-INLINE-06 | `The ‸` (caret at whitespace or an empty line) | Bold | `The **‸**`: an empty pair, caret inside; Bold again straight away removes the pair | Bold becomes "armed": the next typed text is bold; if nothing is typed, nothing is written | [MAIO] (empty pair at whitespace), [PM] (stored marks); decision D6 | P1 | untested |
| EB-INLINE-07 | `**text‸**` (caret just before the closing markers) | Bold | Steps over the closer: `**text**‸` (finish the bold and keep typing) | Typing after leaving bold is plain | [MAIO] (caret before the closer) | P2 | untested |
| EB-INLINE-08 | `«Hello world·»` (trailing space or newline selected, typical of a double-tap) | Bold | Whitespace stays outside: `**Hello world**·`, never `**Hello world **` (which would not render) | same | [MTE], [PM] (whitespace skipped) | P1 | untested |
| EB-INLINE-09 | Selection spans two paragraphs `«one⏎⏎two»` | Bold | Each paragraph wrapped on its own: `**one**⏎⏎**two**`; markers never span a blank line | Both paragraphs bold | brainstorm, [CMS] §6.2 | P2 | untested |
| EB-INLINE-10 | Selection over list items `«- a⏎- b»` | Bold | Each item's text wrapped after its marker: `- **a**⏎- **b**` | same | [MAIO] (strike in list item); brainstorm | P2 | untested |
| EB-INLINE-11 | `- [ ] text text‸` | Strike (or Bold) with caret at end of the item | Wraps the item's text, not the marker: `- [ ] ~~text text~~‸` | same | [MAIO] ("task list item") | P3 | untested |
| EB-INLINE-12 | `a «**b** c»` (partly bold) | Bold | Makes all of it bold and merges: `a **b c**` (Docs convention: on if any part is off) | same | [GDOCS]; [PM] removes instead; decision D7 | P1 | untested |
| EB-INLINE-13 | `**a «b» c**` (selection inside a bold run) | Bold | Unbolds just that part: `**a** b **c**` | same | [GDOCS], [PM] | P2 | untested |
| EB-INLINE-14 | Selection starting and ending mid-word `qu«ick bro»wn` | Bold | Wraps exactly the selection; intraword `**` is valid. For italic, `*` is used, never `_` mid-word (it would not render) | same | [CMS] §6.2 (intraword `_`) | P2 | untested |
| EB-INLINE-15 | `«text»` | Italic | `*text*` by default, or `_text_` when the document already uses `_` | same | [MAIO], [MTE] (GitHub uses `_`), [SSM] DocumentStyle | P1 | untested |
| EB-INLINE-16 | `***both***` caret inside | Italic | Removes the italic only: `**both**` | Italic pressed; tap leaves bold | brainstorm, [CMS] §6.2 | P2 | untested |
| EB-INLINE-17 | `**bo‸ld**` | Italic | `***bo‸ld***` or `**_bo‸ld_**` in the document's style; both render bold italic | same | brainstorm | P2 | untested |
| EB-INLINE-18 | `«text»` | Strikethrough (⋮ or Ctrl+Shift+X) | `~~text~~`; toggle off removes it | same | [MAIO], [MTE], [GFM] §6.5 | P2 | untested |
| EB-INLINE-19 | Caret in bold text | Look at the strip | n/a (Code shows no toggle state, or shows it from the tokenizer) | Bold is shown pressed; leaving bold releases it | [SSM] ux-spec | P2 | untested |
| EB-INLINE-20 | `2 * 3 * 4` (literal asterisks) | Bold on `3` | `2 * **3** * 4`; existing literal stars untouched | same | brainstorm | P3 | untested |
| EB-INLINE-21 | Selection includes a link `«see [doc](x) now»` | Bold | `**see [doc](x) now**`; the link syntax is never cut | Link stays a link, now bold | brainstorm | P2 | untested |
| EB-INLINE-22 | Selection starts inside a link's URL `[doc](ht«tp://x) more»` | Bold | Expands to whole link: markers outside `[…](…)` | n/a (URL not visible) | brainstorm | P3 | untested |
| EB-INLINE-23 | Inside inline code `` `co‸de` `` | Bold | Disabled (markers in code are literal) | Disabled | rule 3 | P2 | untested |
| EB-INLINE-24 | Typing `**word**` by hand | Type | Highlighted live as bold | Typing `**word**` in Preview converts to bold when the closing `**` is typed (input rule); Undo gives the literal stars back | [TT] (mark input rules), [NOTION] | P2 | untested |
| EB-INLINE-25 | Typing `_` inside `snake_case_name` | Type | Literal; nothing converts | Never converts to italic mid-word | [CMS] §6.2 | P2 | untested |
| EB-INLINE-26 | `«text»` already `__text__` (underscore bold) | Bold | Removes `__` (recognises either style) | same | [MAIO] ("Use \_\_") | P2 | untested |
| EB-INLINE-27 | Bold applied, then typing at the end of the bold word in Preview | Type | n/a | Typed text at the end of a bold run continues bold (marks are inclusive at the end) and the source grows inside the `**` | [PM] (inclusive marks), [GDOCS] | P1 | untested |
| EB-INLINE-28 | Caret at the start of a bold word in Preview | Type | n/a | Typed text at the start is not bold (written before `**`) | [PM], [GDOCS] | P2 | untested |
| EB-INLINE-29 | `**bold**‸` | Backspace in Code | Deletes the `*` (it is text); no auto-removal of the opener | n/a: Backspace in Preview deletes the `d` of "bold" | brainstorm | P2 | untested |
| EB-INLINE-30 | Delete every character of a bold run in Preview | Backspace | n/a | The empty `****` is removed from the source too | brainstorm | P1 | untested |
| EB-INLINE-31 | `==highlight==`, `^sup^`, `~sub~`, `<u>u</u>`, `<kbd>` | Edit text inside | Ordinary text | Edited if the renderer maps it; otherwise the run is shown read-only with "Edit in Code" | [SSM] ux-spec | P3 | untested |
| EB-INLINE-32 | Escaped `\*not italic\*` | Edit near it | Escapes kept byte for byte | Shows `*not italic*`; typing next to it never drops the backslashes | rule 1; [CMS] §2.4 | P2 | untested |
| EB-INLINE-33 | Typing `*` in Preview | Type | n/a | Written escaped (`\*`) only when it would otherwise start emphasis; plain otherwise | [CMS] §2.4; brainstorm | P2 | untested |
| EB-INLINE-34 | Entity `&amp;` or `&nbsp;` in source | Edit beside it in Preview | n/a | The entity is kept as written; a typed `&` is written as `&` (escaped only if it would form an entity) | [CMS] §2.5 (entities) | P3 | untested |
| EB-INLINE-35 | Any text | Type `(`, `[`, `` ` ``, `*` or `"` | Only the typed character is inserted; no auto-closer | same | decision D15 | P2 | untested |

## Links and images

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-LINK-01 | `«homepage»` | Link | Sheet: text "homepage", address empty (or the clipboard's URL); Done writes `[homepage](address)` | same | [MTE], [SSM] ux-spec | P1 | untested |
| EB-LINK-02 | `is «https://example.com»` | Link | Sheet: address prefilled, text empty; Done with no text writes `[https://example.com](https://example.com)` or the typed text | same | [MTE] ("URL selected"), [SSM] | P1 | untested |
| EB-LINK-03 | Caret in a bare URL `https://exa‸mple.com` | Link | The whole URL is taken as the address | same | [MTE] ("cursor inside URL") | P2 | untested |
| EB-LINK-04 | Caret inside `[doc](x)` | Link | "Edit link" sheet prefilled; Remove link leaves `doc` | same | [SSM] ux-spec | P1 | untested |
| EB-LINK-05 | `«text»` selected, clipboard holds a URL | Paste | Becomes `[text](url)`; one Undo turns it back into a plain replace, a second restores `text` | same | [GH-LINK], [OB-PASTE], [GL], [MAIO] (paste link); decision D9 | P1 | `PasteMatrixTest` (EB-LINK-05), `e2e/paste.test.ts` (EB-LINK-05) |
| EB-LINK-06 | `«text»` selected, clipboard holds text that is not a URL | Paste | Plain replace | same | [GH-LINK] | P1 | `PasteMatrixTest` (EB-LINK-06 to 08), `e2e/paste.test.ts` (EB-LINK-06) |
| EB-LINK-07 | Selected URL, clipboard holds a URL | Paste | Plain replace (never a link of a URL to a URL) | same | [OB-PASTE] (complaint) | P2 | `PasteMatrixTest` (EB-LINK-06 to 08), `unit/paste.test.ts` (EB-LINK-07) |
| EB-LINK-08 | Selection inside code | Paste a URL | Plain replace (no link in code) | same | rule 3 | P2 | `PasteMatrixTest` (EB-LINK-06 to 08), `e2e/paste.test.ts` (EB-LINK-08) |
| EB-LINK-09 | Link in Preview | Tap | n/a | Chip "Open · Copy link · Edit link"; the caret does not move into the link text until Edit | [SSM] ux-spec | P1 | untested |
| EB-LINK-10 | Link in Preview | Type at its end | n/a | Typed text after the last character is **outside** the link (links are not inclusive at the end) | [PM] (link mark `inclusive: false`), [GDOCS] | P1 | untested |
| EB-LINK-11 | Link in Preview, caret in its text | Type | n/a | Edits the link's text only; the URL is untouched | brainstorm | P1 | untested |
| EB-LINK-12 | Text with `]` or address with spaces or `(` `)` | Link → Done | Brackets escaped in the text; address wrapped in `<>` | same | [SSM] ux-spec, [CMS] §6.3 | P2 | untested |
| EB-LINK-13 | Reference link `[doc][ref]` | Edit link | Edits the definition, or offers to; never converts it to inline silently | Reference definitions are read-only with "Edit in Code" | [SSM] ux-spec | P3 | untested |
| EB-LINK-14 | Autolink `<https://x.y>` or a bare URL | Tap in Preview | n/a | Same chip as EB-LINK-09 | [GFM] §6.9 | P2 | untested |
| EB-LINK-15 | Typing a URL then space | Type | Left as typed (GFM autolinks it on render) | Same; no conversion to `[x](x)` | [GFM] §6.9 | P2 | untested |
| EB-LINK-16 | `«logo»` | Image key | Picker for a picture; writes `![logo](relative/path)`; alt text = selection | same | [MTE], [SSM] ux-spec (Pictures) | P2 | untested |
| EB-LINK-17 | Image in Preview | Tap | n/a | Opens the viewer; never a caret inside the image | [SSM] ux-spec | P1 | untested |
| EB-LINK-18 | Image inline in a paragraph | Backspace right after it in Preview | n/a | First Backspace selects the image (or deletes it as one unit), never half of its syntax | [PM] (selectNodeBackward) | P2 | untested |
| EB-LINK-19 | Image | Edit alt text | In the source | Offered from the viewer or "Edit in Code"; alt text never lost on a nearby edit | brainstorm | P3 | untested |
| EB-LINK-20 | Wiki link `[[Page]]` (not CommonMark) | Type `[[` | Plain text; the closer is not auto-inserted | Rendered as text | brainstorm | P3 | untested |

## Line and paragraph breaks

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-BREAK-01 | `Hello‸ world` in a paragraph | Enter | A line break in the source: `Hello⏎‸ world` (a soft break, rendered as a space). Code is plain text: Enter is just a newline | Enter starts a new paragraph: `Hello⏎⏎world` | [GDOCS], [PM] (splitBlock); decision D14 | P1 | untested |
| EB-BREAK-02 | Paragraph in Preview | Shift+Enter | Newline (Code has no distinction) | A visible line break in the same paragraph, written as a hard break in the file's style (`\` or two trailing spaces; `\` when the file has none) | [TY-KEYS], [GDOCS], [CMS] §6.7 | P2 | untested |
| EB-BREAK-03 | Line ending with two spaces `line··` | Any edit elsewhere, save | The trailing spaces are kept (they are a hard break) | Rendered as a break; editing the line's text keeps them | [CMS] §6.7, [MK] #1274 | P1 | untested |
| EB-BREAK-04 | Hard break `line··⏎next` | Delete the line break in Preview (Backspace at start of `next`) | n/a | Joins the lines and removes the two spaces too | brainstorm | P2 | untested |
| EB-BREAK-05 | Two soft-wrapped lines `one⏎two` (one paragraph) | Backspace at the start of `two` in Preview | n/a | Preview shows "one two" on one line; Backspace at "two" deletes the space between them, i.e. the source line break | brainstorm | P2 | untested |
| EB-BREAK-06 | Empty paragraph between two paragraphs | Enter on an empty line in Preview | n/a | Several empty paragraphs are allowed while typing but collapse to one blank line in the source (Markdown cannot hold more); the caret stays where the user expects | brainstorm | P2 | untested |
| EB-BREAK-07 | Paragraph `a‸` with paragraph `b` two lines below | Delete forward at end of `a` in Preview | n/a | Joins `b` onto `a` (removes the blank line) | [PM] (joinForward) | P2 | untested |
| EB-BREAK-08 | Thematic break `---` | Tap or Backspace after it in Preview | n/a | Selected as a unit, deleted as one line | [PM] (selectNodeBackward) | P3 | untested |
| EB-BREAK-09 | Paragraph ending with `\` | Edit in Preview | n/a | The backslash break is kept | [CMS] §6.7 | P3 | untested |
| EB-BREAK-10 | Mixed CRLF file | Enter | New breaks use the file's dominant ending | same | [SSM] robustness | P1 | untested |

## Front matter, footnotes and HTML

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-FM-01 | YAML front matter `---⏎title: x⏎---` | Enter inside it | Plain newline; no list, quote or heading behaviour; `- ` in YAML lists is continued only as text (not as Markdown list syntax) | Read-only block with "Edit in Code" | [SSM] ux-spec | P2 | untested |
| EB-FM-02 | Front matter | Bold, ¶, quote keys | Disabled inside it | n/a (not editable) | rule 3 | P2 | untested |
| EB-FM-03 | Front matter at the top, caret in the first heading below | Edit | Front matter bytes untouched | same | rule 1 | P1 | untested |
| EB-FM-04 | `---` thematic break as the first line without a closing `---` | Render | Not taken as front matter unless closed | same | brainstorm | P3 | untested |
| EB-FM-05 | TOML `+++` front matter | Edit | As YAML | Read-only chip | brainstorm | P3 | untested |
| EB-FN-01 | `text[^1]` and `[^1]: note` | Edit the definition in Preview | Ordinary text | The definition is read-only with "Edit in Code"; the reference is a link to it | [SSM] ux-spec | P2 | untested |
| EB-FN-02 | Footnote definition `[^1]: note‸` | Enter | Plain newline; continuation lines need indent (`····`) and are not auto-added | n/a | [GFM-FN] | P3 | untested |
| EB-FN-03 | Footnote reference in Preview | Backspace right after it | n/a | Deletes the whole reference `[^1]` as one unit, never `[^1` | brainstorm | P3 | untested |
| EB-FN-04 | Footnote reference | Tap in Preview | n/a | Scrolls to the definition; back returns | [GFM-FN] | P3 | untested |
| EB-HTML-01 | HTML block `<div>…</div>` | Enter inside it | Plain newline; no Markdown continuation | Read-only with "Edit in Code" | [CM] ("doesn't do anything in non-Markdown content"), [SSM] | P2 | untested |
| EB-HTML-02 | `- <div‸>` (HTML inside a list item) | Enter | No list continuation inside the HTML tag | n/a | [CM] | P3 | untested |
| EB-HTML-03 | Inline HTML `<b>x</b>` or `<br>` in a paragraph | Type next to it in Preview | n/a | Editing the paragraph keeps the inline HTML byte for byte; the HTML itself is not editable | rule 1 | P2 | untested |
| EB-HTML-04 | HTML comment `<!-- note -->` | Edit near it | Kept | Invisible in Preview, yet never deleted by an edit to the block around it | brainstorm | P2 | untested |
| EB-HTML-05 | `<details><summary>` | Tap | n/a | Opens or closes the details; the content is read-only | brainstorm | P3 | untested |

## Maths and Mermaid

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-MATH-01 | `$$…$$` block or ```` ```math ```` | Enter, Tab inside | Plain text editing like a fence; no list or quote behaviour | Not editable: a tap opens the viewer; "Edit in Code" jumps to it | [SSM] ux-spec | P2 | untested |
| EB-MATH-02 | Inline `$x^2$` in a paragraph | Type next to it in Preview | n/a | The formula is one non-editable unit; typing beside it edits the text; Backspace after it deletes the whole formula as one unit (undoable) | [PM] (atom nodes), brainstorm | P2 | untested |
| EB-MATH-03 | `$5 and $6` (money, not maths) | Type | Plain text | Not rendered as maths; editable as text | brainstorm | P2 | untested |
| EB-MATH-04 | Mermaid block | Tap in Preview | n/a | Opens the viewer; never a caret in the SVG | [SSM] ux-spec | P1 | untested |
| EB-MATH-05 | Mermaid block | Select across it in Preview | n/a | A selection that spans it and is deleted removes the whole block from the source (one undo step); a partial delete never leaves half a fence | brainstorm | P2 | untested |
| EB-MATH-06 | Diagram with a syntax error | Edit in Code | Errors shown in Preview as an inline box with the source; typing never blocks | Error box, not a blank | [SSM] edge-cases | P2 | untested |
| EB-MATH-07 | Bold or ¶ with the caret beside a formula in Preview | Strip key | n/a | Acts on the text block, never rewrites the formula | rule 1 | P3 | untested |

## Selection

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-SEL-01 | Any text | Double-tap a word | Selects the word (grapheme- and word-break aware; `snake_case` is one word; markup chars `**` are not part of the word) | Selects the rendered word | [AND-SEL]; brainstorm | P1 | untested |
| EB-SEL-02 | Selection | Drag a handle | Extends by character, snapping to words when dragging fast as Android's TextView does; the handle never hides under the strip | same, inside the block or across blocks | [AND-SEL] | P1 | untested |
| EB-SEL-03 | Selection across two blocks in Preview | Type a letter | Replaces the selection; the two blocks merge into the first block's type | Same source result | [PM] (deleteSelection then join), [GDOCS] | P1 | untested |
| EB-SEL-04 | Selection from mid-list into a following paragraph | Delete | The paragraph tail joins the list item; no orphan marker remains | same | [PM]; brainstorm | P2 | untested |
| EB-SEL-05 | Selection from a heading into a code block | Delete | The heading keeps its type and takes the code tail as text; the fence is closed or removed so the rest of the document is not swallowed | same | brainstorm | P2 | untested |
| EB-SEL-06 | Any | Select all (toolbar, Ctrl+A) | Selects the whole document, including front matter | Selects the whole document (not just the block being edited), including read-only blocks | [AND-KB], [PM] (selectAll) | P1 | untested |
| EB-SEL-07 | Select all | Type / Delete | Replaces the whole document; one undo restores it | same | brainstorm | P1 | untested |
| EB-SEL-08 | Select all | Bold, ¶, quote | Applied to every block (bold per paragraph; ¶ per block); code and front matter skipped | same | rule 3; brainstorm | P3 | untested |
| EB-SEL-09 | Selection in Preview that starts in an editable block and ends in a Mermaid diagram | Copy | The copy includes the diagram's source | same | [SSM] ux-spec (Copy formatted) | P2 | untested |
| EB-SEL-10 | Long selection | Scroll while dragging a handle to the screen edge | Auto-scrolls | same | [AND-SEL] | P2 | untested |
| EB-SEL-11 | Selection | Rotate, switch Code ↔ Preview | The same source range stays selected (or the caret at its start if it cannot map) | same | [SSM] product-journey (never lose your place), R7 | P2 | untested |
| EB-SEL-12 | Shift+arrow (hardware) | Extend | By character / word (Ctrl) / line; Shift+Home/End to line ends | same (WebView) | [AND-KB] | P2 | untested |

## Undo and redo

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-UNDO-01 | Typed a sentence with Gboard | Undo | Removes a word or a burst of typing, not one letter (typing groups by pause and word) | same | [SSM] edge-cases | P1 | untested |
| EB-UNDO-02 | Enter that continued a list | Undo | One step removes the new line and marker together | same | rule 2 | P1 | untested |
| EB-UNDO-03 | Enter on an empty item that exited the list | Undo | Brings back the empty item with the caret in it | same | rule 2 | P1 | untested |
| EB-UNDO-04 | A renumbering Enter (`1.`→`2.` …) | Undo | Restores the numbers too, in one step | same | rule 2 | P1 | untested |
| EB-UNDO-05 | Any strip command | Undo | One step; caret and selection restored to before the command | same | rule 2 | P1 | untested |
| EB-UNDO-06 | Input rule conversion in Preview (`# `, `**x**`, ```` ``` ````) | Undo straight after | n/a: Code has no input rules | Gives back the literal characters typed, not the empty line | [TT] (undoInputRule), [NOTION] | P2 | untested |
| EB-UNDO-07 | Paste URL over selection → link | Undo | First undo: plain replace; second: original text | same | decision D9 | P2 | `PasteMatrixTest` (EB-UNDO-07), `e2e/paste.test.ts`, `PreviewSyncTest` (Split) |
| EB-UNDO-08 | Edits in Preview then in Code | Undo repeatedly | One history across both, in order | same | [SSM] R5 | P1 | untested |
| EB-UNDO-09 | Undo, then type | Redo | Redo stack cleared by new typing | same | brainstorm | P2 | untested |
| EB-UNDO-10 | Undo with the caret far from the change | Undo | Scrolls to and shows the undone change | same | brainstorm | P1 | untested |
| EB-UNDO-11 | Composing word underlined | Undo | Commits the composing word first, then undoes; never leaves a stale underline | same | [SSM] R6, edge-cases | P1 | untested |
| EB-UNDO-12 | Replace all | Undo | One step | same | [SSM] ux-spec | P1 | untested |
| EB-UNDO-13 | Switch files and back | Undo | Each file keeps its own stack | same | [SSM] R5 | P1 | untested |
| EB-UNDO-14 | Save | Undo | Undo still works after a save (save is not a history barrier); the document turns unsaved again | same | brainstorm | P1 | untested |
| EB-UNDO-15 | Task tick in Preview | Undo | Unticks (one step) | n/a in Code (no tap) | [SSM] R12 | P2 | untested |
| EB-UNDO-16 | Keyboard's own Undo (Gboard toolbar), Ctrl+Z, strip Undo, ⋮ Undo | Undo | All four do the same, one shared stack | same | [SSM] edge-cases | P2 | untested |
| EB-UNDO-17 | Autocorrect replaced a word | Undo | Restores the word as typed (one step) | same | brainstorm | P2 | untested |

## Copy, cut and paste

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-CLIP-01 | Text selected | Copy | Copies the exact source | Copies the source Markdown of the selection by default (plain-text apps get Markdown; rich apps get formatted HTML) | [SSM] ux-spec (Copy formatted) | P1 | `PasteMatrixTest` (EB-CLIP-01), `e2e/paste.test.ts` (EB-CLIP-01) |
| EB-CLIP-02 | Selection inside a code block in Preview | Copy | n/a | Exact code, no smart quotes, no markup | [SSM] R9 | P1 | `e2e/paste.test.ts` (EB-CLIP-02) |
| EB-CLIP-03 | Selection that cuts through `**bo«ld**» text` in Preview | Copy | n/a | The copy is balanced: `**ld**` as formatted, `ld` as plain; never a stray `**` | brainstorm | P2 | `e2e/paste.test.ts` (EB-CLIP-03; copied as rendered text, so no stray `**`) |
| EB-CLIP-04 | Whole list item selected | Cut | Line removed with its terminator; numbered list renumbers | same | brainstorm | P2 | untested |
| EB-CLIP-05 | No selection | Cut / Copy (hardware Ctrl+X) | Does nothing (Android convention; VS Code's "cut the whole line" is not expected on a phone) | same | [AND-KB]; brainstorm | P3 | `PasteMatrixTest` (EB-CLIP-05) |
| EB-CLIP-06 | Paste plain text into a paragraph | Paste | Inserted as is | Inserted as text; Markdown characters in it are kept literally in the source (not escaped) when the user pastes Markdown | known case | P1 | untested |
| EB-CLIP-07 | Paste multi-line Markdown `- a⏎- b` into a list item | Paste | As is | Becomes list items at the caret's list; the item is split at the caret | known case; brainstorm | P1 | `unit/paste.test.ts`, `e2e/paste.test.ts` (EB-CLIP-07) |
| EB-CLIP-08 | Paste multi-line text into a list item `- foo‸` | Paste | As is (lines after the first are lazy lines) | The first line joins the item; later lines become continuation lines indented to the item, so the paste stays inside it | known case; decision D10 | P1 | `unit/paste.test.ts`, `e2e/paste.test.ts` (EB-CLIP-08/09/10/11) |
| EB-CLIP-09 | Paste into a quote | Paste | As is | Every pasted line gets `> ` so it stays in the quote | known case | P1 | `e2e/paste.test.ts` (EB-CLIP-08/09/10/11) |
| EB-CLIP-10 | Paste into a code block | Paste | As is | Literal, no formatting, indentation kept | known case | P1 | `PasteMatrixTest`, `e2e/paste.test.ts` (EB-CLIP-08/09/10/11) |
| EB-CLIP-11 | Paste into a heading | Paste | As is (several lines become paragraphs after the heading) | First line joins the heading; the rest become paragraphs after it | known case; brainstorm | P2 | `e2e/paste.test.ts` (EB-CLIP-08/09/10/11) |
| EB-CLIP-12 | Paste into a table cell | Paste | As is | See EB-TABLE-07 | known case | P1 | `unit/paste.test.ts` (EB-TABLE-07), `e2e/paste.test.ts` |
| EB-CLIP-13 | Paste rich HTML (from a browser or Docs) | Paste | Plain text of the clip (Code is raw) | Converted to Markdown (headings, lists, links, bold, tables), sanitised | [GDOCS], [NOTION]; brainstorm | P1 | untested |
| EB-CLIP-14 | Paste rich HTML | Paste as plain text (long-press menu, Ctrl+Shift+V) | Plain text | Plain text, no conversion | known case; [AND-KB] | P1 | untested |
| EB-CLIP-15 | Pasted text with CRLF or lone CR | Paste | Line endings normalised to the file's own | same | [SSM] robustness | P1 | `PasteMatrixTest` (CRLF), `e2e/paste.test.ts` (EB-CLIP-15) |
| EB-CLIP-16 | Pasted text with NBSP, zero-width or bidi control chars | Paste | Kept as is (they may be intended), shown visibly in Code | Kept | brainstorm | P3 | `PasteMatrixTest` (UNICODE), `e2e/paste.test.ts` (EB-CLIP-16) |
| EB-CLIP-17 | Paste of a picture (image clip) | Paste | Offers to save the picture beside the file and insert `![](…)` | same | brainstorm | P3 | untested |
| EB-CLIP-18 | Very large paste | Paste | Progress, cancellable, one undo step | same | [SSM] edge-cases | P1 | `PasteMatrixTest` (EB-CLIP-18), `ChunkedPasteTest` |
| EB-CLIP-19 | Paste with a trailing newline at the end of a line | Paste | Does not add an extra blank line or break a list | same | brainstorm | P2 | `e2e/paste.test.ts` (EB-CLIP-19; Preview only, Code pastes raw) |
| EB-CLIP-20 | Paste via the keyboard's clipboard strip (Gboard clipboard chip) | Commit text | Same as Paste (treated as one insertion, one undo step) | same | [AND-IC] (commitText) | P1 | `PasteMatrixTest` (EB-CLIP-20; Code only) |
| EB-CLIP-21 | Drag and drop text (tablets, ChromeOS) | Drop | Moves the text (copies with Ctrl); one undo step | same | brainstorm | P3 | untested |

## Find and replace

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-FIND-01 | Query `bold` while the source has `**bold**` | Find | Matches the text in the source | Matches the rendered text (markers ignored) and maps back | [SSM] ux-spec | P1 | untested |
| EB-FIND-02 | Match inside a read-only block (front matter, maths) | Next | Selected in Code | Scrolled to and highlighted; Replace there goes through Code or is refused with a reason | [SSM] ux-spec | P2 | untested |
| EB-FIND-03 | Replace inside a code block | Replace | Literal | same | brainstorm | P2 | untested |
| EB-FIND-04 | Replacement that introduces syntax (`*` or `#` at line start) | Replace all | Written literally | same (the page re-renders) | brainstorm | P3 | untested |
| EB-FIND-05 | Find bar open | Type in the document | Matches update live; the current match stays the one at or after the caret | same | [SSM] ux-spec | P2 | untested |
| EB-FIND-06 | Regex with `^` `$` | Find | Anchors per line | same | [SSM] ux-spec | P3 | untested |
| EB-FIND-07 | Query with an emoji or accented letter (NFC vs NFD) | Find | Matches both forms, or at least the same form as typed | same | brainstorm | P3 | untested |
| EB-FIND-08 | Find, close the bar | Close | The last match stays selected; typing replaces it | The caret lands at the match | [SSM] ux-spec | P2 | untested |
| EB-FIND-09 | Replace all, then a single Undo | Undo | Restores every replacement | same | [SSM] ux-spec | P1 | untested |

## Caret and tap placement

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-CARET-01 | Short line | Tap to the right of its end | Caret at the line's end | Caret at the end of that block's line | known case | P1 | `tapCaret.test.ts`, `TapCaretTest` |
| EB-CARET-02 | Wrapped line | Tap to the right of a visual row that is not the last | Caret at the end of that visual row (before the wrap), not at the start of the next | n/a | brainstorm, [AND-SEL] | P1 | `tapCaret.test.ts`, `TapCaretTest` |
| EB-CARET-03 | Empty line between two paragraphs | Tap it | Caret on the empty line | Taps on the gap between blocks place the caret at the end of the nearer block above, or create a paragraph there (decision D12) | brainstorm | P1 | `tapCaret.test.ts` (gap cells), `TapCaretTest` |
| EB-CARET-04 | Below the last line | Tap | Caret at the document's end | Caret at the end of the last editable block; a tap below a final table, code block or diagram creates a paragraph there | [TT] (exitOnArrowDown), brainstorm | P1 | untested |
| EB-CARET-05 | Left of a list item's text (on the marker) | Tap | Caret before or after the marker by the nearest edge | Caret at the item text's start | brainstorm | P2 | `tapCaret.test.ts` (left of the start), `TapCaretTest` |
| EB-CARET-06 | Caret at a wrap point | Type | Typed letter appears where the caret was drawn | same | brainstorm | P2 | untested |
| EB-CARET-07 | Arrow up/down across lines of different length | Arrow keys | Keeps the goal column (x) across short lines | same | [AND-KB] | P2 | untested |
| EB-CARET-08 | Typing at the bottom of the screen | Type | The caret stays above the keyboard and the strip | same | [SSM] R8 | P1 | untested |
| EB-CARET-09 | Tap while a fling is moving | Tap | Stops the fling only | Stops scrolling; does not start editing (reading guard) | [SSM] edge-cases, ux-spec | P1 | untested |
| EB-CARET-10 | Tap in Preview on read-only content | Tap | n/a | No keyboard; the chip offers "Edit in Code" | [SSM] ux-spec | P2 | untested |
| EB-CARET-11 | Drag the caret handle | Drag | Moves by character, with a magnifier | same | [AND-SEL] | P2 | untested |
| EB-CARET-12 | Space-bar swipe (Gboard cursor control) | Swipe on the space bar | Caret moves left/right through the text | same | [AND-IC] | P2 | untested |
| EB-CARET-13 | Reopen a file | Open | Caret and scroll restored | same | [SSM] R7 | P1 | untested |
| EB-CARET-14 | Caret after an inline formula, image or link end in Preview | Arrow right | Steps over the unit as one | n/a | [PM] | P3 | untested |

## IME and soft keyboard (Android)

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-IME-01 | Gboard has `wor` underlined (composing), suggestion shown | Press Enter | The composing word is committed as shown, **then** the list continues; the new item lands on the new line and the caret stays in the list | same | [OB-51716], [DYN], [SSM] edge-cases | P1 | untested |
| EB-IME-02 | Gboard autocompletes the last word and sends a newline as text (`commitText("\n")`) rather than a key event | Enter via commitText | Treated exactly as the Enter key (list continuation etc.) | same | [OB-51716], [AND-IC] | P1 | untested |
| EB-IME-03 | After a new list item `- ‸` | Keyboard's auto-capitalise | The keyboard capitalises the first letter (we report sentence start via `getCursorCapsMode` after a marker) | same | [OB-97926], [OB-103141], [AND-IC] | P2 | untested |
| EB-IME-04 | Autocorrect at the start of a line or item | Accept a correction | The caret stays at the end of the corrected word; no jump to the start | same | [OB-33908], [MK] #1212 | P1 | untested |
| EB-IME-05 | Strip key pressed while a word is composing | Bold | The word commits once, then Bold wraps it; text never doubled | same | [SSM] R6, edge-cases | P1 | untested |
| EB-IME-06 | Samsung Keyboard | Type quickly | The caret never ends up before the last letter typed | same | [OB-78185], [OB-79497] | P1 | untested |
| EB-IME-07 | Swipe (glide) typing a word | Swipe | The word and its auto-space are inserted once; Backspace after a swipe deletes the whole word (keyboard behaviour honoured) | same | [AND-IC] | P1 | untested |
| EB-IME-08 | Voice input (Gboard mic) inserts a phrase | Dictate | The phrase is inserted at the caret, one undo step; continuing dictation does not duplicate the composing region | same | [AND-IC]; brainstorm | P2 | untested |
| EB-IME-09 | Double-space for a period | Type space twice | `. ` inserted by the keyboard works, except it must not fire right after a list marker (`- ` followed by space) | same | [OB-33045] | P2 | untested |
| EB-IME-10 | Keyboard's suggestion replaces a word that contains markup (`**word**` with caret inside) | Pick a suggestion | Only the word's letters change; markers kept | Only the text changes | brainstorm; [AND-IC] (getTextBeforeCursor) | P1 | untested |
| EB-IME-11 | Backspace held down | Hold | Deletes repeatedly; the list/quote rules (EB-LIST-30) apply at the marker, then carry on deleting into the line above | same | brainstorm | P2 | untested |
| EB-IME-12 | Keyboard sends `deleteSurroundingText` across a marker | Backspace | Same result as the key event (marker rules apply) | same | [AND-IC] | P1 | untested |
| EB-IME-13 | Emoji keyboard or sticker | Insert | Inserted at the caret as text; never splits a surrogate pair | same | [SSM] edge-cases | P2 | untested |
| EB-IME-14 | CJK IME with a long composing phrase | Type, then convert | The composing region stays where it began; no list or input rule fires mid-composition | same | [AND-IC]; brainstorm | P2 | untested |
| EB-IME-15 | Keyboard's own smart quotes or autocorrect inside a code block | Type | The app cannot stop the keyboard, but the field reports a "no suggestions" type while the caret is in code so most keyboards stop correcting | same | [SSM] R9; [AND-IC] (`TYPE_TEXT_FLAG_NO_SUGGESTIONS`) | P2 | untested |
| EB-IME-16 | Rotate while composing | Rotate | The composing word is committed once | same | [SSM] edge-cases | P1 | untested |
| EB-IME-17 | Keyboard's editing panel (Select all, Copy, Paste, Undo) | Use | Works as the matching shortcuts | same | [SSM] edge-cases | P2 | untested |
| EB-IME-18 | Keyboard hidden then reshown | Tap | Caret position kept; strip returns | same | brainstorm | P2 | untested |
| EB-IME-19 | Handwriting input (stylus) | Write | Inserted as committed text; scribble-to-delete works where the keyboard supports it | same | [OB-33082] | P3 | untested |
| EB-IME-20 | TalkBack on | Edit text | Characters read as typed; the caret position read on move; the strip's keys have labels and state ("Bold, on") | same, through the WebView's own accessibility | [TB], [SSM] ux-spec | P2 | untested |
| EB-IME-21 | TalkBack on | Move by word, line, heading; select with the local context menu | Works on the virtual text tree | same | [TB], [SSM] ux-spec | P2 | untested |
| EB-IME-22 | TalkBack on | Enter in a list | Announces the new item ("Bullet, new item"), or at least the inserted text | same | [TB]; brainstorm | P3 | untested |

## Hardware keyboard

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-KBD-01 | Text selected | Ctrl+B / Ctrl+I | Bold / Italic as the strip keys | same | [GDOCS], [NOTION], [TY-KEYS], [SSM] edge-cases | P1 | untested |
| EB-KBD-02 | Any | Ctrl+K | Link sheet (the near-universal binding). Needs a decision: ux-spec gives Ctrl+K to the Navigator | same | [GDOCS], [NOTION]; decision D8 | P2 | untested |
| EB-KBD-03 | Any | Ctrl+E (inline code), Ctrl+Shift+X (strike), Ctrl+Shift+7/8/9 (numbered/bullet/task), Ctrl+Alt+1–3 (headings), Ctrl+Shift+. (quote) | As the strip keys | same | [GDOCS], [NOTION] | P3 | untested |
| EB-KBD-04 | In a list item | Tab / Shift+Tab | Indent / outdent the item (wherever the caret is in the item, not only at its start) | same | [MAIO], [GDOCS], [OB-24784] | P1 | untested |
| EB-KBD-05 | In a plain paragraph | Tab | Inserts a tab or 4 spaces at the caret (never re-indents the paragraph into a code block); focus never leaves the editor | same, or nothing | [OB-52982]; decision D3 | P2 | untested |
| EB-KBD-06 | Any | Home / End | Start / end of the visual row (wrapped), a second press to the logical line; Home in a list item goes to the text start first, then the line start | same | [AND-KB], brainstorm | P2 | untested |
| EB-KBD-07 | Any | Ctrl+Home / Ctrl+End | Document start / end | same | [AND-KB] | P2 | untested |
| EB-KBD-08 | Any | Ctrl+Backspace / Ctrl+Delete | Delete the word before / after; stops at markup boundaries | same | [AND-KB] | P2 | untested |
| EB-KBD-09 | Any | Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y | Undo / redo | same | [SSM] edge-cases | P1 | untested |
| EB-KBD-10 | Any | Ctrl+S, Ctrl+F | Save, Find | same | [SSM] edge-cases | P1 | untested |
| EB-KBD-11 | Task item | Ctrl+Enter | Toggles the task's tick | same | [MAIO] (Ctrl+Enter), brainstorm | P3 | untested |
| EB-KBD-12 | Code block in Preview | Ctrl+Enter or Shift+Enter | n/a: in Code the fence is text and arrow down passes it | Exits the code block (EB-CODE-05) | [PM] (Mod-Enter exitCode); decision D4 | P2 | untested |
| EB-KBD-13 | Any | Alt+↑ / Alt+↓ | Move the line (or list item with children) up/down | same | brainstorm | P3 | untested |
| EB-KBD-14 | Any | Escape | Closes the find bar or a sheet; in Preview editing it is Done | same | brainstorm | P2 | untested |
| EB-KBD-15 | Any | Ctrl+/ | Shortcut help sheet | same | [SSM] ux-spec | P3 | untested |
| EB-KBD-16 | Any | Page Up / Page Down | Move a screen, caret moves with it | same | [AND-KB] | P3 | untested |
| EB-KBD-17 | Mouse or touchpad | Click, drag, double-click, triple-click, right-click | Caret, selection, word, line/paragraph, context menu | same | [AND-KB]; brainstorm | P3 | untested |

## Large documents

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-LARGE-01 | 10,000-line file, caret near the top | Enter in a list (renumbering 5,000 items) | Under one frame for the keystroke; renumbering limited to the contiguous run | same | [SSM] R1, edge-cases | P2 | untested |
| EB-LARGE-02 | Huge file | Select all + Copy | Copies without a crash (Binder-safe) | same | [SSM] edge-cases | P1 | untested |
| EB-LARGE-03 | Huge file | Select all + Delete, then Undo | Both fast; one undo restores it | same | brainstorm | P2 | untested |
| EB-LARGE-04 | Huge file | Replace all with thousands of matches | Responsive, one undo step | same | [SSM] edge-cases | P2 | untested |
| EB-LARGE-05 | A single 1 MB line | Type at its end | Responsive; caret visible | Preview may degrade per size tier; never freezes | [SSM] R1 | P2 | untested |
| EB-LARGE-06 | Open a fence at the top of a big file | Type ```` ``` ```` | No lag; highlighting converges | same | [SSM] edge-cases | P2 | untested |
| EB-LARGE-07 | Huge file | Bold on a selection spanning thousands of paragraphs | Completes or refuses quickly with a message; never ANRs | same | brainstorm | P3 | untested |

## Right-to-left text and emoji

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-INTL-01 | Arabic or Hebrew list item `- שלום‸` | Enter | Continues the list; the marker stays at the line's logical start | The item is right-aligned (auto direction) and continues | [SSM] edge-cases | P2 | untested |
| EB-INTL-02 | Mixed LTR and RTL in one line | Arrow keys, taps | The caret moves in logical order and is drawn at the correct visual edge | same | [SSM] edge-cases | P2 | untested |
| EB-INTL-03 | RTL word selected | Bold | Markers wrap the logical range | same | brainstorm | P3 | untested |
| EB-INTL-04 | Emoji with ZWJ (family, flags, skin tones) | Backspace | Deletes the whole grapheme | same | [SSM] edge-cases | P1 | untested |
| EB-INTL-05 | Emoji at a selection edge | Bold | Never splits a grapheme with a marker | same | [SSM] edge-cases | P2 | untested |
| EB-INTL-06 | Combining accents (NFD `é`) | Arrow, Backspace | One step per grapheme for arrows; Backspace may remove the accent only (Android convention) | same | [SSM] edge-cases | P3 | untested |
| EB-INTL-07 | CJK text with no spaces | Double-tap | Selects a word by dictionary break, not the whole line | same | [AND-SEL] | P3 | untested |
| EB-INTL-08 | Wide characters in a table | Cell edit | Padding by display width (CJK and emoji count as 2) | same | [SSM] ux-spec | P3 | untested |

## Read-only files

| ID | Situation | Action | Code | Preview | Source | P | Status |
|---|---|---|---|---|---|---|---|
| EB-RO-01 | File opened read-only (no write grant, UTF-32) | Type | No keyboard edits accepted; a clear "Read-only · Save as…" | same; taps never start editing | [SSM] R2, edge-cases | P1 | untested |
| EB-RO-02 | Read-only file | Select, Copy, Find | All work | same | brainstorm | P1 | untested |
| EB-RO-03 | Read-only file | Strip | Formatting keys hidden or disabled; Undo has nothing | same | brainstorm | P2 | untested |
| EB-RO-04 | Read-only file | Tap a task checkbox | Nothing changes and the box does not tick on screen | same | [SSM] edge-cases | P2 | untested |
| EB-RO-05 | Read-only file | Paste, Cut, voice input | Refused with the same message; nothing lost | same | brainstorm | P2 | untested |
| EB-RO-06 | Read-only file | Save as… | The copy opens editable with the caret where it was | same | [SSM] R2 | P2 | untested |

## Where sources disagree

Each decision below is our recommendation, set against the principles
in `product-journey.md`: calm and predictable, never lose work, the
same text from both surfaces, and valid Markdown kept valid. Items
marked **Andrew** need a product call before the rows that cite them
are tested.

| # | Question | Alternatives | Recommendation | Rows |
|---|---|---|---|---|
| D1 | Enter on an empty **nested** item | Exit the list at once (CodeMirror lifts one level only after a blank line); outdent one level first (Markdown All in One, ProseMirror, Google Docs, Notion) | Outdent one level per Enter, exit at top level. Already the spec | EB-LIST-06 |
| D2 | Backspace at the start of an item's text | Remove the marker and leave its width as indentation, so the text becomes a continuation paragraph of the item above (CodeMirror, Markdown All in One, Google Docs); remove the marker and the indent at once (Obsidian users complain of exactly this, [OB-104451]); join with the line above (plain text editors) | First Backspace: marker → indentation (a continuation of the previous item; for the first item, a plain paragraph). Second: remove the indent. Third: join. Each step is visible and undoable. **Andrew**: is a continuation paragraph too subtle on a phone, where the known-case fix may simply make a paragraph? | EB-LIST-30, -31, -32 |
| D3 | Tab / Indent outside a list, and on a list's first item | Insert a tab or spaces (code editors, Typora in code); indent the paragraph (Google Docs); nothing, or move focus (Android's default for Tab); indent the first item anyway (Markdown All in One), refuse (ProseMirror) | In a list: indent the item (never the first item, which would become a code block or orphan; announce why). In a code block or table: indent / next cell. In a paragraph: hardware Tab inserts the file's indent unit at the caret; the strip's Indent key on a paragraph is disabled. **Andrew**: disabled vs hidden for Indent on a paragraph | EB-LIST-23, EB-CODE-03, EB-KBD-05 |
| D4 | Leaving a code block in Preview | Triple Enter (Tiptap default), Mod-Enter (ProseMirror `exitCode`), Arrow down at the end (Tiptap), a tap below | All of: two empty trailing lines + Enter, Shift/Ctrl+Enter, tap below. A phone has no arrow keys and no Ctrl, so the triple Enter and the tap are what matter. **Andrew**: the triple Enter deletes two blank lines a user might have wanted in code (rare); the alternative is a visible "↵ Exit block" affordance, which adds chrome | EB-CODE-05, EB-KBD-12 |
| D5 | Ending a quote | Two empty quoted lines (CodeMirror); one (Notion, Google Docs, our lists) | One empty `> ` line + Enter ends the quote, like lists, so Enter means the same thing everywhere. A blank line inside a quote is still possible by typing `>` | EB-QUOTE-02 |
| D6 | Bold with a caret and no selection | Wrap the word under the caret (GitHub toolbar, Markdown All in One); insert an empty pair (Markdown All in One at whitespace); arm a "stored mark" for the next typing (ProseMirror, Docs) | In a word: wrap the word. At whitespace: Code inserts a pair with the caret inside; Preview arms bold for the next typing and writes nothing if nothing is typed (no empty `****` left in the file) | EB-INLINE-04, -06 |
| D7 | Bold on a selection that is partly bold | Remove where present (ProseMirror default); add to the rest (Google Docs, Word) | Docs convention: on unless all of it is already bold. Users carry this over and it never deletes formatting by surprise | EB-INLINE-12 |
| D8 | Ctrl+K | Insert link (Docs, Notion, GitHub, Slack, VS Code Markdown); quick switcher (our ux-spec, VS Code's Ctrl+P family) | **Andrew**: Ctrl+K = link, Navigator on Ctrl+P (already bound). Keeping Ctrl+K for the Navigator surprises every Docs user | EB-KBD-02 |
| D9 | Pasting a URL over selected text | Make a link (GitHub, Obsidian 1.11+, GitLab, Notion, Markdown All in One); replace (plain editors; some Obsidian users want this back) | Make a link; the first Undo turns it into the plain replace. Paste as plain text always replaces | EB-LINK-05, -07, EB-UNDO-07 |
| D10 | Pasting several lines into a list item | Lines become new items; first line joins the item and the rest are continuation lines; paste raw (lazy lines) | Preview: if the clip is itself a Markdown list, its items become items; otherwise the first line joins and the rest are indented continuation lines, so the paste stays inside the item. Code: paste raw | EB-CLIP-07, -08 |
| D11 | Enter on an empty second item of a tight list | Make the list loose by moving the item down a line (CodeMirror default `nonTightLists`); exit | Exit. Changing a list's looseness is a surprising side effect | EB-LIST-17 |
| D12 | Tap in the gap between two blocks in Preview | Caret at the nearest block's edge (most WYSIWYG); create an empty paragraph there (Notion "click to add"); nothing | Caret at the end of the block above (or start of the block below if nearer). A paragraph is created only below a last block that cannot hold a caret (table, code, diagram) | EB-CARET-03, -04 |
| D13 | Renumbering numbered lists | Renumber the following run on every edit (CodeMirror, us); never renumber (asked for in [GH39057] for accessibility); renumber the whole list on save (MarkText, complained about in [MT2479]) | Renumber only the contiguous run an edit touches, keep `1. 1. 1.` and custom starts, and never on save or view switch | EB-NUM-03, -06, -16 |
| D14 | Enter in a paragraph | Code is plain text, Enter is a newline (every source editor); WYSIWYG Enter is a new paragraph and Shift+Enter a line break (Docs, Typora, Notion) | Keep each surface's convention: Code = newline; Preview = new paragraph, Shift+Enter = hard break in the file's style. The same keystroke gives different text in the two surfaces, which is the one deliberate exception to rule 5 | EB-BREAK-01, -02, EB-LIST-47 |
| D15 | Auto-pairing `(` `[` `` ` `` `*` `"` | On (Obsidian setting, code editors); off (Docs, Typora defaults) | Off. Soft keyboards already insert, delete and correct around the caret; auto-inserted closers fight composing text and leave stray characters | EB-INLINE-35 |

**Decided (Andrew, 2026-11-03).** The decisions below take precedence over the recommendations in the table where the two differ.

- **D2.** The first Backspace removes the marker and leaves a plain paragraph in place. The second Backspace joins it to the line above. The indented-continuation step is not used.
- **D3.** On a paragraph, the strip's Indent key is shown but disabled, and TalkBack says why.
- **D4.** You leave a code block in Preview by pressing Enter on a second empty line at the end of the block (the empty lines are removed), or with ¶ → Paragraph on its last line. There is no extra control.
- **D8.** Ctrl+K inserts a link. Ctrl+P opens the Navigator.
- **D10.** Use the recommendation in the table. A Markdown list on the clipboard becomes list items; any other text joins the item, and its remaining lines become continuation lines.
- **All other rows:** the recommendation in the table stands.

## Sources

Each row cites one or more of these keys. Behaviours were read from
the sources' documentation, tests and issue threads and written here in
our own words; no code or test text was copied.

| Key | Source |
|---|---|
| [CM] | CodeMirror 6, `@codemirror/lang-markdown`: `insertNewlineContinueMarkup` and `deleteMarkupBackward` in [src/commands.ts](https://github.com/codemirror/lang-markdown/blob/main/src/commands.ts) and their tests in [test/test-commands.ts](https://github.com/codemirror/lang-markdown/blob/main/test/test-commands.ts) (MIT) |
| [MAIO] | Markdown All in One for VS Code (yzhang-gh): [listEditing.test.ts](https://github.com/yzhang-gh/vscode-markdown/blob/master/src/test/suite/integration/listEditing.test.ts) and [formatting.test.ts](https://github.com/yzhang-gh/vscode-markdown/blob/master/src/test/suite/integration/formatting.test.ts) (MIT) |
| [MTE] | GitHub's [markdown-toolbar-element](https://github.com/github/markdown-toolbar-element), its [test suite](https://github.com/github/markdown-toolbar-element/blob/main/test/test.js) (MIT) |
| [PM] | ProseMirror: [prosemirror-schema-list](https://github.com/ProseMirror/prosemirror-schema-list/blob/master/src/schema-list.ts) (`splitListItem`, `liftListItem`, `sinkListItem`, `wrapInList`) and [prosemirror-commands](https://github.com/ProseMirror/prosemirror-commands/blob/master/src/commands.ts) (`joinBackward`, `exitCode`, `toggleMark`, `baseKeymap`) (MIT). Tiptap and Milkdown build on these commands; Lexical's list plugin follows the same conventions and was not mined separately |
| [TT] | Tiptap [Code block extension](https://tiptap.dev/docs/editor/extensions/nodes/code-block): `exitOnTripleEnter`, `exitOnArrowDown`, `exitOnArrowUp`, `enableTabIndentation`, the fence input rule |
| [TY-CF] | Typora, [Code Fences](https://support.typora.io/Code-Fences) |
| [TY-KEYS] | Typora, [Shortcut Keys](https://support.typora.io/Shortcut-Keys) |
| [CMS] | [CommonMark spec 0.31.2](https://spec.commonmark.org/0.31.2/): §2.4 backslash escapes, §4.1 thematic breaks, §4.2 ATX headings, §4.3 setext headings, §4.4 indented code, §4.5 fenced code, §5.1 block quotes (lazy lines), §5.2 list items, §5.3 lists (start number, `)` delimiter, tight vs loose), §6.1 code spans, §6.2 emphasis, §6.3 links, §6.7 hard line breaks |
| [GFM] | [GitHub Flavored Markdown spec](https://github.github.com/gfm/): §4.10 tables, §5.3 task list items, §6.5 strikethrough, §6.9 autolinks |
| [GFM-ALERT] | GitHub Docs, [Alerts](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax#alerts) |
| [GFM-FN] | GitHub Docs, [Footnotes](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax#footnotes) |
| [GH-LINK] | GitHub changelog, [Linkify selected text on URL paste](https://github.blog/changelog/2021-11-10-linkify-selected-text-on-url-paste/) (2021) |
| [GL] | GitLab, [Markdown: select text and paste URL to create link](https://gitlab.com/gitlab-org/gitlab/-/merge_requests/178942) |
| [GH39057] | GitHub community, [an accessibility option not to renumber lists](https://github.com/orgs/community/discussions/39057) |
| [MT2479] | MarkText issue [#2479](https://github.com/marktext/marktext/issues/2479): ordered lists rewritten and renumbered on save |
| [OB-PASTE] | Obsidian forum, [option to disable paste-as-link over a selection](https://forum.obsidian.md/t/add-option-to-disable-native-paste-markdown-link-over-selected-text-replace-text-instead-and-separate-commands-to-add-link-or-replace-with-cutomizable-shortcut/115955) |
| [OB-54358] | Obsidian forum, [Cannot exit the automatic list after pressing enter twice](https://forum.obsidian.md/t/cannot-exit-the-automatic-list-after-pressing-enter-twice/54358) |
| [OB-96116] | Obsidian forum, [Unexpectedly inserting - when pressing enter in code block](https://forum.obsidian.md/t/unexpectedly-inserting-when-pressing-enter-in-code-block/96116) |
| [OB-96585] | Obsidian forum, [Bulleted list break Enter key behaviour for code blocks with indented code](https://forum.obsidian.md/t/bulleted-list-break-enter-key-behaviour-for-code-blocks-with-indented-code/96585) |
| [OB-96130] | Obsidian forum, [List line break not indented with Shift+Enter](https://forum.obsidian.md/t/list-line-break-not-indented-with-shift-enter-in-editing-mode/96130) |
| [OB-95842] | Obsidian forum, [Nested blockquotes becoming lists on newline](https://forum.obsidian.md/t/nested-blockquotes-becoming-lists-on-newline/95842) |
| [OB-104451] | Obsidian forum, [Backspace deletes all indent in one go](https://forum.obsidian.md/t/backspace-deletes-all-indent-in-one-go-can-i-make-it-step-back-gradually/104451) |
| [OB-52982] | Obsidian forum, [Strange bullet tab/outdent behaviour](https://forum.obsidian.md/t/strange-bullet-tab-outdent-behavior/52982) |
| [OB-24784] | Obsidian forum, [Tab in a bullet paragraph adds space instead of indenting](https://forum.obsidian.md/t/when-i-press-tab-while-in-a-bullet-point-paragraph-it-adds-space-rather-than-indenting-the-text-it-only-indents-if-i-am-on-the-first-letter/24784) |
| [OB-51716] | Obsidian forum, [Enter in lists on Android Gboard doesn't make the next bullet, due to autosuggest](https://forum.obsidian.md/t/enter-in-lists-on-android-gboard-doesnt-make-next-bullet-point-checkbox-etc-due-to-autosuggest-autocorrect/51716) |
| [OB-97926] | Obsidian forum, [Auto-capitalisation in bulleted lists not working on Android](https://forum.obsidian.md/t/auto-capitalization-in-bulleted-lists-not-working-on-android/97926) |
| [OB-103141] | Obsidian forum, [Capital first letter after a list marker on mobile](https://forum.obsidian.md/t/capital-first-letter-after-list-bullet-or-checklist-item-auto-capitalize-first-letter-in-lists-on-mobile/103141) |
| [OB-33908] | Obsidian forum, [Autocorrect text entry broken on Android](https://forum.obsidian.md/t/autocorrect-text-entry-broken-in-obsidian-on-android/33908) |
| [OB-33045] | Obsidian forum, [Android Gboard double-space period broken](https://forum.obsidian.md/t/bug-android-gboard-double-space-period-broken/33045) |
| [OB-33082] | Obsidian forum, [Samsung Keyboard input problems: delete backwards and page jumps](https://forum.obsidian.md/t/bug-external-keyboard-samsung-keyboard-problems-delete-backwards-and-page-jumps/33082) |
| [OB-78185] | Obsidian forum, [Cursor ends up before the letter on Android Samsung keyboard](https://forum.obsidian.md/t/cursor-ends-up-before-the-letter-on-android-samsung-keyboard/78185) |
| [OB-79497] | Obsidian forum, [Cursor moves behind the character right after typing it](https://forum.obsidian.md/t/cursor-moves-behind-the-character-right-after-typing-it/79497) |
| [BEAR-10240] | Bear community, [Double enter does not exit list mode](https://community.bear.app/t/double-enter-does-not-exit-list-mode/10240) (see also [#14762](https://community.bear.app/t/enter-after-an-empty-list-doesnt-delete-anymore-the-bullet/14762)) |
| [DYN] | Dynalist forum, [Gboard new line in existing bullet formatting](https://talk.dynalist.io/t/gboard-keyboard-new-line-in-existing-bullet-formatting/1221): Gboard commits its autocorrect before the Enter |
| [MK] | Markor issues cited in `research-findings.md`: [#2819](https://github.com/gsantner/markor/issues/2819) (smart quotes in code), [#1212](https://github.com/gsantner/markor/issues/1212) (autocorrect after toolbar actions), [#1274](https://github.com/gsantner/markor/issues/1274) (whitespace altered) |
| [GDOCS] | Google Docs, [keyboard shortcuts](https://support.google.com/docs/answer/179738) and its well-known list, paste and link conventions |
| [NOTION] | Notion, [keyboard shortcuts](https://www.notion.com/help/keyboard-shortcuts) and Markdown-style input conventions |
| [AND-IC] | Android, [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection) (composing regions, `commitText`, `deleteSurroundingText`, `getCursorCapsMode`) and [EditorInfo / input types](https://developer.android.com/reference/android/text/InputType) |
| [AND-KB] | Android, [Handle keyboard actions](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands) and the platform's text-editing shortcuts |
| [AND-SEL] | Android `TextView` selection behaviour (handles, magnifier, word snapping), [Selection and the text toolbar](https://developer.android.com/develop/ui/views/text-and-emoji/magnifier) |
| [TB] | Google, [Use TalkBack to edit text](https://support.google.com/accessibility/android/answer/6283677) |
| [SSM] | SS Markdown's own docs: `ux-spec.md`, `edge-cases.md`, `research-findings.md` (R1–R13), `robustness.md`, `product-journey.md` |
| known case | One of the behaviours other agents were fixing when this file was written (list Enter/exit/split, top-level Outdent, Backspace on a marker, Bullet↔Numbered, ¶ on one line, tap past a line's end, paste per block, Paste as plain text) |
| brainstorm | No source found: worked out block by block and action by action |

**Licence note.** SS Markdown is Apache-2.0. The sources above are under
their own licences (MIT for CodeMirror, ProseMirror, Markdown All in One
and markdown-toolbar-element; CC-BY-SA for the CommonMark spec; terms of
use for forums and product help pages). Only behaviours and ideas, which
are not copyrightable, are recorded here, in our own words. Do not copy
test code or wording from these sources into our tests; write our own
cases from the rows above.
