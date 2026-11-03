# Changelog

All notable changes to SS Markdown are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, any
release may change behaviour.

## [Unreleased]

### Added

- Code view colours fenced code by language, in the same colours Preview
  uses: keywords, types, strings, numbers, comments, function names,
  annotations, variables, tags and keys. Python, JavaScript, TypeScript,
  Java, Kotlin, C, C++, C#, Go, Rust, PHP, Ruby, Swift, Dart, SQL,
  Bash/Shell and console sessions, PowerShell, JSON, YAML, TOML, INI,
  HTML/XML/SVG, CSS/SCSS/Less, Markdown, Dockerfile and diff, with their
  usual short names (`py`, `ts`, `rs`, `sh`, …). A fence with no language
  or an unknown one stays plain. Themes gained nine code colours (Colours
  → Code colours), contrast-checked like the rest.
- A **Numbered list** key next to Bulleted list in the formatting strip
  (Code and Preview). On a bulleted item it switches the whole list to
  numbers, and the Bulleted key does the reverse; on its own kind each
  key makes that item a paragraph and keeps the rest of the list.
- Outdent and Indent now sit side by side (← →) in both strips, and
  Preview's strip has them too. Shift+Tab and Tab do the same on a
  hardware keyboard. Redo is pinned next to Undo. With two rows, neither
  pair is ever split across the rows.
- Code blocks indent like code: Indent and Outdent add or remove one of
  the block's own indent units (a tab, 2 or 4 spaces), Enter keeps the
  indentation and adds a unit after `{`, `[` or `(` (or `:` in Python and
  YAML), and a `}`, `]` or `)` typed first on a line takes one off.
- In Preview, Enter on a second empty line at the end of a code block
  leaves the block.
- Sharing text that is already at the end (or top) of the chosen file no
  longer adds it twice: the share sheet says "This text is already at the
  end of <file>." and offers Open, Add again and Cancel.
- If Android closes SS Markdown while a share is still being added, the
  share sheet checks the file when it comes back: it shows "Added to …"
  if the text got there, or says "The last Append didn't finish. Nothing
  was added." and offers Append again.
- Settings → Text → Formatting bar: One row (the default) or Two rows.
  Two rows shows the whole formatting strip at once on most phones (every
  Code key from 384 dp wide), with Undo and `{ }` or Done still at the top
  right. Landscape, and any window that would leave the editor under about
  200 dp, always uses one row, and the caret stays on screen above the
  taller strip.
- Settings → Text → Show line numbers ("In the Code view."), on by
  default. Off, the Code view drops its number gutter and the text uses
  the full width, starting a small inset from the edge. It applies to the
  open file at once, and the line you are typing on stays where it is on
  screen. It applies to Split's Code pane too.
- Typing in Split now gives the pane you are editing the whole editor
  area: the other pane slides away while the keyboard is up and comes back
  when it closes, you switch mode or open another file. Settings → Text →
  When typing in Split can keep both panes instead. On wide screens both
  panes always stay.

### Changed

- New app icon.

### Fixed

- In the Code view, the line number of a row partly scrolled off the top
  could be drawn over the top bar, across the file's title. The numbers
  are now clipped to the Code view, like the text.
- Preview: a tap in the space right of a line now puts the caret at the
  end of that line, in a code block too, instead of on the line above or
  nowhere. A tap left of a line, below a block's last line or between two
  blocks goes to the nearest place you can type, and a wrapped line keeps
  the caret at its wrap point.
- Preview: a code block you are editing has one background again, not a
  darker box behind each line.
- Code: a tap below the last line puts the caret at the end of the
  document.
- In Split, tapping into the Code or Preview pane could show the keyboard
  and then take it away again. Opening the keyboard rebuilt the Split
  layout, which took the pane you tapped off the screen for a moment and
  lost its focus. The panes now stay put while the layout changes.
- Enter at the end of a list item in Preview could make a plain paragraph,
  or an item with the wrong number, when the last word had just been
  typed. Enter in a list now behaves the same in Code and Preview: a new
  item of the same kind right after the caret's item, an item split at
  the caret, the numbers after it counted on.
- Enter on an empty item in the middle of a numbered list, Outdent on a
  top-level item and the list keys now split the list and the second
  part continues its numbering, instead of restarting at 1 or becoming
  part of the new paragraph.
- Backspace at the start of an item's text removes the bullet or number
  and leaves the text in place as a paragraph; the next Backspace joins
  it to the line above.
- Enter on an empty quote line ends the quote in both views.
- ¶ on one line of a code block, quote or alert converts that line only,
  splitting the block around it, instead of unwrapping the whole block.
  ¶ is disabled in a table, and a line turned into a code block next to
  one joins it.
- Indent is disabled on a paragraph, where four spaces would have made
  it a code block.
- An edit made while a large paste was finishing (Find's Replace) could
  land a few characters off when you had typed during the paste; it now
  lands where it was aimed, and an edit whose text has moved is refused
  rather than written in the wrong place.
- Undo during a large paste now undoes the paste; what you typed while it
  went in stays.
- Undo of Discard and Restore from History during a large paste now
  restore the text instead of quietly doing nothing.
- A paste over a selection no longer deletes the wrong text when the
  document changes while the clip is being prepared.
- If Android closed SS Markdown just after a share was added to a file,
  the share sheet came back with Append still on, and a second tap added
  the text twice. It now comes back showing "Added to …" and closes.

## [0.2.2] - 2026-11-03

Features from the 0.2.1 phone test, then a robustness pass over the
editor, Preview and files.

### Added

- Pictures that cannot be shown get a calm placeholder saying why (not
  found, no folder access, not a picture, too large, online) with the alt
  text and path, instead of a broken image.
- A file opened on its own (from Files, Drive or another app) can show the
  pictures beside it: Allow access asks once for its folder, checks the
  folder really holds the file and remembers it. Folders already granted
  are reused.
- Insert image, from the strip and ⋮, in Code and Preview: the picker
  starts next to the file; a picture in the file's folder is linked by its
  relative path, and one elsewhere can be copied into `images/` beside the
  file.

### Changed

- Copy opens a small chooser: **Copy Markdown** or **Copy formatted text**,
  the last-used first, so it stays two taps. Long-press Copy repeats the last
  choice at once. ⋮ "Copy as rich text" is now **Copy as…**, opening the same
  chooser, and a heading's section menu offers the same two copies. Messages
  say what was copied ("Markdown copied", "Formatted text copied").
- Formatted text now pastes well into Gmail, Google Docs, Word and Outlook:
  inline light-theme styles only, tables with borders, code on grey, ☐/☑
  tasks, footnotes at the end, pictures next to the file and Mermaid diagrams
  embedded as images within the clipboard's limit, maths as readable text,
  and a plain-text version without Markdown symbols for apps that paste text.
  A document too long for the clipboard is shortened, and the message says so.
- ⋮ "Display" is now **View** ("Wrap · A4 page · theme · size"), with an
  "Aa" View icon beside Undo and Redo. The View sheet has one fixed layout:
  Lines (Code: Wrap / No wrap; Preview: Fluid / A4 page / No wrap) first, then
  Appearance, Text size and Preview theme. Long-press Code, Split or Preview
  in the bottom bar to open it for that mode (its row is briefly emphasised);
  a tap still switches mode.
- Code, Split and Preview in the bottom bar each have a small icon.
- Find's up and down arrows move between matches in Code, Split and
  Preview alike, and scroll the match into view.
- PDF export and the A4 page view use Word-like A4 typography: white
  pages with 20 mm margins, an 11 pt body set to the full text width,
  so more words fit on a page.

### Fixed

- `../` picture paths now show in Preview (the address lost its `..` on
  the way to the app).
- The strip's ¶ menu and Bold no longer flash the keyboard away; headings
  and other block types apply where the cursor is.

**Editor**

- Undo during a large paste no longer crashes; dead keys (accents) no longer
  crash.
- Enter no longer drops the word being composed, and continues lists in
  Code as well as Preview.
- Gboard's undo and its edit panel (select all, copy, cut, paste) work, and
  Gboard undo takes back a word, not a letter.
- Replace all on a big file no longer freezes the app (8 s → 0.7 s).
- The Link prompt closes when another file opens; the symbol arrows step
  over an emoji whole.
- A leak of the closed screen through the keyboard is fixed.

**Preview**

- Table edits (add or delete a row, paste, Enter, Bold) after typing has
  widened a padded table no longer break the table.
- Typing could be written twice, or lost during a slow re-render; both fixed.
- In Split, editing Preview during a large Code paste keeps both views in
  step, and tapping back into Code hands the strip back to Code.
- Pictures always come from the open document's folder, even when another
  document with the same picture path was open just before, or a picture
  was still loading during the switch.
- Another file opens at its own reading position, not the previous file's.
- Ticking a task in a read-only file no longer looks ticked.
- A formatted copy waiting on a document that was replaced no longer hangs.

**Files and sharing**

- Share-in after the screen was recreated no longer shows an empty sheet or
  writes the wrong text.
- Undo discard and a conflict merge always apply to the file they were
  shown for; Insert image stops, and says so, if another file opens first.
- Reopening after Android closed the app restores the right file.
- Renaming the open file and folder listings stay up to date.
- Drafts are created and deleted off the main thread.

## [0.2.1] - 2026-11-02

Fixes from the first phone test of 0.2.0.

### Fixed

- Text no longer disappears from the Code view after scrolling down and back
  up (a line's freed drawing is recorded again), and a tap that stops a fling
  no longer moves the cursor or shows a stray handle.
- Flinging upwards no longer judders as lines above are measured.
- The reading line under the header reaches the end at the very bottom.
- Export PDF works from Code, Split or Preview and prints only the file it
  was asked for; a document switch cancels it with a message. The same
  binding now covers diagram and image exports and rich-text copy.
- PDF pages have 20 mm margins; headings stay with the text after them.
- Deleting a table row in Preview no longer leaves a blank line that breaks
  the table; several other table edits (no outer pipes, pipes in code,
  rows at the end of a file) are fixed too.
- Enter on an empty list item ends the list, in Code and Preview alike.

### Changed

- The Navigator fills the space below the header and closes with its ×,
  a second tap on the title, or Back; tablets get a 420 dp popover.
- The ⋮ menu starts with Undo and Redo, puts the keyboard away, and scrolls
  when it doesn't fit.
- The formatting strip's ¶ key opens a block-type menu (paragraph, headings,
  lists, task, quote, code block), and Link asks for the address.
- The strip's scrolling keys run up to the pinned group, cut at about half.

## [0.2.0] - 2026-11-02

### Added

- Code / Split / Preview with position kept in sync, and pinch zoom that
  reflows text.
- A virtualised code editor on a piece-tree text model: big files type, scroll
  and open like small ones.
- Preview with GitHub-flavoured Markdown, footnotes, alerts, front matter,
  KaTeX and Mermaid; tap-to-edit and a table editor; diagram viewer and image
  export; PDF export.
- Never-lose-work: a checksummed recovery journal, verified saves with a
  backup, and version history with compare, restore, pinning and export.
- Conflict handling with a three-way merge when another app changes an open
  file.
- Navigator (outline, quick switcher, drafts), folder mode with Save all,
  share-in, find and replace.
- Light and dark themes, built-in and custom Preview themes, bundled fonts and
  Google Fonts downloads.
- The Open-source licences screen shows every licence text in full.
- Direct Share: up to four recent files appear in Android's share sheet (and
  the launcher's long-press menu). Tapping one opens the share sheet with that
  file chosen; Append still confirms. On by default; Settings → Sharing turns
  it off and removes the file names (ADR 0017).
- Undo and Redo survive the app being killed in the background: the
  recovery journal keeps the newest 8 MB of undo steps while a document is
  open, across saves (journal format 2; ADR 0016).

### Fixed

- After recovering unsaved edits, one Undo no longer reverts every
  recovered edit at once; it takes back one step, as before the crash.
- A save no longer rewrites the undo history kept for a crash: it adds
  one small record to the recovery journal, so autosave stops writing
  megabytes after every pause. A save on low storage, or one whose only
  undo step was a huge paste, no longer leads to a recovery offer for
  work that was saved.

### Build

- The hand-over APK is a non-debuggable, fully optimised R8 build signed with
  the debug key: 6.15 MB, down from 15.12 MB. `mapping.txt` is archived with
  each APK.
- CI runs the Preview bundle's tests (typecheck, lint, Vitest, Playwright),
  checks the committed bundle is fresh and within budget, and gates core
  coverage at 90%.

[Unreleased]: https://github.com/scorchsoft/ss-markdown/commits/main
