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
- Tapping the title opens Recent files. When a folder is open, a `≡`
  button sits at the left and opens the folder drawer (it is docked on
  tablets).
- **Copy** is always visible and copies the whole document as Markdown.
  Long-pressing it gives "Copy as rich text".
- **Save** is always visible:
  - Clean: a muted outline icon.
  - Unsaved: a red filled "Save" pill.
  - Untitled: "Save as…".
  - A brief toast confirms each save with the destination.
- **⋮** opens: New · Open file · Open folder · Recent · Save as · Find &
  replace · Export PDF · Export images · Share · Display · Settings.

**Bottom bar (64 dp)**
- A segmented Code | Split | Preview control, with icon and label.
- A zoom chip ("120% ↺") sits at the right edge only when the focused
  pane's zoom differs from the default. Tapping it resets the zoom.
- While the keyboard is up, the bar is replaced by the formatting strip:
  H, B, I, list, task, code, link, indent, undo. It scrolls horizontally
  to reveal table, quote, redo and outdent.

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
  Recent are the switchers.

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
- Saving: autosave files and recovery copies.
- Split view: scroll panes together.
- Preview: tap to edit (on by default).
- Themes: manage custom themes.
