# UX spec

This spec matches the iteration-0 mockups. Where they disagree, the
mockups agreed with Andrew win, and this file gets updated.

## Chrome

**Top bar (56 dp)**
- Title block: the filename, plus a red dot when unsaved, plus a status
  line underneath:
  - "Unsaved changes"
  - "Saved · 2 min ago"
  - "Draft · kept on this phone"
- Tapping the title opens the **Navigator** (see below). When a folder
  is open, a `≡` button sits at the left and opens the folder drawer (it
  is docked on tablets).
- **Copy** is always visible and copies the whole document as Markdown.
  Long-pressing it gives "Copy as rich text".
- **Save** is always visible:
  - Clean: a muted outline icon.
  - Unsaved: a red filled "Save" pill (text only, 40 dp tall, 48 dp hit
    area).
  - Clean: a check-in-circle labelled "All saved" (tapping it shows "All
    saved").
  - Untitled, or read-only with edits: a red "Save as…" pill.
  - **Long-press Save** opens Save as… · Autosave this file (on/off).
  - A brief toast confirms each save with the destination.
- **⋮** opens: New · Open file · Open folder · Save as │ Find & replace ·
  Version history │ Export… (PDF · Images · Section) · Share │ Display ·
  Settings.

**Bottom bar (64 dp)**
- A segmented Code | Split | Preview control, with icon and label.
- A zoom chip ("120% ↺") sits at the right edge only when the focused
  pane's zoom differs from the default. Tapping it resets the zoom.
- While the keyboard is up, the bar is replaced by the formatting strip.
  It is **always one row**.
  - It holds H, B, I, list, task, code, link, indent and undo, and
    scrolls horizontally to reveal table, quote, redo and outdent.
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
    syntax to type.
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
- On narrow screens the parent crumbs are truncated first; the current
  section never truncates before its parents.
- The header's bottom hairline doubles as a **reading-position line**:
  a neutral grey fill, 2 dp, full width. It is not an accent colour and
  doesn't animate on its own.
- Tapping the title opens the Navigator with the current section
  highlighted. **Back to top** is the first row of the Outline ("↑ Top of
  document"), and Ctrl+Home works on hardware keyboards.
- No third bar and no floating button: the existing title does the job.

## Gestures in Preview (precedence, top wins)

1. A tap that **stops a fling** only stops the scroll; it never edits.
2. Tap on a **diagram, image or maths block** → the viewer.
3. Tap on a **link** → a small chip, *Open · Edit link*. With "Tap to
   edit" off, a tap opens the link directly.
4. Tap on a **task checkbox** → toggles it (one character changes).
5. Tap on **text** → caret and keyboard (if "Tap to edit" is on).
6. **Long-press text** → native selection. When the selection starts in
   a heading, the selection toolbar adds **Section…** (Copy section ·
   Copy as rich text · Select section · Export section). It never
   replaces native selection.
7. **Pinch** → this file's preview text size. **Double-tap a block** →
   jumps to its source line in Code.

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

The find bar temporarily takes the header's place; close it with ✕ or
Back.
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
- **Settings → Default zoom** sets the size new files start at.
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

- **Compare side by side on a phone.** A single scrolling diff (as in
  History) with a "Theirs / Mine" toggle. Side-by-side is tablet only.
- **Tablet Navigator.** A 420 dp popover under the title.
- **Keyboard-shortcut help** (Ctrl+/). A sheet listing every shortcut.
- **Split with the keyboard up in portrait.** The preview pane collapses
  to a 1-line breadcrumb strip until the keyboard closes. Editing gets
  the space.
- **Export maths blocks toggle.** In Export images → Include, there's a
  "Maths blocks" switch, off by default.

## Navigator (tap the title)

One sheet answers "where am I, and where can I go?". It replaces the
separate Recent menu.

- It opens by tapping the title or pressing Ctrl+K / Ctrl+P. There is no
  edge swipe, because gesture navigation owns both edges for Back. On
  tablets it is a 420 dp popover under the title.
- The search field **autofocuses with the keyboard up**. The sheet sits
  above the keyboard, so the outline stays visible under the field. Back
  or a swipe down closes the keyboard and shows the full outline.
- Matching is **word-start and contiguous only** ("sav" matches "Save
  flow" and "savings-plan.md", never "re*s*e*a*rch-o*v*erview"). Results
  are ranked headings, then recent files, then folder files. The Ctrl K
  hint shows only when a hardware keyboard is attached.
- **Search field on top (the quick switcher).** It fuzzy-matches as you
  type across three groups, in this order:
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
  when a folder is open, with unsaved dots.
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
- **Append to…** — recent and pinned files, newest first.
  - Options: Bottom (default) or Top; a prefix of none, `- `, or a
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

## Layouts

| Window | Split direction | Folder UI |
|---|---|---|
| < 600 dp wide (phone portrait) | Top / bottom, draggable divider | Modal drawer |
| ≥ 600 dp wide (phone landscape, small tablet) | Side by side | Modal drawer |
| ≥ 840 dp wide (tablet, unfolded) | Side by side | Docked, collapsible panel |

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
- Double-tapping a preview block opens Code at its source line.
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
- Before writing, the file's `lastModified` and size are compared with
  what we loaded. If they differ, a banner under the header offers
  Compare · Reload · Keep mine.
- Leaving with unsaved changes prompts Save / Discard / Cancel.

## Folder mode

- Opened through the system folder picker; access persists.
- The tree shows `.md`, `.markdown`, `.mdx` and `.txt` files, with
  "show all files" in the folder ⋮ menu.
- Search finds files by name.
- Dirty files show a dot. Collapsed folders show "n unsaved".
- A "n files unsaved · Save all" row exists only while n > 0.
- Several dirty buffers are held in memory, with no tabs. The drawer and
  the Navigator are the switchers.

## Editing in Preview

Preview is not read-only. Tap rendered text and the cursor lands there,
the keyboard opens, and the block under edit gets a faint tint.

- The formatting strip works on rendered text: a block-style picker
  (Heading 1–3, Paragraph, Quote, Code block), then B, I, inline code,
  link, list, insert table, and undo. Toggles show their state, so Bold
  is pressed when the cursor is in bold text.
- **Edits are scoped to one block.** When the cursor leaves a block,
  only that block is turned back into Markdown and spliced into its
  source lines. Every other line of the file stays byte-for-byte as it
  was, so editing in Preview never reformats a hand-written document.
- Links open on long-press while editing (a tap places the cursor).
- Diagrams, maths and images are not text, so tapping them opens the
  viewer instead of a cursor.
- A setting, "Tap to edit in Preview", is on by default; turn it off for
  a read-only Preview.

### Tables

Tapping a cell puts the cursor in it, and a dark table toolbar appears
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

The table is written back as a tidy GFM pipe table: columns padded to
line up in Code view and alignment markers kept. Only the table's own
lines change.

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

## Themes

Built-in themes, each with a light and a dark variant:
- **Clean** (default): a GitHub-like sans, 1.6 line height.
- **Reader**: serif, sepia.
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
Fonts family names, downloaded on first use and cached. The editor
(code) font is a separate setting.

## Settings

- Opening: when a file opens, show Code / Preview / Last used. On launch,
  reopen the last file or start a new scratch doc.
- Text: default zoom for each pane, the code font, the preview font
  override, and line numbers.
- Saving: write to the file "When I tap Save" (default) or "Autosave"
  (see `robustness.md` §3a), version history (on, storage cap, clear).
- Split view: scroll panes together.
- Preview: "Tap to edit" (on by default; off = Preview is read-only,
  and tapping a link opens it directly).
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
  - **Compare side by side.**
  - **Keep the file as it is** (your edits stay in History).
  - **Use my version…**, behind a confirmation.
- Nothing is written until you choose and then save.
