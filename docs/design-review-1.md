# Design review 1: triage

**What was reviewed.** An independent, unanchored reviewer agent reviewed
the iteration-0 docs and all 22 boards on 2026-11-01. It worked only
from the artifacts, without access to my reasoning. It rendered the
boards, computed contrast from the hex values, and walked jobs J1–J10.

**Result.** A design-stage weighted score of **3.13** against a ship bar
of 4.0.

Each finding below is marked **Fixed** (in the mockups and spec),
**Specified** (behaviour now written down, mockup to follow if needed),
**Deferred** (moved later in the roadmap), or **Rejected** (with the
reason). Board names refer to the Design canvas.

## Critical

| # | Finding | Decision |
|---|---|---|
| 1 | The recovery state had no design | **Fixed.** New `Recovery` board: a non-modal banner, with the text already restored and editable. Got it / Compare / Discard edits. Discard is undoable (a snackbar plus a History checkpoint). |
| 2 | Two conflict UIs contradicted each other ("Keep mine" overwrote in one tap) | **Fixed.** There is one surface, the sheet. The in-session banner only says "changed in another app · nothing is overwritten · **Review**", which opens the same sheet. One vocabulary: *Merge my edits / Compare / Keep the file as it is / Use my version… (confirmed)*. |
| 3 | The conflict sheet hid the unsaved state | **Fixed.** The `ChangedOutside` header keeps the red dot plus "Your unsaved edits are kept". |
| 4 | An edge swipe would conflict with system back | **Fixed.** The right-edge swipe is removed. The Navigator opens with a tap on the title or Ctrl+K; the drawer opens with ≡. |

## Major

| # | Finding | Decision |
|---|---|---|
| 5 | Red used as decoration | **Fixed** across all boards. Syntax markers, checkboxes, toggles, sliders, the caret, the table-cell focus and the history pin are now ink. Selection is system blue. Red is reserved for unsaved (dot, Save pill, Save all), errors and the app logo. |
| 6 | Targets under 48 dp | **Fixed.** Header actions are 48 dp; the Save pill is 40 dp tall with a 48 dp hit area; segments are 44 dp; strip and symbol keys are 48×48 (the strip scrolls, with fewer keys visible); outline rows are 44–48 dp; steppers 48 dp; tablet Save all 40 dp; find option chips 40 dp. Tokens are recorded in `quality-bar.md`. |
| 7 | Fixed widths at 200% font | **Fixed + Specified.** A `MoreStates` row shows the 2.0× header. Above 1.5× the switcher goes icon-only (labels kept for TalkBack), Copy moves into ⋮, and Save never moves. Segments use flexible widths. |
| 8 | Tap-to-edit hurts reading | **Specified, default kept** (Andrew's explicit decision). Gesture precedence is now defined (ux-spec "Gestures in Preview"): a tap that stops a fling never edits; a tap on a link shows a small "Open · Edit link" chip; long-press uses native selection, and a heading adds *Section…* to the selection toolbar, so there is no clash. "Tap to edit" can be turned off. |
| 9 | ⋮ was missing Version history, per-file autosave and Find & replace | **Fixed.** ⋮ is now New · Open file · Open folder · Save as │ Find & replace · Version history │ Export… · Share │ Display · Settings. That's still 10 rows, because PDF and images are merged into Export…. Per-file autosave is on long-press of Save. New `FindReplace` board. |
| 10 | The `{ }` key was inconsistent; `<` read as a literal character | **Fixed.** `{ }` is pinned in the Keyboard, Symbols and LandscapeIME boards, and the cursor keys are arrows with shafts. The long-press lock is cut: the page simply stays while you keep tapping symbols and flips back on the next letter. |
| 11 | Share-in "toast with Open" can't be built | **Fixed.** The confirmation shows inside the sheet ("Added to … · Open") for about 1.5 s, then the sheet finishes. Cancel and "Another file…" rows added. |
| 12 | Many states had no design | **Fixed:** first Save (name from heading), save failed, leaving with unsaved edits plus predictive back, Save all with a skip, autosave header, read-only with edits, 200% font, font-download failure, theme import error, contrast warning (`MoreStates`); merge overlap (`MergeResolve`); landscape with the IME (`LandscapeIME`). **Specified only:** Compare side by side on a phone, the tablet Navigator popover, the keyboard-shortcut help sheet, Split with the IME in portrait, and the maths toggle in Export (`ux-spec.md`). |
| 13 | Contrast: dark syntax red 3.40, placeholder 3.43 | **Fixed.** Syntax red is gone (#5); the placeholder is now `#6E6E73` (5.0:1). The gutter is raised to `#6E6E73` in the spec too. |
| 14 | Save glyph = download arrow, reused for import, save image and Save | **Fixed.** The Save pill is text only. The clean state is a check-in-circle labelled "All saved". Import and export in the theme editor get file-in / file-out glyphs. The download arrow now means only "save image". |

## Minor

| # | Finding | Decision |
|---|---|---|
| 15 | Terminology drift | **Fixed:** "n unsaved files" everywhere. "Lean mode" is renamed **"Big-file mode"**. "Draft" is used for untitled docs and "recovered" for journal restores. A glossary is added to the ux-spec. |
| 16 | Three text-size controls | **Specified.** Pinch and the Display stepper both set *this file's* size per pane. Settings → Default zoom sets the starting size for new files. The zoom chip resets to that default. The copy now says "This file" / "New files". |
| 17 | Navigator issues | **Fixed or specified.** No handle on the top sheet. The search field autofocuses with the IME up and the outline stays visible above it, so J2 is 3 taps. Matching is word-start and contiguous only (the loose match is gone). Dots have TalkBack text. The Ctrl K hint shows only with a hardware keyboard. |
| 18 | Mermaid PNG row had two targets | **Fixed.** One row, "Save as PNG · 2×"; the scale is remembered from Export. |
| 19 | Landscape segments drop icons; some boards show partial headers | **Accepted as mockup shorthand.** The real header and segments are one component. |
| 20 | "Keep recovery copies" toggle | **Fixed: removed.** The safety net isn't optional. |

## Cut or defer

| Suggestion | Decision |
|---|---|
| Drop Google Fonts / `INTERNET` | **Rejected.** Andrew asked for any Google Font. Mitigations: 8 bundled families are the default; a download happens only when you pick a non-bundled font; offline failure is designed; .ttf import is also allowed. |
| Defer the visual theme editor and Advanced CSS | **Deferred within the roadmap.** Built-ins and JSON import ship first; the editor follows in the same iteration, after them. Andrew asked for it, so it isn't cut. |
| Viewer mini-map and filmstrip | Mini-map **deferred**. The filmstrip stays: it's cheap and it's how you browse images. |
| Export: "Both" formats, 3×, folder-wide, Export section | "Both" **cut**. 3× kept (it's one option). Folder-wide **deferred**. Export section kept (it's the same pipeline). |
| A4 page mode | **Rejected.** Andrew asked for it, and it doubles as the print preview. |
| History pin and name | **Kept.** It's small, and it protects the moments people care most about. |
| Share-in Top and prefix options | **Trimmed.** Bottom/Top stays; the prefix is *none* or *date heading* (the bullet option is cut). |
| Copy as rich text in three places | **Trimmed to two:** long-press Copy and the section menu. |

## Re-score

These changes need an independent re-score, which is run as review 2.
**We don't score our own homework.**
