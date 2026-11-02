# Changelog

All notable changes to SS Markdown are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, any
release may change behaviour.

## [Unreleased]

### Added

- Code / Split / Preview with position kept in sync, and pinch zoom that
  reflows text.
- A virtualised code editor on a piece-tree text model: big files type, scroll
  and open like small ones.
- Preview with GitHub-flavoured Markdown, footnotes, alerts, front matter,
  KaTeX and Mermaid; tap-to-edit and a table editor; diagram viewer and image
  export; PDF export.
- Never-lose-work: a checksummed recovery journal, verified saves with a
  backup, and version history with compare, restore, pinning and export.
- Conflict handling with a three-way merge when another app changes an open
  file.
- Navigator (outline, quick switcher, drafts), folder mode with Save all,
  share-in, find and replace.
- Light and dark themes, built-in and custom Preview themes, bundled fonts and
  Google Fonts downloads.
- The Open-source licences screen shows every licence text in full.
- Direct Share: up to four recent files appear in Android's share sheet (and
  the launcher's long-press menu). Tapping one opens the share sheet with that
  file chosen; Append still confirms. On by default; Settings → Sharing turns
  it off and removes the file names (ADR 0017).
- Undo and Redo survive the app being killed in the background: the
  recovery journal keeps the newest 8 MB of undo steps while a document is
  open, across saves (journal format 2; ADR 0016).

### Fixed

- After recovering unsaved edits, one Undo no longer reverts every
  recovered edit at once; it takes back one step, as before the crash.

### Build

- The hand-over APK is a non-debuggable, fully optimised R8 build signed with
  the debug key: 6.15 MB, down from 15.12 MB. `mapping.txt` is archived with
  each APK.
- CI runs the Preview bundle's tests (typecheck, lint, Vitest, Playwright),
  checks the committed bundle is fresh and within budget, and gates core
  coverage at 90%.

[Unreleased]: https://github.com/scorchsoft/ss-markdown/commits/main
