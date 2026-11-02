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
- **Copy** is always visible and copies the whole document as Markdown.
  Long-pressing it gives "Copy as rich text".
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
  Version history │ Export PDF · Export images… · Share │ Display ·
  Settings. Share also offers *Copy as rich text*, the twin for
  long-pressing Copy. In folder mode, while any file is unsaved, **Save
  all (N)** follows Save as (one tap after ⋮, rubric #9); it is absent
  otherwise, and the ≡ panel keeps its own Save all row.
- The menu's **top row is Undo · Redo**, two 48 dp icons side by side
  (as in Chrome's menu). Each is dimmed and read as unavailable when
  there is nothing to undo or redo; a tap applies it and keeps the menu
  open, so it can be tapped again. It works in Code, Split and Preview.
- Opening ⋮ commits the keyboard's word and **hides the keyboard**, so
  the menu has the room; closing it does not bring the keyboard back
  (tapping the text does). The menu never runs off the screen: below
  the pinned Undo · Redo row its rows scroll, with a fade at the edge
  that has more (checked at 360 × 640 dp, on its side and at 2× font).

**Bottom bar (64 dp)**
- A segmented Code | Split | Preview control: labels at normal sizes,
  icons alone above 1.5× font scale and in the landscape bar, where the
  labels stay as TalkBack names. (Icons beside labels were dropped as
  busy chrome; UX-26.) Segments size to their labels and grow with the
  font, and fall back to a vertical radio list when a label cannot fit.
- A **zoom chip** ("120% ↺") floats in the **top-right corner of the
  pane it belongs to**, only when that pane's zoom differs from the
  default. In Split it is unambiguous which pane it resets. It is never
  in the bottom bar, which has no room for it. Tapping it resets the
  zoom.
- While the keyboard is up, the bar is replaced by the formatting strip.
  It is **always one row**.
  - **The strip never puts the keyboard away.** Its keys never take
    focus, the ¶ and table ⋯ menus open above the strip without taking
    focus (the editor keeps its caret and the keyboard while they are
    open), and a command keeps the keyboard's connection: it commits the
    composing word, applies as one edit and reports the new selection.
    In Preview the edited block is re-rendered under the caret with focus
    held by an invisible editable stand-in, so the keyboard never sees
    focus leave text. Only the Link sheet takes the keyboard, for its own
    fields; closing it gives the caret back to the text.
  - It holds ¶, B, I, list, task, code, link, indent and undo, and
    scrolls horizontally to reveal table, quote, redo and outdent.
  - **Enter in a list** starts the next item (same bullet, the next
    number, a fresh `[ ]`). **Enter on an empty item ends the list**: the
    marker goes and the caret sits on a plain line, after a blank line so
    what you type is a paragraph and not part of the item above; a nested
    empty item steps out one level first, and a numbered rest of the list
    counts from 1 again. **Outdent** on a top-level item does the same
    with the item's text. Code and Preview behave identically: Preview
    asks Kotlin to run Code's own Enter on the empty item (`listExit`).
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
    syntax to type. In Preview the strip pins Undo and Done on the
    right (Code pins Undo and `{ }`).
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
6. **Long-press a heading** → the section menu directly (Copy section ·
   Copy as rich text · Select section · Export section…), with **Select
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
  and an outline.
- **Replace all is one undo step** and records a History checkpoint.
- In Preview, matches highlight in the rendered text, and replacing maps
  back to the source.

## Text size: one model

- **Pinch** and the **Display sheet stepper** both set *this file's* size
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

## More decisions from review 3

- **Strip discoverability:** the scrolling part of the strip runs right
  up to the pinned group, which is exactly its 48 dp keys with no padding,
  and its last visible key is cut at about half (35–65%), so it obviously
  scrolls. When 48 dp keys would cut elsewhere, the scrolling keys widen a
  little (at most a fifth on a phone) rather than leave a gap before the
  pinned group. A 12 dp fade sits at the cut, by the pinned group, so the
  cut key reads as half a key, not as a key faded early. Order: ¶ B I
  list task code link indent table quote redo outdent. Undo and `{ }`
  are pinned on the right.
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
  block above or below. Only those lines change. Rows are 48 dp and read
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
- **Formatting strip** — the one-row bar above the keyboard.

## Specified, mockup to follow

- **Tablet Navigator.** A 420 dp popover under the title.
- **Keyboard-shortcut help** (Ctrl+/). A sheet listing every shortcut.
- **Split with the keyboard up in portrait.** The preview pane collapses
  to a 1-line breadcrumb strip until the keyboard closes. Editing gets
  the space.
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
  - Copy section (Markdown)
  - Copy section as rich text
  - Select section
  - Export section…
  A section runs from the heading to the next heading of the same or a
  higher level.

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
- The sheet also offers **Cancel** and an **Another file…** row (the
  system picker) for targets that aren't recent.
- Prefix options are *none* (default) or *a date heading*.

## Display sheet (⋮ → Display)

Appearance (System / Light / Dark) · Preview theme (Clean, Reader,
Compact, Contrast, + custom) · Text size steppers for Code and Preview,
with a reset · Line wrapping: Code (Wrap / No wrap) and Preview (Fluid /
A4 page / No wrap).

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
  link, list, insert table, and undo. Toggles show their state, so Bold
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
  pages.
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
⋮ → Display → Edit theme…". This replaces the earlier plan of a separate
code-font setting.

## Settings

- Opening: when a file opens, show Code / Preview / Last used. On launch,
  reopen the last file or start a new scratch doc.
- Text: default zoom for each pane (− / + steppers, as on the Display
  sheet), and line numbers. The code font and preview font rows point to
  the theme, where both are set (see "Where each font lives" under Themes).
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
- In folder mode, relative image paths (`../img/x.png`) resolve within
  the opened folder.
- The theme editor warns when a custom theme's contrast is below AA.
- The formatting strip is always one row and always sits above the
  keyboard, edge-to-edge-safe on Android 15+.

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
