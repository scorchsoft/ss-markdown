# ADR 0006: Version history lives in private app storage

- **Status:** Accepted
- **Date:** 2026-11-01 (recording a decision made in iteration 0)
- **Deciders:** Andrew (Scorchsoft), who raised the question

## Context

The app keeps a recovery journal and local version history for every file
(`robustness.md`). Andrew asked whether history should travel with the
document, as a sidecar file or a hidden folder next to it.

## Options

1. **Sidecar file or folder beside the document** (for example `.ssmarkdown/`).
   Survives uninstall and moves with the folder.
2. **Private app storage**, keyed by document.

## Decision

Private app storage, with caps (100 MB per file and 1 GB in total by default,
adjustable or off), thinning of old intermediate versions, 90-day pruning of
files not opened, pinning, and Export/Import history as a `.zip`. An opt-in
per-folder `.ssmarkdown/` store is deferred until after 1.0
(`product-journey.md`, "Where history lives"; `how-it-works.md`).

## Consequences

- Writes are atomic (write, fsync, rename), which the Storage Access Framework
  often cannot do for a sidecar, so a crash cannot corrupt history itself.
- No sync conflicts under Syncthing or Drive, no files in git repositories, no
  deleted text leaking through a shared folder, and read-only files (email
  attachments) still get history.
- History is per device and is lost on uninstall. Files are always plain
  Markdown the user owns; history is a safety net, not the record of truth.
  Export history covers the cases where it must travel.
- The on-disk formats are specified in `core/history/FORMAT.md` and
  `VERSIONS-FORMAT.md`.
