# UX spec

This spec matches the iteration-0 mockups. Where they disagree, the
mockups agreed with Andrew win, and this file gets updated.

## Chrome

**Top bar (56 dp)**
- Title block: the filename, plus a red dot when unsaved, plus a status
  line underneath:
  - "Unsaved"
  - "Saved · 2 min ago"
  - "Draft · kept on this phone"
- Tapping the title opens the **Navigator** (see below). When a folder
  is open, a `≡` button sits at the left and opens the folder drawer (it
  is docked on tablets).
  - On a phone in folder mode the header reads: `≡` (48 dp) at the left,
    then the title, then Copy and Save. The title keeps a minimum width
    of about 120 dp and truncates with an ellipsis before it can push
    Copy or Save.
- **Copy** is always visible and opens a small **Copy chooser** anchored
  to it, two rows: **Copy Markdown** (the source, exactly) and **Copy
  formatted text** (the rendered document, for Gmail, Google Docs, Word,
  Outlook…; see "Copy formatted text" below). The last-used choice is
  listed first (remembered in settings), so a copy is two taps in the same
  place. Each row is 56 dp with a one-line purpose under its title, read
  together by TalkBack.
  - **Long-press Copy** repeats the last choice at once, with no chooser
    (TalkBack: "Copy Markdown again" / "Copy formatted text again"). It is
    a shortcut; the chooser is its visible twin.
  - The message names what was copied: "Markdown copied", "Formatted text
    copied", or "Formatted text copied, shortened to fit the clipboard".
    If formatted text cannot be prepared (no page in time, or another file
    opened first), the file's Markdown is copied instead and the message
    says so. A document over the clipboard's limit (robustness §5) goes
    as a file, as before.
- **Save** is always visible. One table defines every state:

  | State | Looks like | Status line | Tap |
  |---|---|---|---|
  | Clean (manual or autosave) | Muted check-in-circle, no visible label; its accessible name is "All saved" (review 3: the clean state should recede) | "Saved · 2 min ago" / "Autosaved · just now" | Shows "All saved" |
  | Unsaved | **Red** "Save" pill, text only | "Unsaved" (+ red dot by the name) | Saves |
  | Saving | Pill with a spinner, disabled | "Saving…" | — |
  | Untitled draft | Outline "Save as…" pill (not red: drafts are already safe) | "Draft · kept on this phone" | System picker, name prefilled from the first heading |
  | Read-only with edits | **Red** "Save as…" pill | "Unsaved · read-only" | System picker |
  | Save failed | **Red** "Retry" pill | "Couldn't save · <reason>" (error colour) | Retries; banner offers Save as… |
  | Autosave paused (conflict, permission, error) | **Red** "Save" pill | "Autosave paused · <reason>" | Opens the reason (conflict sheet or Save as…) |

  - Pills are 40 dp tall with a 48 dp hit area.
  - **Long-press Save** offers: Save as… · Autosave this file (on/off).
    The twin for Autosave this file is the toggle at the top of Version
    history.
  - Every successful save confirms with a short "Saved to <place>"
    message.
- **⋮** opens: New · Open file · Open folder · Save as │ Find & replace ·
  Insert image… · Version history │ Export PDF · Export images… · Share · Copy as… │
  View · Settings. **View** (hint "Wrap · A4 page · theme · size") opens
  the View sheet; it was "Display", where Andrew could not find wrapping
  or the A4 page. **Copy as…** opens the same Copy chooser (anchored to
  Copy). Above 1.5× font, when Copy itself moves into ⋮, its row opens the
  chooser and Copy as… is left out as a twin. In folder mode, while any file is unsaved, **Save
  all (N)** follows Save as (one tap after ⋮, rubric #9); it is absent
  otherwise, and the ≡ panel keeps its own Save all row. While the
  preview has pictures it cannot show without the document's folder,
  **Show pictures beside this file…** follows Insert image… (see
  "Pictures").
- The menu's **top row is Undo · Redo · View**, three 48 dp icons side by
  side (as in Chrome's menu). Undo and Redo are dimmed and read as
  unavailable when there is nothing to undo or redo; a tap applies it and
  keeps the menu open, so it can be tapped again. They work in Code, Split
  and Preview. **View** is an "Aa" glyph (TalkBack: "View options"); a tap
  closes the menu and opens the View sheet.
- Opening ⋮ commits the keyboard's word and **hides the keyboard**, so
  the menu has the room; closing it does not bring the keyboard back
  (tapping the text does). The menu never runs off the screen: below
  the pinned Undo · Redo · View row its rows scroll, with a fade at the edge
  that has more (checked at 360 × 640 dp, on its side and at 2× font).

**Bottom bar (64 dp)**
- A segmented Code | Split | Preview control: labels at normal sizes,
  icons alone above 1.5× font scale and in the landscape bar, where the
  labels stay as TalkBack names. (Icons beside labels were dropped as
  busy chrome; UX-26.) Segments size to their labels and grow with the
  font, and fall back to a vertical radio list when a label cannot fit.
- **Long-press a mode** (Code, Split or Preview; also the icon-only bar
  at large fonts and the landscape switcher) opens the View sheet for it,
  with a light haptic, and does not switch mode (a tap still does).
  Code's or Preview's Lines row is briefly emphasised; Split emphasises
  nothing. TalkBack offers it as "View options for Code". It is a
  shortcut: ⋮'s View icon and View row are its visible twins.
- A **zoom chip** ("120% ↺") floats in the **top-right corner of the
  pane it belongs to**, only when that pane's zoom differs from the
  default. In Split it is unambiguous which pane it resets. It is never
  in the bottom bar, which has no room for it. Tapping it resets the
  zoom.
- While the keyboard is up, the bar is replaced by the formatting strip.
  It is **one row by default**; Settings → Text → **Formatting bar**
  (One row | Two rows) gives it a second row on a bigger phone.
  - **Two rows** are two 48 dp key rows under one hairline, parted by the
    keys' own air rather than a divider. The top row is the keys that fit
    before the pinned group (Undo and Redo, then `{ }` or Done, the table keys in
    table mode, and in landscape the filename and Save), which keeps its
    place at the right end; the bottom row carries on with the rest, in
    the same order, from the left edge. Keys stay 48 dp, so the rows line
    up as columns. **Pairs never part**: Undo and Redo sit side by side in
    the pinned group, and Outdent and Indent stay together, so when the
    pair would end the top row both move down. From 384 dp wide every
    Code key and every symbol shows without scrolling; at 360 dp insert
    image is cut at half and quote a short scroll away, because a key
    never shrinks below the touch target. A bottom row that does not fit scrolls with the usual
    cut and fade. When every key fits in the top row, the strip stays one
    row. TalkBack reads the top row, then the bottom row, as one
    "Formatting" group.
  - **Two rows is a preference, not a promise.** The strip uses one row
    whenever the window is landscape (wider than tall), or when the
    editor left above the strip with the keyboard up would be under
    about 200 dp (a fold, split screen, a small window). The choice is
    made from the keyboard's final height, so it never changes rows
    while the keyboard slides, and it is re-made on every rotation,
    resize and inset change. The editor's pane is laid out above the
    strip's measured height, and a caret that was on screen stays on
    screen when the strip grows (as it does when the keyboard opens).
  - **Keyboard: the line being edited stays in view, in Code and
    Preview alike** (Split's Preview pane included). When the keyboard
    opens over the caret, the text moves up with it, frame by frame, and
    the caret's line ends about two lines above the strip at its real
    one- or two-row height; Code keeps it just above. A caret already
    clear of the keyboard and strip moves nothing: a tap near the top
    never scrolls. A taller keyboard (the emoji panel, a suggestion bar)
    checks again; a floating or one-handed keyboard takes no room, so
    nothing scrolls. Each new line typed at the bottom, Enter, Undo and
    Redo, a strip key and Find or Navigator jumps keep the caret or the
    target above the strip, and a rotation or resize with the keyboard
    up keeps the caret on screen. While editing, Preview leaves room
    below the last line so it can sit above the strip too. Closing the
    keyboard never jumps the view back.
  - **The strip never puts the keyboard away.** Its keys never take
    focus, the ¶ and table ⋯ menus open above the strip without taking
    focus (the editor keeps its caret and the keyboard while they are
    open), and a command keeps the keyboard's connection: it commits the
    composing word, applies as one edit and reports the new selection.
    In Preview the edited block is re-rendered under the caret with focus
    held by an invisible editable stand-in, so the keyboard never sees
    focus leave text. Only the Link sheet takes the keyboard, for its own
    fields; closing it gives the caret back to the text.
  - It holds ¶, B, I, bulleted list, **numbered list**, task, code,
    link, **outdent and indent** (a pair, ← then →, so one is found by
    the other), and scrolls horizontally to reveal table, **insert image**
    (a picture icon; TalkBack "Insert image") and quote. **Undo and Redo
    are pinned together** on the right (2026-11-03; Redo used to be the
    last scrolling key, far from Undo). Preview's strip is ¶, B, I, code,
    link, bulleted list, numbered list, outdent, indent, table and insert
    image, with Undo, Redo and Done pinned.
  - **The list keys** (Bulleted list, Numbered list) act on the caret's
    line, or every line in the selection: a plain line becomes that kind
    of item (with a blank line against a paragraph around it, so it is a
    list and not a lazy line); an item of the other kind switches its
    whole list, at its level, to this kind (as Google Docs' list styles
    do; nested lines move with their item when the marker's width
    changes); an item of the same kind leaves the list as a paragraph,
    the rest of the list kept. Each shows pressed for the kind the caret
    is in. A numbered item continues a numbered list just above it, blank
    lines between or not.
  - **Enter in a list item** (Code and Preview alike: Preview asks Kotlin
    to run Code's own Enter, `enter`): at the end, a new item of the same
    kind right after it, wherever it is in the list; in the middle of
    the text, the item splits at the caret. Bullets keep their char
    (`-`, `*`, `+`), a task gives a new unchecked task, and a numbered
    list renumbers the items after it, keeping its start number and
    delimiter (`1. 1. 1.` lists stay that way). A line continuing an
    item after a hard break counts as the item's end.
  - **Enter on an empty item** makes it a paragraph: on the last item
    the paragraph sits below the list, after a blank line; in the middle
    the list splits into two around it, and a numbered second list
    continues the count (a list starting anywhere but 1 cannot follow a
    paragraph line directly in CommonMark, so a blank line goes before
    it). A nested empty item steps out one level first. **Backspace at
    the very start of an item's text** takes the marker off and leaves
    the text in place as a paragraph (a task loses only its box first);
    the next Backspace joins the line to the one above, as anywhere.
  - **Outdent** (the key, or Shift+Tab) moves a nested item out one
    level, and makes a top-level item a paragraph without touching the
    rest of the list: the items after it become a list of their own,
    renumbered as above. So there is always a way to the end of a list
    that keeps the list. **Indent** (the key, or Tab) nests an item under
    the one above; on the first item of a list it does nothing.
  - **On a paragraph, Indent is disabled** (shown greyed, TalkBack
    "Unavailable: only lists and code indent"): four spaces would turn
    the line into an indented code block. A hardware Tab there still
    types a tab.
  - **Code blocks** are edited as code. Indent (or Tab) adds one indent
    unit at the start of the caret's line or every selected line;
    Outdent (or Shift+Tab) removes up to one, and never touches list
    nesting or makes a paragraph. The unit is the block's own: a tab in
    a tab-indented block, otherwise the commonest step between its lines
    (2 or 4 spaces), and 4 spaces in a block with none yet. Enter keeps
    the line's indentation and never adds a list marker, plus one unit
    after a line ending in `{`, `[` or `(` (or `:` in a `python` or
    `yaml` block); a `}`, `]` or `)` typed as the first character of a
    line takes one unit back off. In Preview, Enter on a second empty
    line at the end of a block leaves it: both empty lines go and the
    caret sits in a new paragraph below the closing fence.
  - **Quotes and alerts:** Enter continues `> ` (in Preview a new
    paragraph of the quote); Enter on an empty `> ` line ends the quote,
    with a blank line so what is typed next is not swallowed by it, and
    in a nested quote steps out one level first.
  - **In Code view**, a `{ }` key is pinned at the right end. It flips
    the strip to a **symbols page**: `` ` `` `[` `]` `(` `)` `#` `*` `>`
    `|` `-` `=` `!` and ← → cursor keys (arrows with shafts, hold to
    repeat). Keys are 48×48 dp and the page scrolls.
  - The symbols page stays while you keep tapping symbols, and flips back
    to formatting as soon as you type a letter or tap `{ }`. There is no
    hidden lock.
  - **Landscape with the keyboard up:** the header steps aside. The strip
    then carries the filename, the unsaved dot and the Save pill, so Save
    is always reachable.
  - **Font scale above 1.5×:** the mode switcher goes icon-only (labels
    kept for TalkBack), Copy moves into ⋮, and Save never moves.
  - In Preview the strip shows formatting only, since there is no raw
    syntax to type. In Preview the strip pins Undo, Redo and Done on the
    right (Code pins Undo, Redo and `{ }`).
  - Rationale: progressive disclosure keeps the strip calm. Code-heavy
    typing loses the keyboard's two-tap trip to its symbol layer, and
    every key stays ≥ 44 dp.

## Where am I? (the title's second line)

When you scroll past the first heading, the status line under the
filename becomes a **breadcrumb of the current section**, e.g. "How we
work › **Code review**". The breadcrumb comes from the block index, is
shared by Code and Preview, and updates as headings scroll past the top.
- **Save state stays visible**, appended as text when there is something
  to say ("· Unsaved", "· Saving…", "· Read-only"), alongside the red dot
  and the Save pill. Back at the top of the document, the line shows the
  full save status again ("Saved · 2 min ago").
- Below about 360 dp of title width, only the current section is shown
  ("› Code review"). The parents are dropped rather than truncated to
  useless stubs.

## System bars and insets

The app is edge-to-edge (Android 15 enforces it). The top bar sits below
the status-bar inset. The bottom bar and strip sit above the
navigation-bar inset (3-button or gesture) and the keyboard inset. The
mockups leave system bars out, as the canvas convention, but every
layout budget assumes them:
- **Phone landscape:** the header and mode switch merge into **one 48 dp
  bar** (title · Code/Split/Preview · zoom chip · Save · Copy · ⋮). There
  is no bottom bar, so ~250 dp of text remains even with a 3-button nav
  bar.
- **Landscape with the keyboard:** the header steps aside; the strip
  carries the name, the unsaved dot, undo, `{ }` and Save. The editor
  sets `IME_FLAG_NO_EXTRACT_UI` so the keyboard's full-screen extract
  mode never hides the app.
- The header's bottom hairline doubles as a **reading-position line**:
  a neutral grey fill, 2 dp, full width. It is not an accent colour and
  doesn't animate on its own. It shows the scroll position over the
  scrollable range of the pane in charge (Code or Preview; in Split, the
  pane last touched): empty at the top and when the document fits,
  exactly full at the very bottom.
- Tapping the title opens the Navigator with the current section
  highlighted. **Back to top** is the first row of the Outline ("↑ Top of
  document"), and Ctrl+Home works on hardware keyboards.
- No third bar and no floating button: the existing title does the job.

## Gestures in Preview (precedence, top wins)

1. A tap that **stops a fling** only stops the scroll; it never edits.
2. Tap on a **diagram, image or maths block** → the viewer.
3. Tap on a **link** → a small chip, *Open · Copy link · Edit link*.
   With "Tap to edit" off the chip still appears for a web, mail or
   phone link, as *Open · Copy link*: a tap never launches another app
   by itself (security review SEC-7).
4. Tap on a **task checkbox** → toggles it (one character changes).
5. Tap on **text** → caret and keyboard (if "Tap to edit" is on).
6. **Long-press a heading** → the section menu directly (Copy section as
   Markdown · Copy section as formatted text, the last-used first · Select
   section · Export section…), with **Select
   text instead** as its last row. That's J10 in 2 taps.
7. **Long-press other text** → native selection.
8. **Pinch** → this file's preview text size.
9. **No double-tap gestures** on text in Preview. Double-tap is Android's
   select-word gesture, and listening for it would delay every tap by
   about 300 ms. "Jump to source" isn't needed: switching to Code
   already lands on the same paragraph through the anchor.

## Recovery (reopening after a crash)

Unsaved edits are restored automatically and the editor is usable at
once. A non-modal banner under the header reads "Your unsaved edits are
back", followed by when it happened and "The file on disk hasn't been
touched", with three buttons:
- **Got it** (dismisses the banner).
- **Compare** (a diff against the file on disk).
- **Discard edits**. This records a History checkpoint and shows a
  snackbar, "Edits discarded · kept in History · Undo".

If the file on disk changed meanwhile, the conflict sheet appears
instead (see "File changed outside the app").

## Find & replace (⋮ → Find & replace, or Ctrl+F)

The find bar slides in **under** the header, so the title, the unsaved
dot and Save stay visible while you replace. Close it with ✕ or Back.
The Replace row opens with a "⇄ Replace" toggle at the end of row 1.
- **Row 1:** the query, "3 of 12", and previous / next.
- **Row 2** (only after "Replace" is opened): the replacement, Replace,
  and All.
- **Options:** Match case · Whole word · `.*` (regex).
- Matches are highlighted, and the current match has a stronger fill
  and an outline (in Preview, an underline: the page cannot outline a
  highlight).
- Typing a query makes the first match **at or after the cursor**
  current. **Next / Previous** (the arrows, **Enter** or the keyboard's
  search key for Next, **Shift+Enter** for Previous) step through the
  matches and wrap round; a step that wraps puts a quiet ↻ before the
  count ("from the top" / "from the end" to TalkBack). Stepping never
  closes the keyboard.
- Each step **moves every pane on screen to the match**: Code selects it
  (so typing replaces it) and scrolls the least distance that shows it,
  with a little context, above the keyboard; Preview scrolls it a third
  of the way down unless it is already in view. In Split both move, and
  the Code pane leads.
- **Replace all is one undo step** and records a History checkpoint.
- In Preview, matches highlight in the rendered text, and replacing maps
  back to the source.

## Text size: one model

- **Pinch** and the **View sheet stepper** both set *this file's* size
  for the pane you're in.
- **100% is defined by the preview theme's base size** (Clean: 15 px;
  a custom theme's "Base size" redefines it). Code's 100% is 12.5 sp.
- **Settings → Default zoom** (a percentage of that base) sets the size
  new files start at.
- The **zoom chip** appears when this file differs from the default, and
  tapping it resets.
- The copy says "This file" and "New files" so the difference is
  explicit.

## Colour rule

Red means **"needs you"**: the unsaved dot, the Save / Save as… pill,
Save all, and errors. Everything else that needs emphasis uses **ink**:
syntax markers, checkboxes, toggles, sliders, the caret, the current
table cell, and pins. Text selection uses the system selection colour.
Never put a one-sided coloured border on anything.

The one place colour carries syntax is **inside a fenced code block**
(below): there the theme's code colours apply, as in Preview. A `diff`
block's deleted lines use the theme's deletion colour, a dark red: that
is the document's content, not the app asking for anything.

## Code view: highlighting

Fenced code is coloured by language in the Code view, as Preview colours
it with highlight.js, so the two views look like the same document.

- **Which blocks.** A fence whose info string's first word names a known
  language, any case: ```` ```Python title="x" ````, ```` ```ts ````,
  ```` ```js{1,3} ````. Python, JavaScript, TypeScript, Java, Kotlin, C,
  C++, C#, Go, Rust, PHP, Ruby, Swift, Dart, SQL, Bash/Shell, console
  sessions (commands after a `$ ` prompt), PowerShell, JSON/JSONC, YAML,
  TOML, INI, HTML/XML/SVG, CSS/SCSS/Less, Markdown (flat, nothing nested),
  Dockerfile and diff, with the usual aliases (`py`, `js`, `ts`, `kt`,
  `cpp`/`h`, `cs`, `golang`, `rs`, `rb`, `sh`/`zsh`, `ps1`, `yml`, `md`, …).
  No language, an unknown one, and indented code stay plain: no guessing.
- **What is coloured.** Keywords, built-in types and literals; strings;
  numbers; comments (italic); function and class names; annotations and
  preprocessor lines; sigil variables (`$x`); tags and attributes; keys.
  Punctuation and ordinary names stay plain code text.
- **Where it stops.** The fence lines keep their Markdown styling.
  Colouring stops exactly at the closing fence, even inside an open
  string or comment; an unclosed fence colours to the end of the document.
- **Colours.** The Preview theme's code colours (Themes → Colours → Code
  colours), light or dark with the appearance. Contrast uses its AAA set.
  Every code colour reads at 4.5:1 or more on the code band; a custom
  colour that would not falls back to the default.
- **Long lines.** Only the first 2,000 characters of a code line are
  coloured; the rest is plain code text.
- Selection, find highlights, the caret and the current line draw over
  coloured text exactly as over plain text.

## More decisions from review 3

- **Strip discoverability:** the scrolling part of the strip runs right
  up to the pinned group, which is exactly its 48 dp keys with no padding,
  and its last visible key is cut at about half (35–65%), so it obviously
  scrolls. When 48 dp keys would cut elsewhere, the scrolling keys widen a
  little (at most a fifth on a phone) rather than leave a gap before the
  pinned group. A 12 dp fade sits at the cut, by the pinned group, so the
  cut key reads as half a key, not as a key faded early. Order: ¶ B I
  bulleted numbered task code link outdent indent table image quote.
  Undo, Redo and `{ }` are pinned on the right.
- **Link asks where it goes** (Code and Preview, 2026-11-02; it used to
  write `[text]()` and leave the address to be typed into the syntax).
  One tap opens a small sheet over the strip with two labelled fields,
  Link text and Address (URL). Link text starts as the selection.
  Address starts as the selection when that is itself an address,
  otherwise as the clipboard's text when that is an address (`https://`,
  `http://`, `mailto:`, `www.`); the keyboard opens on Address, whose
  Done key is the same as the sheet's Done. Done writes `[text](address)`
  over the selection, or `[address](address)` when there is no text;
  brackets in the text are escaped, and an address with spaces or
  unbalanced parentheses gets `<>`. Done waits until there is an
  address. Inside an existing link the sheet is "Edit link", prefilled
  from it, rewrites it (keeping its title) and offers **Remove link**,
  which leaves its text. Cancel, or dismissing the sheet, changes
  nothing. Each is one undo step. In Preview the page's selection is
  mapped onto the source first; the edit then reaches the page like any
  host edit.
- **¶ is the block-type menu** (Code and Preview; it replaced Code's H
  key on 2026-11-02, because cycling H could not reach a list, a quote
  or code): Paragraph, Heading 1, Heading 2, Heading 3, Bulleted list,
  Numbered list, Task, Quote, Code block, the caret's current type
  ticked. The choice applies to the block at the caret or every block in
  the selection and replaces each line's block syntax while keeping its
  text: a bullet becomes `## text`, a heading becomes `- [ ] text` (a
  checked task stays checked), several lines become one fenced block
  (with a fence their content cannot close), and a code block's lines
  come out of the fence as the new type, taken literally. A new
  paragraph gets a blank line wherever it would otherwise join the
  block above or below. Only those lines change. **A choice applies to
  the caret's line, or the selected lines, never the whole block they
  are in** (2026-11-03): one line of a fenced block splits it, the code
  above keeping its fence with a closing one added and the code below
  getting a new opening fence like the block's own (characters, length,
  info string); on the last line the fence closes before it, which is
  how you leave a code block with ¶. No empty fenced block is ever left
  behind, and a selection over every line of a block converts it whole.
  A quote, alert or multi-line item splits the same way around the
  line. A line made a code block right next to a fenced block joins
  that block instead of starting a second one. **In a table ¶ is
  disabled** (greyed, TalkBack "Unavailable in a table"): one row turned
  into a heading or a list item would break the table, so the command
  leaves tables alone too. Rows are 48 dp and read
  as radio buttons ("Heading 2, selected"); the key itself reads "Block
  style, Heading 2". Preview sends the same conversion to Kotlin
  (`blockHeading2`…), so both views give the same text.
- **Split divider:** 48 dp invisible touch area around the 14 dp
  handle, plus TalkBack actions "Make top larger / smaller". Task
  checkboxes in Preview get a 48 dp hit area.
- **Copy doesn't jump:** the order is **title · Save · Copy · ⋮**, so
  Copy is pinned beside ⋮ and never moves, whatever Save shows. The Save
  slot is as wide as its pill or tick (at least one 48 dp target); the
  room it doesn't need goes to the title. (Until 2026-11-02 the slot was a
  fixed 96 dp, from when Copy sat before Save; after the order changed it
  only left a gap beside the pill and took room from the title.)
- **Big-file chip** reads "Big file" (TalkBack: "Big-file mode"; a tap
  explains it), so a long name and its breadcrumb keep their room.
- **Clean tick:** muted ink (`#636369`). It *is* the Save button in its
  clean state, so it's present in autosave too.
- **Share-in button label** follows the choice: "Append" or "Create
  note".
- **Leaving:** Home or Recents never prompt, because the journal
  protects everything. After the app is swiped away from Recents, the
  recovery banner says "SS Markdown was closed before you saved", not
  "closed unexpectedly". Dismissing the conflict sheet keeps your edits
  open, leaves autosave paused, and leaves the "Changed in another app ·
  Review" banner in place.
- **Mermaid sheet:** "Save as PNG" uses the scale chosen in Export images
  (default 2×).
- **Non-Markdown files** (`.txt`) are muted in file lists: they open in
  Code only, with no Preview.
- **Editor accessibility:** `MarkdownCodeView` exposes visible lines as
  a virtual accessibility tree, with cursor movement by character, word,
  line and heading. Preview editing relies on the WebView's own
  `contenteditable` accessibility, and the keyboard matrix includes
  TalkBack.

## Storage (Settings → Storage and history)

One screen that answers "why is this app using space?" and reminds
people that history exists.
- **Breakdown.** The total, plus a bar and a list:
  - Version history (n files);
  - Downloaded fonts;
  - Diagram and preview cache;
  - Drafts;
  - Recovery copies of unsaved work;
  - the app itself.

  Sizes are measured off the main thread and cached; the screen shows
  the last known values at once and refreshes them.
- **Free up space** (each clear first says exactly what it removes and
  offers **Export first**):
  - **History of files not opened in** [30 days · 90 days · 6 months ·
    1 year] → Clear. It shows the file count and the space it frees.
  - **Versions older than** [1 month · 6 months · 1 year] → Clear,
    across all files. The latest version of every file is always kept.
  - **Diagram and preview cache** → Clear. Safe; it rebuilds when
    needed.
  - **All version history** → Clear… A confirmation asks about pinned
    versions (kept by default).
- **Never touched by any clean-up:** pinned versions (unless chosen),
  **drafts**, and **recovery copies of unsaved work**. Drafts are
  deleted individually from the Navigator's Drafts list.
- **History by file.** Sorted largest first, or by last opened. Each row
  shows size, versions, pins and last opened, and its ⋯ offers Export
  history (.zip) · Clear history · Open history.
- **Keep version history** (on by default) is the top row of the
  Automatic section.
  - **Off** means no new versions are recorded and History shows "Version
    history is off · Turn on".
  - **Turning it off asks** whether to keep the existing history, delete
    it, or export and then delete it. Deleting gets the same
    are-you-sure dialog as the clears.
  - **What stays on regardless:**
    - **Crash recovery** (the journal). It's small (it keeps unsaved edits,
      and while a document is open its newest 8 MB of undo steps, so Undo
      survives a kill), deleted when the document closes, and is the core
      never-lose-work promise, so it isn't tied to this toggle.
    - **The base copy of each open file**, held only while that file has
      unsaved edits, so the conflict sheet can still offer Merge.
  - History uses storage, not running memory. The app's memory use comes
    from open documents.
- **About** (Settings → About) shows the version, "Made by Scorchsoft"
  (a plain credit, not a link), the Apache 2.0 licence, "Source code on
  GitHub" (github.com/scorchsoft/ss-markdown, opened in the browser: the
  app's only outside link, so questions and bugs go to the repository, not
  to Scorchsoft) and a plain-language
  disclaimer: "Provided as is, without warranty of any kind. Keep your
  own backups of anything important."
- **Every clear asks first.** A confirmation dialog appears before any
  clear (bulk, per-file or "all"). It says:
  - exactly what will be deleted ("642 saved versions (18.4 MB) of 11
    files");
  - **"This can't be undone."**, highlighted;
  - that the files themselves, pinned versions and drafts are kept.

  An **"Export these versions first (.zip)"** checkbox is ticked by
  default, so the button reads *Export and clear*. Nothing is deleted
  until the export has finished and been verified. Unticking it changes
  the button to *Clear permanently*. The cache clear is the exception:
  it is rebuildable, so it needs only a simple confirm.
- **Automatic limits: the rule is never to surprise anyone.**
  - **History limit: 1 GB by default** (choices: 250 MB, 500 MB, 1 GB,
    2 GB, 5 GB, No limit). Normal use never reaches it: 1,000 versions
    of a typical document take about 1–3 MB.
  - **Per-file cap: 100 MB** by default, applied with the same
    protections.
  - **"Forget history of files unused for": Never** by default.
  - **What a limit may remove:** only *thinned intermediate versions,
    oldest first.* It never removes:
    - a file's latest version;
    - anything from the last 7 days;
    - pinned versions;
    - drafts;
    - recovery copies.
  - **Nothing is removed silently.**
    - The first time a limit starts thinning, a one-time, non-modal
      notice appears: "History reached your 1 GB limit · older versions
      are being thinned · Manage".
    - Every automatic removal is listed in **Storage → Recent
      clean-ups** (for example, "3 Nov · thinned 34 older versions of
      handbook.md to stay under 1 GB").
    - If the limit can't be met without breaking those protections, the
      app stops thinning and says so; it doesn't delete more.
  - **Low device storage** (under 500 MB free): a warning offers the
    Storage screen. The app never deletes history to free device
    space.
- **Import history:** ⋮ on the Storage screen → Import history (.zip).
  This attaches the versions to the matching file by name and content
  fingerprint, or to a file you pick.
- **Explaining Android's buttons** (an info note on this screen):
  - Android's *Clear cache* only removes the diagram/preview cache, and
    is safe.
  - Android's *Clear storage* deletes everything on this screen,
    **including drafts and unsaved recovery copies**, and resets
    settings. Markdown files themselves are never touched.
  - To make that true, the diagram and preview cache lives in the
    app's `cacheDir`, while history, journal, drafts and fonts live in
    `filesDir`/`noBackupFilesDir`.

## Glossary

- **Unsaved** — has edits not yet written to the file.
- **Draft** — an untitled document kept on the phone.
- **Recovered** — edits restored from the journal after a crash.
- **Big-file mode** — reduced extras for a large file (previously "lean
  mode").
- **Changed in another app** — the file on disk differs from what we
  loaded.
- **Navigator** — the sheet opened by tapping the title.
- **Formatting strip** — the bar above the keyboard: one row, or two if Settings asks and there is room.

## Specified, mockup to follow

- **Tablet Navigator.** A 420 dp popover under the title.
- **Keyboard-shortcut help** (Ctrl+/). A sheet listing every shortcut.
- **Export maths blocks toggle.** In Export images → Include, there's a
  "Maths blocks" switch, off by default.
- **Rename / delete a file.** Long-press a file in the drawer or the
  Files tab → Rename · Duplicate · Delete. The ⋯ on each row is the
  visible twin. Delete asks for confirmation and keeps the history entry.
- **A file too large to open.** A full-width message in the editor area:
  "This 180 MB file needs about 400 MB, and this phone allows 256 MB",
  with Open read-only (streamed) and Share.
- **Big-file mode explanation.** Tapping the chip opens a small sheet
  listing what's paused (live preview while typing, diagram cache, spell
  suggestions) and why.
- **Preview restarted.** A one-line message in the preview pane, "Preview
  restarted", that fades after 2 s. After two crashes in a minute, the
  pane shows "Preview paused · Retry".
- **Navigator, no results.** "Nothing matches “xyz”" followed by "Open a
  file…". Full-text search across files is out of scope.
- **About → Open-source licences.** Every bundled library and font
  (Apache-2.0, MIT, OFL), generated at build time.

## Fast scroll

A thumb for long documents, like the draggable pill in Contacts, Files and
Gmail: it saves flicking through a 20 MB file. It is chrome that appears
only when it applies.

- **When it shows.** As soon as the user scrolls a pane (Code, Preview, or
  either Split pane) whose document is longer than about four screens. A
  pane moved by something else (Split following its twin, a Navigator
  jump, find) does not show it. It fades about 1.5 s after scrolling
  stops, unless held. It never shows while selection handles or the text
  toolbar are in use.
- **Look.** A pill 6 dp wide and 44 dp tall, 3 dp from the right edge, in
  the gutter grey, ringed by a 1.5 dp halo of the page colour so it stays
  a distinct handle over text; it eases to 10 dp and `inkMuted` while held
  (at once with "Remove animations"). Its touch target is 48 × 48 dp. No
  track: the reading line under the header already shows progress, and
  no view draws a platform scrollbar beside it (the Preview WebView's is
  off).
- **Drag.** The pill's position is a fraction of the document and the pane
  jumps there live. Code maps it by line index through the height map,
  so a file with estimated heights stays steady and only moves forwards
  as the finger does; Preview maps it by fraction of the page height,
  one message to the page a frame. Letting go leaves the pane there: the
  caret does not move and nothing is edited.
- **Section bubble.** While held, a bubble left of the pill names the
  heading of the section at the pane's top (the Navigator's outline).
  Before any heading, Code shows "Line N" and Preview shows nothing. A
  light haptic tick marks each change of section, as the system's
  haptics setting allows.
- **Gestures.** The right edge is Back under gesture navigation, so only
  the thumb's 48 dp target is excluded from the back gesture, only while
  it shows (Android caps exclusion at 200 dp per edge). Hidden, it takes
  no input: a tap there reaches the text. Only vertical scrolling shows
  it, so No wrap's sideways pans are untouched.
- **Split.** Each pane has its own thumb. Dragging one moves that pane,
  which then leads, and the ordinary scroll sync moves the other.
- **Accessibility.** It is a pointer convenience, hidden from TalkBack
  (which has the Navigator and page scrolling). With "Remove animations"
  on it shows and hides without a fade.
- **Keyboard.** It stays inside the pane, which sits above the keyboard
  and the formatting strip.

## Navigator (tap the title)

One sheet answers "where am I, and where can I go?". It replaces the
separate Recent menu.

- It opens by tapping the title or pressing Ctrl+K / Ctrl+P. There is no
  edge swipe, because gesture navigation owns both edges for Back. On
  tablets (600 dp and wider) it is a 420 dp popover under the title.
- On a phone it **fills the height** below the header, down to the mode
  bar, and its list scrolls inside it. A short outline leaves the
  sheet's own background below it, never a half-height sheet over the
  editor.
- **Closing it.** A × (48 dp, "Close navigator") sits beside the search
  field; Back closes it; tapping the title again closes it (the title
  stays visible above the sheet, and TalkBack reads "Navigator,
  expanded" or "collapsed"). One icon, no extra bar.
- The search field **autofocuses with the keyboard up**. The sheet sits
  above the keyboard, so the outline stays visible under the field. Back
  or a swipe down closes the keyboard and shows the full outline.
- Matching is **word-start and contiguous only** ("sav" matches "Save
  flow" and "savings-plan.md", never "re*s*e*a*rch-o*v*erview"). Results
  are ranked headings, then recent files, then folder files. The Ctrl K
  hint shows only when a hardware keyboard is attached.
- **Search field on top (the quick switcher).** It matches word starts
  as you type, across three groups, in this order:
  - headings in this file;
  - recent files;
  - files in the open folder.
  Enter or a tap jumps to the result. An empty query shows the tabs.
- **Outline tab** (the default when a file has headings): an indented
  heading tree. The current section is highlighted. Tapping a heading
  jumps there in the current view (Code or Preview). The outline is
  built incrementally from the block index, so it's cheap even for huge
  files.
- **Files tab:** recent files (pinned files first), then the folder tree
  when a folder is open, with unsaved dots. Untitled drafts sit in a
  **Drafts** group at the top, with their first line and age, so a draft
  left behind by "New" is never lost. Drafts older than 30 days and
  untouched get a gentle "Old draft" label; they are never auto-deleted.
- Every outline row has a **⋯** (the visible twin of long-press). Every
  file and draft row has a ⋯ for Rename · Duplicate · Delete.
- The Navigator opens **below the header**, so the title, the unsaved
  dot and Save stay visible.
- **Long-press a heading** here or in Preview to get:
  - Copy section as Markdown
  - Copy section as formatted text (the two in the Copy chooser's order:
    the last-used first and filled; "Section copied as …" confirms)
  - Select section
  - Export section…
  A section runs from the heading to the next heading of the same or a
  higher level.

## Copy and paste

Code is the raw text; Preview is the rendering. Each pastes the way its
surface reads (docs/editing-behaviours.md, EB-CLIP, EB-LINK; the matrix
tests are `PasteMatrixTest` and `e2e/paste.test.ts`).

A normal paste **keeps formatting** (Andrew, 2026-11-03). **Paste as
plain text** puts in only the words. Both are one Undo step.

**Copying a selection** (Andrew, 2026-11-04) offers the same two forms as
the header's Copy chooser. In Preview the text menu has **Copy Markdown**
(the selection's exact source) and **Copy formatted** (its rendering,
inline-styled for mail and documents, with a plain-text twin) after Copy,
which still carries both so a paste back into Preview keeps formatting.
In Code, Copy is the Markdown and **Copy formatted** renders the lines
the selection touches.

**HTML** on the clipboard (a copy from Chrome, Gmail, Google Docs or
Word) is converted to Markdown, the same way in both surfaces
(`HtmlToMarkdown.kt`, `htmlToMarkdown.ts`, one set of vectors): headings,
paragraphs, line breaks, bold, italic, strikethrough (from tags or from
Docs' and Word's styles), inline code, code blocks with the language of a
`language-x` class, links (web and `mailto:` only; any other link keeps
its words), web pictures as `![alt](address)` (others are left out),
nested bulleted and numbered lists with their start number, task lists,
Word's paragraph lists, quotes, rules, and tables: a simple one as a GFM
table, one with merged or nested cells as rows of text. Scripts, styles,
comments, hidden text, forms and media are dropped and nothing in the
clip runs or loads. Whitespace collapses as HTML collapses it. When the
HTML formats nothing (a single span, plain paragraphs), the clip's plain
text is used instead, and inside a code block the plain text always is.

**Code.** Plain text goes in exactly as copied: CRLF and lone CR become
the document's line breaks, a NUL is dropped (a saved NUL would make the
file reopen as "not text") and so is one leading BOM; every other
character goes in as copied, NBSP, zero-width and bidi marks included.
Nothing is added or taken away whatever block the caret is in (code,
list, table, quote): no markers, no escapes. HTML goes in as Markdown
(above). A selection is replaced. The caret ends after the paste and the
view follows it. A paste over 256K characters, and any HTML or plain-text
conversion, goes in off the main thread by slices with progress and is
still one undo step. Copy and Cut take exactly the selected source; with
nothing selected they do nothing.

**Preview.** A paste is Markdown **kept literally** (EB-CLIP-06): text
copied from a `.md` file, Preview's own copy and converted HTML go into
the source as written, so they render formatted. Nothing is escaped,
except what would break the block it lands in:
- **Several lines** of prose (no Markdown in it) join the way Enter would
  there: paragraphs in a paragraph, paragraphs after it in a heading, `>`
  paragraphs in a quote. Markdown with block syntax or several lines gets
  blocks of its own (in a quote, every line keeps its `>`), so a copied
  list pastes as a list after a paragraph. In a **list item** (decision
  D10), a Markdown list on the clipboard becomes items of the same list,
  the item split at the caret and the numbers after it counted on; other
  lines join the item as continuation lines indented to its text. In a
  **table cell** the lines are joined by `<br>` and pipes become `\|`, so
  the table never breaks. In a **code block** the text goes in verbatim.
  Blank lines and a trailing line break add nothing.
- `snake_case`, `a * b` and `$5` show as they are (CommonMark leaves an
  `_` inside a word alone).
- **Copy** of a selection puts its Markdown source on the clipboard as
  plain text and its rendering as HTML; Cut does the same and removes the
  selection's source as one edit. An end at the start or end of a
  block takes the block's whole line, so a copied list is `- a` lines.
  A selection with one end inside bold, a link or code and the other
  outside it is copied by the browser as rendered text instead, so it
  never carries half of the syntax. **Pasting Preview's own copy** back
  into Preview goes in as Markdown: bold stays bold, a link stays a link,
  and a copied list pasted after a paragraph is a list.
- A paste aimed at text Preview cannot edit (maths, an autolink, raw
  HTML) is refused with the usual message and never lands elsewhere.
- In **Split**, a Preview paste reaches Code as one undo step.

**Paste as plain text** (EB-CLIP-14). In the text toolbar right after
Paste, in Code and while editing in Preview, only when the clip is
formatted: it has HTML, or its plain text holds Markdown syntax. On a
hardware keyboard, Ctrl+Shift+V. It inserts only the words of what Paste
would insert: no `#`, `*`, `_`, `` ` ``, `>`, brackets, list markers,
task boxes or fences; each list item on a line of its own; a table one
row per line with its cells separated by a tab; a blank line between
blocks; a link as its text and a picture as its alt text; a code block's
lines as written. In Preview the words are escaped and fitted to the
block (cell, list, quote) so they show exactly as pasted. It never makes
a link of a selection. In Preview, Chromium's own "Paste as plain text"
item does the same; the app adds its item only when Chromium shows none.

**A web address pasted over selected text** (both surfaces, decision D9):
when the clip is one bare `http(s)://` address and the selection is text
on one line outside code and outside a link, and is not itself an
address, the selection becomes `[text](address)` (brackets escaped,
awkward addresses in `<>`). It is two undo steps: the first Undo leaves
the address pasted as text, the second brings the text back. Anything
else (no selection, inside code, more than an address) pastes as text.

## Copy formatted text

What "Copy formatted text" (the Copy chooser, ⋮ → Copy as…, the section
menu) puts on the clipboard: one `text/html` clip whose item holds HTML for
apps that paste formatting and a plain-text twin for apps that paste text.
It is Preview's own rendering, rebuilt for pasting (`web/src/page/copy/`):

- **Inline styles only**, in fixed light colours whatever the app theme:
  Gmail and Outlook drop `<style>` and classes, and dark text pasted into a
  dark editor is unreadable. Body text inherits the target's font and size.
- Headings bold at 20 / 18 / 16 / 15 / 14 / 13 px; paragraphs and lists
  with small margins; blockquotes (and alerts, in their colour) with a left
  border; tables with 1 px borders, padding, a shaded header row and their
  column alignment; code blocks monospace on light grey, wrapping; inline
  code monospace on a lighter grey; ==marks== highlighted.
- Links stay `<a href>` (web, mail and phone only; links inside the
  document or to other files become their text). Task lists are ☐ and ☑
  (checkboxes do not paste). Footnote references are superscript numbers
  and the footnotes follow at the end under a rule. Front matter, Edit in
  Code chips, the table of contents placeholder and other page controls
  are left out.
- **Pictures** next to the file (and inline `data:` images) are embedded as
  `data:` images on white, PNG first and then JPEG at falling sizes until
  one fits: at most about 150 KB each and 225 KB in all (200 000 and
  300 000 characters of data URL), shown at most 640 px wide. A picture
  that does not fit, or cannot be read, becomes its alt text in brackets.
  A web picture is never fetched (the app is offline): it pastes as a link,
  "[alt]", to its address.
- **Mermaid** diagrams are drawn afresh in the light theme and embedded as
  a PNG with alt text ("Diagram: flowchart", or its `accTitle`); one that
  cannot be drawn becomes "[Diagram: …]".
- **Maths** is readable text laid out from KaTeX's MathML ("E = mc²",
  "∫₀¹x² dx = 1/3", "(a + b)/2", "√x"), in a maths serif, because MathML
  does not survive a paste into Gmail or Google Docs.
- **Plain-text twin**: the same words without Markdown symbols: headings
  as lines of their own, "•" and "1." list markers (nested lists
  indented), ☐/☑ tasks, quotes indented four spaces, tables tab-separated
  (a spreadsheet pastes them into cells), pictures as "[alt]", links as
  "text (address)", footnotes as ¹ and a list at the end.
- **Size**: HTML and plain text together stay within the clipboard's
  Binder limit (400 000 characters, robustness §5). Words come first,
  pictures get what is left. A longer document is rendered a slice at a
  time and stops when the budget is spent, ending with "[Shortened to fit
  the clipboard…]", and the message says it was shortened. The work runs
  in the Preview page (its renderer and worker), never on the app's main
  thread; pictures and diagrams have 8 s before the rest stand in as alt
  text.
- **From Code**, the Preview comes into view while the page renders (a
  page that is not drawn may never finish, as for Export PDF) and Code
  returns once the copy is made. The copy belongs to the document it was
  asked for: if another file opens first, that file's text is never used.

## Share into SS Markdown

SS Markdown is a share target for text (`text/plain`, `text/markdown`)
and for `.md` files. Sharing text opens a small sheet over the sending
app:
- **New note** — an untitled draft containing the text.
- **Append to…** — recent and pinned files, newest first, as rows in
  the sheet itself, the last-used one first and pre-selected. So the
  last-used file is SS Markdown → Append, and any other recent file is
  SS Markdown → the file → Append (2 and 3 taps after the sending app's
  Share; `TapCountAuditTest`).
- **Direct Share** (ADR 0017): Android's own share sheet also shows up to
  four recent files we may still write, as SS Markdown targets ranked
  like the rows above, each labelled with the file name and a document
  icon. Tapping one opens this sheet with that file chosen and listed
  first, so any recent file is the file → Append (2 taps).
  - It **preselects; it never appends.** The sheet is an exported
    Activity, so any app could send a shortcut id; the user's Append is
    the confirmation. The id is a hash of the file's URI, never the URI,
    and is mapped back only through our own recents: an unknown or forged
    id, a forgotten file or one whose write grant is gone just opens the
    normal sheet.
  - The same shortcuts appear when you long-press the app icon; tapping
    one opens that file in the editor.
  - **Privacy:** file names show in Android's share sheet and the
    launcher's menu. Settings → Sharing → "Show recent files in the share
    sheet" (on by default) removes them all.
  - Options: Bottom (default) or Top; a prefix of none (default) or a
    `### <date time>` heading.
  - The last-used target and options are remembered.
- Appending runs through the normal save pipeline: fingerprint check,
  backup, verify, and a history entry ("Appended from Chrome").
  - If the target has unsaved edits open in the app, the text is added
    to that buffer instead. It is then marked unsaved, so it can never
    race the editor.
  - If the target is read-only or conflicted, the sheet says so and
    offers New note.
- Once done, the **sheet itself** shows "Added to <file> · Open" for
  about 1.5 s, then closes, leaving you in the app you shared from.
  (Toasts on Android 12+ can't carry actions.)
- **Already there.** Before any append to an existing file (open with
  or without unsaved edits, or opened from closed), the sheet checks
  whether the shared text is already where Append would put it: within
  its own length plus about 512 characters of the end (Bottom), or of
  the insertion point (Top). Line endings (CRLF/LF) and blank space
  around the text don't count, and the text must sit on lines of its
  own, so the same words inside a longer sentence are not a match. Only
  that window is read, never the whole file. If it is there, nothing is
  written and the sheet says "This text is already at the end of
  <file>." (or "…at the top of…") with **Open <file>**, **Add again**
  (appends it anyway, once) and **Cancel**; TalkBack reads the message
  as it appears. Blank text is never checked, and New note never
  checks.
- **An append cut off.** If Android closes the app while an append is
  still writing, the rebuilt sheet checks the file the same way before
  offering Append again: if the text landed, it shows "Added to <file>"
  and closes; if not, it is choosing again, with one quiet line above
  Append, "The last Append didn't finish. Nothing was added." An
  interrupted New note comes back choosing with the same line (drafts
  are not searched).
- The sheet also offers **Cancel** and an **Another file…** row (the
  system picker) for targets that aren't recent.
- Prefix options are *none* (default) or *a date heading*.

## View sheet (⋮ → View, ⋮'s "Aa", or long-press a mode)

One sheet with a **fixed layout**, the same from every entry point:

1. **Lines**: a **Code** row (Wrap / No wrap) and a **Preview** row
   (Fluid / A4 page / No wrap), each a label and a compact segmented
   control. Preview's No wrap matches Code's: each source line of text
   stays one line (a list item or table cell one line each), never wrapped
   at the screen's edge, and the page pans sideways (Andrew, 2026-11-04;
   it used to unwrap only code and tables, so prose looked like Fluid).
2. **Appearance** (System / Light / Dark).
3. **Text size · this file**: Code − / + and Preview − / +, with the real
   size beside each and "Reset to your default".
4. **Preview theme** (Clean, Reader, Compact, Contrast, + custom, Edit
   theme…).

Context changes emphasis, never order: a long-press on Code tints the
Code row for about a second (fading out) and moves focus to it; Preview
does the same for the Preview row; Split, or opening from ⋮, tints
nothing. The sheet fits a 412 × 915 dp phone at 1× font without
scrolling; it scrolls only at large fonts.

**A4 page mode.** On phones the A4 page renders at fit-width. Pinch in
A4 zooms the page (it doesn't reflow), and the zoom chip resets to
fit-width. On tablets, at 100% page width, body text is at least the
theme's base size at 100%.

## Layouts

| Window | Split direction | Folder UI |
|---|---|---|
| < 600 dp wide (phone portrait) | Top / bottom, draggable divider | Modal drawer |
| ≥ 600 dp wide (phone landscape, small tablet) | Follows the window's shape (below) | Modal drawer |
| ≥ 840 dp wide (tablet, unfolded) | Follows the window's shape (below) | Docked, collapsible panel |

**Split follows the window's shape.** A window wider than it is tall
(square counts as wide) splits side by side; a taller one stacks Code
over Preview. Side by side also needs a page at least 600 dp wide, so a
small split-screen window and a page narrowed by the docked folder panel
stack. So a 600 × 960 dp tablet in portrait stacks, and the same tablet in
landscape splits side by side. The shape is the window's, not the page's:
the keyboard shortens the page but never turns the panes round.

In Fluid mode, preview text is capped at about 75 characters per line on
wide windows. The divider position is remembered separately for each
orientation.

### Split while typing

Settings → Text → **When typing in Split** decides what stacked Split
shows while the keyboard is up for one of its panes.

- **Show only the pane you're editing** (default). Tapping into a pane
  opens the keyboard and that pane fills the editor area: it grows in
  about 160 ms and the other pane slides out of view. Split stays the
  selected mode. The pane you were not editing cannot be tapped while it
  is hidden; close the keyboard or use the mode bar to get back.
- **Keep both panes.** Both panes stay while you type.
- Typing ends when the keyboard closes (Done, Back, or the system hiding
  it), when you switch mode, or when another document opens. Split comes
  back with both panes, each where it was, and with "Scroll panes
  together" the hidden pane has followed the one you typed in.
- The caret stays where it was as the pane grows, so it stays visible.
- **Side by side there is room for both**, so both panes always stay,
  whatever the setting (a wide window, a tablet, landscape with room).
- A window too short to stack folds Preview into a one-line strip under
  Code; if you are typing in Preview there, the page fills the area
  instead, whatever the setting.
- The typing state is not saved: a rotation keeps it while the keyboard
  stays up, and an Activity rebuilt by Android, or a process restart,
  comes back in ordinary Split.
- Neither pane is ever rebuilt or moved to another parent for any of
  this (robustness §5a rule 2): a view that leaves the window takes the
  keyboard with it (edge-cases "Split: the keyboard showed and went away").

## Position continuity

- The source line at the top of the visible area is the **anchor**.
- markdown-it stamps `data-line` on block elements. Each view converts
  the anchor to and from a scroll offset, interpolating inside tall
  blocks.
- In Split, the panes scroll together (a setting, on by default); the
  pane being touched leads.
- The cursor, selection, scroll, view mode and zoom are stored per file.

## Zoom

Pinch changes font size: code sp and preview px. It never scales the
page as a bitmap. A transient "Pinching · 112%" pill shows while the
pinch is in progress. The result is stored per pane. The default zoom is
set in Settings.

## Saving and safety

- Untitled documents autosave to app-private drafts.
- Real files use an explicit Save. Autosave is an opt-in setting.
- A recovery journal keeps unsaved buffers across crashes.
- Before writing, the file's fingerprint is compared with what we
  loaded. If it differs, nothing is written: a banner says "Changed in
  another app · nothing is overwritten · **Review**", and Review opens
  the conflict sheet (see "File changed outside the app"). The conflict
  sheet is the only place conflicts are resolved.
- Leaving with unsaved changes prompts Save / Don't save / Keep editing.
- New, Open file… or opening from the Navigator, from an untitled draft
  with text in it, asks "This draft isn't saved to a file yet": Save as…
  (then on to the new draft or the file), Keep as draft, Delete draft
  (Undo straight after) or Keep editing. Nothing is lost either way;
  the prompt says where the draft went.
  This happens only when a buffer would leave memory (see `robustness.md`
  §6); switching files in a folder never prompts.
- First open of a file, under "Last used": **Preview** for files opened
  from another app, **Code** for files created in SS Markdown.

## Folder mode

- Opened through the system folder picker; access persists.
- The tree shows `.md`, `.markdown`, `.mdx` and `.txt` files, with
  "show all files" in the folder ⋮ menu.
- Search finds files by name.
- Dirty files show a dot. Collapsed folders show "Unsaved: n" in a
  needs-you red tag (`needsYouText` on `needsYouBg`, AA in both themes);
  the amber tag is kept for "changed in another app".
- A "n files unsaved · Save all" row exists only while n > 0.
- Several dirty buffers are held in memory, with no tabs. The drawer and
  the Navigator are the switchers.
- Docked (≥ 840 dp), the header's ≡ hides the panel ("Hide folder") and
  brings it back ("Show folder"), so the text can take the full width.

## Editing in Preview

Preview is not read-only. Tap rendered text and the cursor lands there,
the keyboard opens, and the block under edit gets a faint tint.

- The formatting strip works on rendered text: a block-style picker
  (the ¶ menu: Paragraph, Heading 1–3, the three lists, Quote, Code block), then B, I, inline code,
  link, bulleted and numbered list, outdent and indent, insert table, and undo. Toggles show their state, so Bold
  is pressed when the cursor is in bold text.
- **Edits never rewrite bytes you didn't touch, even inside the block.**
  - The renderer emits an **inline source map**: every text run in the
    page knows its exact source offsets.
  - A text change in Preview (typing, deleting, pasting plain text) maps
    to a **minimal source edit** at those offsets. Fixing a typo changes
    only those characters.
  - Formatting actions (bold, heading level, list toggles) insert or
    remove markers using **the document's own style**, detected per
    file: `*` vs `_`, `-` vs `+` vs `*` bullets, `1.` vs `1)`, hard break
    style. Only the touched node is re-serialised.
  - Constructs that can't round-trip safely are shown as **non-editable
    in Preview**, with a small "Edit in Code" chip: raw HTML, footnote
    and reference definitions, front matter, maths and diagrams. Tapping
    the chip switches to Code at that line.
  - Edits apply to the source **as they happen**, debounced at about
    150 ms. They are never held until the cursor leaves the block, so
    rotation, a crash or switching view never loses a Preview edit.
- **Reading guard:** a tap within 500 ms of a scroll or fling never
  starts editing. While editing in Preview, the strip shows a **Done**
  key that closes the keyboard and returns you to reading.
- Links: tapping shows the *Open · Copy link · Edit link* chip (Gestures, rule 3).
- Diagrams, maths and images are not text, so tapping them opens the
  viewer instead of a cursor.
- A setting, "Tap to edit in Preview", is on by default; turn it off for
  a read-only Preview.

### Tables

Prev/Next cell keys are 48 dp. Each column and row handle has a 48 dp
touch area and TalkBack actions (move left/right/up/down, delete), with
⋯ in the table toolbar as the visible twin. Tapping a cell puts the
cursor in it, and a dark table toolbar appears
above the table:
- **+ Row** and **+ Column** insert after the current cell.
- Align left / centre / right applies to the current column.
- **⋯** offers insert before, move, delete row or column, and delete
  table.

The formatting strip's right end becomes **‹ Prev cell / Next cell ›**.
"Next" on the last cell adds a row. Thin handles above columns and
beside rows show the current column and row; long-press a handle to
move it. Wide tables scroll sideways inside their own box, with a fade
at the edge, and never scroll the page.

The table is written back **in its existing style**. A compact table
(`|a|b|`) stays compact. A padded table stays padded, re-padded only
where a cell's width changed, using display width (East Asian wide
characters and emoji count as 2 columns; graphemes, not chars).
Alignment markers are kept. Only the table's own lines change, and only
the rows that need it.

Every table operation leaves a valid GFM table: header, delimiter and
body rows, no blank line inside it, columns counted the way the renderer
counts them (a pipe inside a code span still splits a cell; only `\|`
does not). **Delete row** removes the row's whole line, terminator
included; it never touches the header or the delimiter row, so deleting
the only body row leaves a header-only table. **Add row above** from
the header adds the first body row. **Delete table** removes the table's
lines and keeps the blank lines around it. A table written without outer
pipes gets a pipe at the start (or end) of a row whose first (or last)
cell is empty, because GFM drops an outer pipe and the cells would shift.
Cells past the header's count are hidden by the renderer but kept when
the row is rewritten. Every entry of the strip's table menu works in
Preview.

## Viewer (diagrams and images)

Tapping a diagram or image in Preview, or "View full screen" from its
long-press sheet, opens a full-screen viewer:
- It has its own pinch, pan and double-tap zoom, separate from the
  preview's text zoom. A chip shows the zoom ("250%") with a Fit button.
- Diagrams are vector, so they stay sharp at any zoom. A mini-map
  appears when zoomed in.
- A strip along the bottom holds every image in the document. Swipe or
  tap to move between them ("2 of 5").
- Save and Share sit in the viewer's header.

## Pictures

`![alt](path)` with a relative path shows the picture beside the
document. Remote pictures are never fetched: the app is offline.

**Where a path resolves.** Against the document's own folder, never
climbing out of the folder the user granted (SEC-9):
- in folder mode, through the open folder;
- for a file opened on its own (Files, Drive, another app's View, Open
  file), through the folder the user granted for it once ("Allow
  access", below), or through any folder grant the app already holds that
  contains it, so a file inside a folder opened before needs nothing new
  (ADR 0018);
- for a draft, in the drafts folder (its `images/`).

**Placeholders.** A picture that cannot be drawn becomes a calm box in
its place: a line icon, the reason, the alt text and the path as written
(the URL for a remote one). It is the size of a short paragraph, and a
picture still loading holds the same room, so text does not jump when it
arrives. The box cannot take a caret, and a tap on it does nothing (no
viewer, no editing). TalkBack reads "Picture not shown: <alt>. <reason>.
<path>".

| State | Says | Action |
|---|---|---|
| Not found | "Not found next to this file" | — |
| No folder access | "Allow access to this folder to show its images" | **Allow access** button |
| Not a picture | "Not a picture this preview can show" (also a format WebView cannot decode) | — |
| Too large | "Too large to show here" (over 32 MB) | — |
| Remote | "Online images aren't loaded", with the URL | — |
| Unsupported | "Only pictures next to this file can be shown" (`file:`, `content:`, absolute paths) | — |

The page asks the app why once per burst of failures (one `imageQuery`
for every picture that failed in ~40 ms), never once per picture. The
viewer and Export images skip a picture that cannot be read.

**Allow access.** The button, or ⋮ → Show pictures beside this file…,
opens the system folder picker at the document's folder where the
provider's ids say where that is, else at the document. The folder must
contain the document; if it does not, "That folder doesn't hold this
file. Choose the folder it's in." and nothing is kept. Otherwise the
grant is kept, the folder is remembered for this file (reopening it
asks nothing), the pictures load at once and "Showing pictures from
<folder>" confirms it.

**Insert image** (strip key or ⋮ → Insert image…, Code and Preview):
1. The system picker for one picture opens in the document's folder.
2. A picture inside the document's folder tree is written as
   `![name](relative/path.png)` at the caret, replacing any selection:
   `../` where it climbs, spaces and brackets percent-encoded
   (`my%20pic.png`), other characters as typed. The alt text is the file
   name without its extension, selected, so typing replaces it. One undo
   step takes it away. In Preview it goes at the page's caret.
3. A picture outside it (or any picture when the app has no folder yet)
   asks "Copy this picture next to the file?" with **Copy into this
   file's folder** and Cancel. Copy puts it in `images/` beside the
   document (created if needed), as `name-2.png` when the name is taken,
   and links the copy. If the app cannot write the folder yet, the Allow
   access picker comes first, then the copy. A raw `content://` link is
   never written: it would stop working.
4. A draft copies into the drafts folder's own `images/`. **Save as** of
   a draft with pictures does not move them: "Saved. Its pictures stayed
   with the draft: insert them again here."
5. Every step belongs to the document Insert image began in. If another
   file opens (or the same one is opened afresh) before the picture is
   linked, nothing is written into either: the copy prompt is taken away
   and "Insert image stopped: another file opened" says so. A copy that
   had already been made is named: "…The picture was copied to
   images/cat.png beside the file you left." A folder chosen in the Allow
   access picker is kept for the file it was asked for.

## Mermaid

Long-press a rendered diagram to get a sheet with:
- View full screen
- Save as PNG (1× / 2× / 3×)
- Save as SVG
- Copy image
- Share
- Show source in Code

## Export images (⋮ → Export images…)

This exports every rendered image in the file in one go: Mermaid
diagrams, maths blocks if you want them, and embedded pictures.
- **Include:** thumbnails, all selected by default, with Select none.
- **Diagrams as:** PNG / SVG / Both, plus a PNG scale of 1× / 2× / 3×.
  Pictures keep their original format and size.
- **Save as:**
  - **One ZIP file** (`<doc>-images.zip`), saved wherever you choose.
  - **Separate files** in `Downloads/SS Markdown/<doc>/`, numbered in
    document order: `01-save-flow.png`, `02-architecture.png`, …
- **Share instead** hands the files to the system share sheet.
- In folder mode, the folder's ⋮ offers the same export for every file
  in the folder.

## PDF

⋮ → Export PDF renders through the A4 print stylesheet and opens the
system print dialog, so "Save as PDF" and real printers both work.

- It works from every view. Split and Preview print at once; Code
  switches to Preview first, because the page renders only while it is
  drawn, and prints as soon as it is.
- It prints the file it was asked for and nothing else. Opening another
  file before the dialog appears stops it, with the message "Export
  stopped: another file opened. Nothing was saved." It never opens later
  for a different file.
- If the Preview cannot appear within 3 s, or does not finish rendering
  within a minute, the message says so and how to try again: "Couldn't
  prepare the PDF. Open Preview, then ⋮ → Export PDF again." It is never
  silent.
- Export images and the Mermaid sheet's Save / Share / Copy follow the same
  rules: they wait for the Preview to be drawn, and another file opening
  part-way stops them with the same message instead of saving that file's
  diagrams under this one's name.

**Print typography is fixed in physical units**, and set like a word
processor's A4 page, not a phone's screen: about 16 words a line and 45
lines a page of plain text at 11 pt, as Word sets it. Screen zoom, pinch,
the Preview's text size and the theme's on-screen base size never change
it (Andrew, 0.2.1: the screen's measure and spacing gave about 12 words a
line and 25-30 lines a page, with a quarter of the width empty).

| Element | Size | Notes |
|---|---|---|
| Page | A4 (210 × 297 mm); US Letter when the locale is en-US/CA | Chosen in the system dialog |
| Margins | 20 mm all round; 15 mm bottom with the page number | Text column 170 mm; prose runs the full column (no reading-width cap in print) |
| Body | **11 pt**, line height 1.35; 6 pt after each paragraph | The theme's font; serif themes stay serif |
| H1 / H2 / H3 / H4 | 18 / 14 / 12 / 11 pt bold, 12 pt above, 4 pt below | H2 has a thin (0.5 pt) rule under it; the first heading on the page has no space above. Keep with next; no orphan headings at the foot of a page |
| Lists | 18 pt indent, 1 pt between items | |
| Code | 9.5 pt, light grey background, line height 1.3 | Wraps (no clipped lines) |
| Tables | 10 pt, 2 pt × 5 pt cell padding | Rows never split across pages; wide tables wrap |
| Quotes and alerts | Body size, 4 pt × 8 pt padding | |
| Footnotes and captions | 9 pt | |
| Images and diagrams | At most the column width | |
| Page number | 8 pt, centred in the footer | |

- **The paper is white**, whatever the app's theme. WebView paints the
  page margins in its own background colour, so the app paints it white
  for the print job and puts the theme's colour back afterwards; dark
  themes print with their light variant.
- The margins are set by the app on the print job, not only by the
  stylesheet: Android's WebView ignores `@page` margins and "Save as PDF"
  offers none, so without that the text ran to the edge of the paper. A
  printer that needs wider margins gets its own.
- A heading stays with the block after it; code blocks, quotes, table
  rows, diagrams and pictures are not split across pages when they fit on
  one.
- Diagrams and images scale to the text width and never split across
  pages: one taller than a page's text (240 mm, Letter's, so A4 fits too)
  scales down to fit a page. Nothing prints in the 15 mm at the foot but
  the page number.
- The **A4 page mode** in Preview uses the same stylesheet at true scale.
  On a phone that makes body text small, by design ("true scale, pinch
  to read"); pinch zooms the page, not the type.
- A Playwright test prints the corpus to PDF and checks:
  - the body text measures 11 pt, with the sizes and spacing above;
  - the margins are 20 mm and prose runs the full column;
  - plain paragraphs fill at least 40 lines a page;
  - no table row or diagram is split across a page;
  - the A4 page mode measures the same as print.

## Themes

Built-in themes, each with a light and a dark variant:
- **Clean** (default): a GitHub-like sans, 1.6 line height.
- **Reader**: serif (Literata), sepia, 15 px (17 px held only 41 characters a line on a 390 px phone).
- **Compact**: dense, for technical docs.
- **Contrast**: AAA, for accessibility.

Custom themes are JSON with this shape:

```json
{
  "name": "Studio Serif",
  "base": "reader",
  "fonts": { "body": "Lora", "heading": "Lora", "code": "Fira Code" },
  "size": { "base": 16, "headingScale": 1.25, "lineHeight": 1.65 },
  "light": { "bg": "#FBF7EF", "text": "#2B2620", "heading": "#7A2E1B", "link": "#9B3B22", "codeBg": "#F1EADC" },
  "dark":  { "bg": "#1E1B17", "text": "#E9E1D3", "heading": "#F0A58C", "link": "#F2B49E", "codeBg": "#2A2620" },
  "css": ""
}
```

Each palette may also set the nine code colours (`codeComment`,
`codeKeyword`, `codeString`, `codeNumber`, `codeTitle`, `codeAttr`,
`codeBuiltIn`, `codeMeta`, `codeDeletion`); left out, they come from the
base theme. The Colours tab lists them under "Code colours". The
built-in values are exactly Preview's highlight.js palette; a custom
theme's own code colours reach the Code view now and Preview in a later
change (its stylesheet still has the built-in palette fixed).

These are edited through pickers (Type / Colours / Spacing / JSON tabs)
and exported or imported through the system file picker. Fonts are Google
Fonts family names, downloaded on first use and cached.

**Where each font lives** (decided after review 2, UX2-12 and UX2-17):

- **App chrome** (bars, sheets, menus, settings): Inter, bundled. Not a
  setting.
- **Code pane**: JetBrains Mono, bundled. Not a setting: one monospace
  face keeps the cell grid, caret maths and line wrap identical in every
  theme.
- **Code inside Preview** (code spans and fenced blocks): the theme's
  `code` font. It is part of each theme, edited on the theme's Type tab
  ("Code font (inside preview)").

Settings → Text has no font pickers of its own. Its "Code font" and
"Preview font" rows say where the choice lives: "Part of each theme:
⋮ → View → Edit theme…". This replaces the earlier plan of a separate
code-font setting.

## Settings

- Opening: when a file opens, show Code / Preview / Last used. On launch,
  reopen the last file or start a new scratch doc. Returning to the app
  after Android ended it in the background is not a launch: it comes
  back to the file you were in either way.
- Text: default zoom for each pane (− / + steppers, as on the View
  sheet), and **Show line numbers** (a switch, on by default; "In the Code
  view."). Off, the Code view draws no number gutter: the text column
  starts the usual small inset (8 dp) from the left edge and widens, so
  wrapped lines break again. It applies to the open file at once, with no
  reopen; the caret's row stays where it is on screen (as near as the new
  wrapping allows), or, with the caret off screen, the top line stays put,
  so Preview's anchor does not move. The setting applies to Split's Code
  pane too; the Navigator and Preview are unaffected. The numbers are
  clipped to the Code view: a row half scrolled off the top never draws
  its number over the top bar. The code font and preview font rows point to
  the theme, where both are set (see "Where each font lives" under Themes).
  Formatting bar: **One row** (default) | **Two rows**, "Two rows shows
  more keys at once. Landscape always uses one row." A set-once
  preference, so it is not on the View sheet (see "Bottom bar").
  When typing in Split: **Show only the pane you're editing** (default) |
  **Keep both panes**, "The other pane comes back when the keyboard
  closes. On wide screens both panes stay." (see "Split while typing").
- Saving: write to the file "When I tap Save" (default) or "Autosave"
  (see `robustness.md` §3a), version history (on, storage cap, clear).
- Split view: scroll panes together.
- Preview: "Tap to edit" (on by default; off = Preview is read-only;
  tapping a link still shows the *Open · Copy link* chip).
- Sharing: "Show recent files in the share sheet" (on by default). Your
  recent file names appear as Direct Share targets in Android's share
  sheet and in the launcher's long-press menu; off removes them at once
  (see "Share into SS Markdown").
- Themes: manage custom themes.

## Version history (⋮ → Version history)

- A timeline grouped by day. Each entry shows its time and a label:
  Opened, Saved, Autosaved, Checkpoint (unsaved work), Before restore,
  or Pinned with a name. A line-change summary ("+12 −3 lines") sits
  under each label.
- Tapping an entry expands it into a diff snippet with **Compare with
  now** and **Restore** buttons. Long-press an entry to pin and name it.
- Older entries collapse ("Hourly · 6 versions").
- The header shows the version count and the storage used.
- Restoring never overwrites. See `robustness.md` §3 for the storage
  design: keyframes plus diffs, thinning, and caps.

## Small requirements from research

Full evidence is in `research-findings.md`.

- Opening a `.md` from a file manager, email or share sheet edits it
  **in place**. If the source can't be written to, a **Read-only** badge
  sits in the status line with "Save as…".
- Tapping a task checkbox in Preview toggles it, changing exactly one
  character.
- Each file restores its own cursor, selection and scroll on reopen,
  and keeps its own undo stack, which includes Preview edits.
- No smart quotes inside code. The copy button on a code block copies
  the exact source.
- Relative image paths (`../img/x.png`) resolve within the granted
  folder, in folder mode and for a single file once its folder is
  allowed (see "Pictures").
- The theme editor warns when a custom theme's contrast is below AA.
- The formatting strip is one row (two if Settings → Formatting bar
  asks and there is room) and always sits above the keyboard,
  edge-to-edge-safe on Android 15+.

## File changed outside the app

See `robustness.md` §2a.
- With no unsaved edits, the file refreshes silently and a "Changed
  outside SS Markdown" entry appears in History.
- With unsaved edits, a sheet shows the file as it is now and offers:
  - **Merge my edits into the new file** (recommended), with a summary
    such as "3 merge cleanly, 1 overlaps".
  - **Compare**: a single scrolling diff, as in History, with a
    "Theirs / Mine" toggle. (A side-by-side layout for tablets is not
    built yet, so the button does not promise one.)
  - **Keep the file as it is** (your edits stay in History).
  - **Use my version…**, behind a confirmation.
- Nothing is written until you choose and then save.
