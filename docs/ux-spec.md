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
  replace · Export PDF · Share · Display · Settings.

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

## Mermaid

Long-press a rendered diagram to get a sheet with:
- Save as PNG (1× / 2× / 3×)
- Save as SVG
- Copy image
- Share
- Show source in Code

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
- Themes: manage custom themes.
