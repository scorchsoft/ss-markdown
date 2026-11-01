# Design review 2: triage

**What was reviewed.** A fresh, unanchored reviewer agent on 2026-11-01.
It was told not to open review 1. It rendered 28 boards and computed
contrast.

**Score.** **3.52** (up from 3.13), with no criterion below 3. The
reviewer's view: fixing issues 1–3 and 6–9 should reach 4.0.

| # | Finding | Decision |
|---|---|---|
| 1 | Double-tap jump vs tap-to-edit (~300 ms delay, select-word clash); two link rules | **Fixed.** No double-tap on Preview text; "jump to source" is dropped, because switching to Code already lands on the same paragraph. One link rule: tap shows the *Open · Edit link* chip. |
| 2 | J10 spec path (selection toolbar → overflow, 3–4 taps) vs mockup (direct menu) | **Fixed.** Long-press on a heading opens the section menu directly, with "Select text instead" as the last row. Mockup and spec agree. |
| 3 | Folder switching would prompt on every file | **Fixed.** The prompt appears only when a dirty buffer would leave memory. Switching inside a folder never prompts. Predictive back shows the prompt while a dirty buffer is on screen (robustness §6). |
| 4 | Five looks for Save | **Fixed.** One state table in the ux-spec covering clean, unsaved, saving, untitled draft, read-only with edits, failed, and autosave paused. An untitled draft is an *outline* pill, because drafts are already safe. |
| 5 | Undocumented blue and amber; red table handle | **Fixed.** Semantic tokens are recorded in the quality bar: needs-you red, info blue, caution amber, ink. The table handle is ink. |
| 6 | J6 4–5 taps | **Fixed.** "Export images…" is top-level in ⋮ again (PDF too). Separate files in Downloads is the default (no picker), and the ZIP folder is remembered. J6 = 3 taps. |
| 7 | No system-bar insets; landscape too heavy; IME extract mode | **Fixed + Specified.** An insets section in the ux-spec. Landscape merges header and mode switch into **one 48 dp bar** (board updated). `IME_FLAG_NO_EXTRACT_UI` is set. The mockups leave system bars out by canvas convention, and the budgets assume them. |
| 8 | Undo off-screen in the strip | **Fixed.** Undo is pinned next to `{ }` at the right end (Keyboard and LandscapeIME boards). |
| 9 | Targets below tokens; rubric and quality bar disagree | **Fixed.** Main segments 44 dp, `{ }` 48×48, table toolbar 48 dp with 44 dp buttons, accessible column handles (with ⋯ as the twin), Viewer Fit 40 dp, Select none / Reset 48 dp. The rubric now points at the token table. |
| 10 | Four controls for preview size | **Fixed.** The theme's base size defines 100%. Settings default is a percentage of it. The Display stepper is labelled "this file". |
| 11 | Spec contradictions (symbols flip-back, share prefix, fuzzy vs word-start, old banner text, autosave toggle location) | **Fixed.** All five reconciled. |
| 12 | J1 untrue for Gmail; first-open view undefined | **Fixed.** J1 is reworded: Gmail's read-only copies are an expected +1 tap (Save as…), shown honestly. On first open, files from other apps open in Preview. |
| 13 | Tables re-padded on write-back | **Fixed.** Existing style is preserved; only padded tables are re-padded, and only changed rows, using display width (CJK and emoji = 2). |
| Minor | Menu separators | **Fixed** (groups start with their separator). |
| Minor | Bottom bar icons inconsistent across boards | Accepted as mockup shorthand. |
| Minor | Breadcrumb truncation | **Fixed.** Below about 360 dp only the current section shows. |
| Minor | Gutter contrast 4.03 | **Fixed.** Gutter is `#6E6E73` on every board. |
| Minor | Gesture-only features | **Fixed.** Twins: Copy as rich text in Share; Autosave this file at the top of Version history; double-tap removed. Ctrl+/ help is specified. |
| Minor | Missing flows (rename/delete, old drafts, declined file, Big-file sheet, preview restarted, no results, drawer marker, licences) | **Specified** in the ux-spec; the drawer "Changed" marker is mocked. |
| Minor | Theme import/export unlabelled | **Fixed.** Labelled "Import" / "Export". |
| Minor | Conflict wording varies | **Fixed.** "Changed in another app", "the file's version", "my version", "Keep both" everywhere. |
| Minor | rubric.md table malformed | **Fixed.** Row 11 is joined to the table, and the scorecard has 11 columns plus both review scores. |
