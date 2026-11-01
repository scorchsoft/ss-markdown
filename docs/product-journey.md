# SS Markdown — product journey

The vision, the decisions made so far, and the reasons behind them.
Read this before changing behaviour, and change it in line with it.

## Vision

A Markdown editor for Android that does exactly what a plain-text person
needs and nothing else. Most editors in the store either carry bloat
(ads, accounts, sync upsells, feature clutter) or are too simple to do real
work in. SS Markdown is meant to feel **premium, calm and fast**. It must
be good enough for a power user editing long technical files every day,
and obvious to someone who opens a `.md` once a month.

The rule that settles most arguments: **features may be deep, chrome may
not be busy.** A capability that most people never touch is fine. UI for
it that is always on screen is not.

## Principles

1. **Open to an editor.** No home screen, no onboarding carousel. First
   launch is an untitled scratch document with the cursor in it.
2. **Two bars, nothing else.** The header holds the filename and state,
   Copy, Save and ⋮. The bottom bar holds Code / Split / Preview. Every
   other control appears only when it applies: the zoom-reset chip, the
   folder button, Save all, the formatting strip while typing, and the
   conflict banner.
3. **Never lose work, and always know where you stand.** Unsaved state is
   shown three ways — a red dot, a status line and a filled Save pill —
   so no-one has to rely on colour alone. Drafts and recovery copies
   survive crashes.
4. **Never lose your place.** Switching Code ↔ Preview, rotating, or
   resizing the split keeps the same paragraph in view.
5. **Small by default, big when you pinch.** Code text defaults to a
   compact 12.5 sp monospace, because this app is often used for code
   and technical docs. Pinch reflows the text rather than scaling it as
   an image, and a chip resets it.
6. **Offline.** The only network use is downloading a Google Font you
   picked, which is then cached. No analytics, ads or accounts.

## Decisions so far (iteration 0)

| Decision | Choice | Why |
|---|---|---|
| Editor engine | Native `EditText` with highlight spans, applied only to the visible region | Native keyboard, selection handles, autocorrect and accessibility; a WebView editor feels foreign on Android. Limiting spans to the viewport keeps 1 MB files smooth. |
| Preview engine | One reused `WebView`, with markdown-it and plugins, Mermaid, KaTeX, highlight.js and DOMPurify all bundled | Mermaid and KaTeX need a browser engine anyway. Bundling them keeps the app offline. The WebView also gives print-to-PDF for free. |
| Network | `INTERNET` used only for Google Fonts downloads | The user asked for any Google Font; downloaded fonts are cached and work offline afterwards. |
| Custom themes | JSON theme plus a visual editor, with an optional advanced CSS box | Non-developers get pickers; power users get CSS. JSON makes export and import trivial. |
| Identity | "SS Markdown", `com.scorchsoft.ssmarkdown`, minSdk 29 | Android 10+ simplifies storage, dark mode and gesture handling. |
| Accent colour | Scorchsoft red (#B3191B light / #C9302F dark) | Used almost only to mean "unsaved — needs you". That makes the brand colour carry meaning instead of decoration. |
| Saving files | Explicit Save by default (autosave is a setting); untitled docs keep drafts automatically | The user wants to see unsaved state at a glance, which only means something with explicit save. Recovery copies remove the downside. |
| Open mode | Setting: always Code, always Preview, or last used per file (the default) | Asked for directly; "last used" respects how each file is actually used. |
| Wrap modes | Code: Wrap / No wrap. Preview: Fluid / A4 page / No wrap | Fluid suits reading, A4 previews print and PDF, and No wrap suits wide tables and code. |
| Editing in Preview | Tap-to-edit on the rendered view, with a block-scoped round trip: only the edited block is turned back into Markdown and spliced into its source lines | Most editors' "visual" mode is poor, especially for tables, and re-serialising the whole document mangles hand-written Markdown. Scoping edits to one block keeps every untouched line byte-identical. |
| Tables | A structured cell editor (toolbar for + Row / + Column / align, Prev/Next cell), written back as a padded GFM table | Tables are where raw Markdown is most painful on a phone. |
| Image zoom | A separate full-screen viewer for diagrams and images, with its own zoom | Pinch in Preview changes text size, so it can't also zoom a diagram. A dedicated viewer avoids two gestures fighting over one pinch. |
| Export images | One action exports every rendered image as a ZIP or as separate files in Downloads | Saving diagrams one by one is tedious. ZIP vs files is offered because both are reasonable. |
| Line spacing | Line height is a ratio of font size, so zoom never changes the gaps between lines on its own | The spacing in the first round of mockups was a mockup bug, not the design. |
| Robustness | Layered defences: a recovery journal, verified saves with a backup, local version history, byte-faithful encoding and line endings, and Binder-safe copy/share. Size tiers degrade features rather than risk a crash. Full contract in `docs/robustness.md`. | "It lost my work" and "it chokes on big files" are the top complaints about Markdown editors. Andrew made this the first priority. |
| Editor engine | **Virtualised by requirement.** Our own `MarkdownCodeView` on a piece-table text model (`core/text`). It is benchmarked in iteration 1 against `EditText` and Sora Editor (LGPL-2.1), and the result is recorded in ADR 0001. | `EditText` lays out the whole document, which is exactly what makes other editors lag. Andrew wants very big files to feel the same as small ones. Owning the view also keeps the codebase Apache-clean and showcase quality. |
| Memory | A `MemoryGovernor` reads the heap ceiling, available RAM and trim signals; admission control runs before loading; under pressure we shed caches first; we survive WebView renderer death | Out-of-memory crashes are avoidable when you read the numbers Android gives you. |
| Open source | Apache-2.0, built as a showcase of Scorchsoft's craft. Standards are in `docs/engineering.md`. | The code carries the brand's reputation. |
| Preview editing default | Always editable on tap; Settings → Preview → "Tap to edit" turns it off | Andrew: editing shouldn't need an Edit button, but it must be controllable. |
| Symbol keys | A pinned `{ }` key flips the one-row strip to a symbols page (Code view only), flips back when you type a letter or tap `{ }` again | One row always (R8); symbols one tap away instead of two in the keyboard layer; no extra chrome at rest. |
| Navigator | Tapping the title opens one sheet: quick switcher + Outline + Files. Long-press a heading to copy a section. | Covers outline jump, quick switch and copy-section without adding a single visible button. |
| Share-in | Share text into SS Markdown as a New note or "Append to…" a file, through the normal save pipeline | Capturing snippets into a running file is a daily job; doing it safely means no race with the editor. |
| Current section | The title's status line becomes a live breadcrumb ("How we work › Code review") once you scroll past the first heading, and the header hairline doubles as a reading-position line. Back to top is the first row of the Navigator's Outline. | Andrew likes this pattern on andrewleeward.com. Putting it in the existing title keeps it at zero extra chrome. |
| No side-stripe highlights | Selection and callouts use a filled tint plus weight or an icon, never a one-sided coloured border | Andrew: the one-sided border is an AI tell. |
| Where history lives | Private app storage, with caps, 90-day pruning and Export/Import history. An opt-in per-folder `.ssmarkdown/` store is deferred until after 1.0. | Sidecar files are *less* robust on Android (no atomic rename through Storage Access Framework), conflict under Syncthing/Drive, pollute git repos, leak deleted text and fail for read-only files. Andrew raised the question; see `how-it-works.md`. |
| Toolchain | Mirrors Measur3D's Android setup (AGP 8.13, Kotlin 2.3, Compose BOM 2026.06, no wrapper) | Already proven in this cloud container and on CI. |
| APK size | Under 15 MB is a **guideline**. Shrink wherever it causes no regression. The limit may be raised when that is the right strategic choice, recorded here with the reason. | Andrew, 2026-11-01: size must never be bought at the cost of the app's mission, but it shouldn't creep silently either. The policy is in `quality-bar.md` §4. |

## Iteration history

- **Iteration 0** — the UX spec (`docs/ux-spec.md`), the scoring rubric
  (`docs/rubric.md`) and interactive mockups. No app code until the
  mockups are signed off.
