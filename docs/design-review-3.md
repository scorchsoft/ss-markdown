# Design review 3: triage

**What was reviewed.** A fresh reviewer agent on 2026-11-01. It was told
not to open reviews 1–2, and it read 28 boards.

**Score: 3.48** (review 2 was 3.52). Accessibility scored 2.

## Why the score has stopped moving

Three fresh reviewers have now scored 3.13 → 3.52 → 3.48. Each one
starts from zero, so each finds a new layer of polish. Two criteria can't
reach 4 on paper at all:
- **#4 Speed** and **#5 Rendering fidelity** are "unproven" until there's
  a build and a test corpus.
- **#11 Craft** is about code, which doesn't exist yet.

Together they hold 6 of the 23 weight points at about 3. **An average of
4.0 is a gate for shipped software, and a design can't honestly reach
it.** See the proposal at the end.

## Findings

| # | Finding | Decision |
|---|---|---|
| 1 (critical) | Turning a whole block back into Markdown still rewrites untouched bytes (`_x_`→`*x*`, bullets, breaks, escapes) | **Fixed (spec).** An inline source map turns text edits into minimal source edits; formatting actions use the document's own detected style and re-serialise only the touched node. Constructs that can't round-trip (HTML, footnote/reference definitions, front matter, maths, diagrams) are non-editable in Preview, with "Edit in Code". Edits apply as they happen (debounced ~150 ms); robustness §5a is reconciled. |
| 2 | Unsaved state hidden by the find bar, the Navigator, and at 200% | **Fixed.** The find bar slides in under the header, the Navigator opens below the header, and the dot is drawn at 200% (boards updated). |
| 3 | Actions with no visible twin | **Fixed.** ⋯ on outline rows, history entries, file and draft rows; Autosave toggle drawn at the top of History. |
| 4 | Targets below tokens | **Fixed.** Drawer rows 48, landscape segments 44, block picker 40, Prev/Next cell 48, table toolbar 48, Clear 48, code Copy 48. The divider gets a 48 dp touch area plus TalkBack actions; checkboxes get a 48 dp hit area. |
| 5 | Zoom chip doesn't fit the bottom bar; ambiguous in Split | **Fixed.** The chip floats in its pane's top-right corner (landscape board updated). |
| 6 | Hidden strip keys | **Fixed (spec).** The last visible key is cut at about half, with an edge fade; order defined. |
| 7 | Stray taps while reading | **Fixed.** 500 ms post-scroll guard plus a Done key while editing in Preview (RichEdit board). |
| 8 | Required features not mocked | **Fixed.** New boards: A4 page mode, Google Font picker (on-phone vs downloadable, size, progress, offline note, .ttf import), Compare on phone, Navigator Files with Drafts; file-deleted state added to MoreStates. Theme editor save model: changes apply live to the preview, Back keeps them (themes are files with their own history), "Use this theme" switches to it. |
| 9 | Breadcrumb contradicts spec | **Fixed.** Phone shows the current section only (board updated). |
| 10 | Colour tokens drift | **Fixed.** Dot uses the red token; diff uses grey and blue; highlight and code-palette tokens added. |
| 11 | Status copy and clean-tick differences | **Fixed.** "Unsaved" everywhere; the clean tick is muted ink and is the Save button. |
| 12 | Copy jumps between states | **Fixed (spec).** The Save slot reserves pill width. |
| 13 | 11 px is off the type scale | **Fixed.** 11 sp added for captions (line numbers, badges). Muted `.txt` explained. |
| 14 | Share-in button label | **Fixed (spec).** "Append" or "Create note". |
| 15 | Mermaid sheet vs spec | **Fixed.** Scale comes from Export images (2× default). |
| 16 | `EditText` vs custom view; a11y for the custom view and contenteditable | **Fixed.** Robustness says `MarkdownCodeView`; accessibility approach specified. |
| 17 | Home/Back/Recents, conflict-sheet dismissal, Replace toggle | **Fixed (spec).** |

## Proposal for the design gate

The 4.0 ship bar stays for **release candidates**. For **design
sign-off**, the proposed gate is:
1. **No open Critical or Major findings** from the latest independent
   review.
2. **Every job J1–J10 passes on paper.**
3. **No criterion below 3,** excluding #4, #5 and #11, which can only be
   proven in code.
4. **Andrew's sign-off.**

Status against that gate: every Critical and Major finding from
review 3 is addressed above. A fourth review would confirm it.
