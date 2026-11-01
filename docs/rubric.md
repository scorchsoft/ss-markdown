# Definition of good — scoring rubric

Score every criterion from 0 to 5 at the end of each iteration, with
evidence: a test, a measurement or a screenshot.

**Ship bar:** a weighted average of at least **4.0**, and **no criterion
below 3**.

Scale: 0 missing · 1 broken · 2 works but frustrating · 3 acceptable ·
4 good, no friction · 5 delightful and measurably excellent.

| # | Criterion | Weight | A "5" looks like | How we measure it |
|---|---|---|---|---|
| 1 | **Never lose work** | 3 | Unsaved state is readable from any screen in under 0.5 s. In 1000 random `kill -9`s, the recovered text loses at most the last ~1 s and the file on disk is never left partially written. Saves are verified by reading back. An edit from another app is caught before we overwrite it. Encoding and line endings round-trip byte-for-byte. | The Part 3 tests in `docs/robustness.md` (kill test, save-fault injection, journal property tests, round-trip corpus) |
| 2 | **Calm / unobtrusive** | 3 | At rest only two bars are visible. No control appears until it applies (folder button, zoom chip, Save all, formatting strip). There are no dialogs on the happy path. | Screenshot review against the mockups; count chrome elements per state |
| 3 | **Position continuity** | 3 | Switching Code ↔ Preview lands within ±1 block of the anchor in ≥ 95% of corpus positions. Rotation, folding, resizing or a theme change never loses a character, and keeps the cursor, selection, scroll and undo. Reopening a file returns you to where you were. | Playwright anchor-mapping test; the **rotation matrix** in `robustness.md`, run on every PR |
| 4 | **Speed and large files** | 2 | Cold start to an editable doc < 800 ms. Keystroke to glyph < 16 ms on a 1 MB file. Preview refresh < 150 ms at 50 KB. Frame and keystroke cost don't grow with file size (virtualised): a 20 MB file types and scrolls like a 20 KB one. A 1 MB file opens in < 1 s and 20 MB in < 3 s. A file too big for memory is declined with an explanation **before** loading. No out-of-memory crashes and no ANRs. | Macrobenchmark on a Pixel 6a-class phone using the large-file corpus; JS timing in Playwright; CI monkey runs with StrictMode |
| 5 | **Rendering fidelity** | 2 | The whole test corpus renders correctly: CommonMark, GFM, footnotes, math, Mermaid, alerts, front matter, TOC, emoji. It does so in every built-in theme, light and dark. | Playwright corpus render plus a visual check |
| 6 | **Editing ergonomics** | 2 | The formatting strip and list or task continuation on Enter work. Undo/redo, find & replace and indent/outdent work. Native selection and IME work, including autocorrect and voice. **In Preview**, editing paragraphs, headings, lists and tables feels as direct as a notes app. A Preview edit changes only the edited block's source lines: every other line stays byte-identical. | Unit tests for the edit operations; a round-trip test over the corpus (edit one block, diff the rest); a manual IME pass |
| 7 | **Reading quality** | 2 | Text meets WCAG AA contrast in all themes and the line length is comfortable. The small default is still legible. Pinch reflows instead of scaling. | Contrast checker on the theme tokens; screenshot review |
| 8 | **Adaptive layouts** | 2 | Phone portrait, phone landscape, foldable and tablet each have a deliberate layout, never a stretched phone UI. Split orientation follows the window shape. | Robolectric renders at 360, 600, 840 and 1280 dp |
| 9 | **Power depth** | 1 | Folder mode, Save all, no-wrap, custom themes and fonts, PDF and Mermaid export are each reachable within two taps of ⋮. Any heading or recent file is reachable in ≤ 3 interactions through the Navigator (tap, type 2–3 letters, tap). A snippet can be shared into a file in ≤ 3 taps without leaving the sending app. | A tap-count audit |
| 10 | **Accessibility** | 1 | Every control has a TalkBack label and a ≥ 48 dp touch target. System font scale is respected. No signal relies on colour alone. | An accessibility scanner plus a TalkBack pass |

| 11 | **Craft (open-source code quality)** | 2 | A stranger can clone the repo, build, run the tests and find their way around within 30 minutes. Lint, ktlint and detekt report zero issues. `core/*` has ≥ 90% coverage, with property tests. Dependencies point inwards (CI-enforced). Every significant decision has an ADR. Every row in `edge-cases.md` is `tested`. | CI gates; a review against `docs/engineering.md`; a fresh-clone test by someone new to the code |

Weighted average = Σ(score × weight) / 23.

## Scorecard

| Iteration | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | Weighted |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 (mockups) | — | — | — | — | — | — | — | — | — | — | not scored; design only |
