# ADR 0005: Preview editing syncs with operational transform

- **Status:** Accepted
- **Date:** 2026-11-01
- **Deciders:** Andrew (Scorchsoft)

## Context

Preview is editable on tap (`product-journey.md`, "Editing in Preview"): only
the edited block is turned back into Markdown and spliced into its source
lines. The Code pane, undo and format commands can change the same text while
the page is mid-edit, and messages cross the WebView bridge asynchronously, so
the two sides regularly edit different versions of the text.

## Options

1. **Reject stale page edits and reload the page.** Simple, but typing in
   Preview while anything else changes the text loses keystrokes. This was the
   first implementation.
2. **Lock one side while the other edits.** Visible stalls; undo from Code
   would have to wait for the page.
3. **Operational transform between two parties** (a Jupiter system, Nichols et
   al. 1995): every change gets a revision; an edit made against an older
   revision is transformed over the ones it missed.

## Decision

Option 3. The host owns the revision counter (`PreviewEditSync.kt`); the page
sends edits with `baseRev`, and hears of every revision exactly once, as an
`edit` (a host change) or an `ack` (its own). Edits are non-overlapping
splices transformed as retain / insert / delete components, after ot.js (MIT).
Inserted text always survives, and when both sides insert at one offset **the
host's text goes first**. If rebasing is impossible (an unknown revision,
edits that do not fit) the host resyncs the page from its text.

The two halves are the same algorithm in two languages and must change
together: `web/src/core/ot.ts` + `core/editSync.ts`, and Kotlin's
`editing/TextTransform.kt` + `PreviewEditSync.kt`.

## Consequences

- Concurrent edits converge (TP1) and nothing typed is lost; both halves have
  property tests, and CI runs both (the Kotlin half in `check`, the TypeScript
  half in `preview-web`).
- Two hand-mirrored implementations can drift. Shared golden vectors run by
  both test suites would close that (code-craft review CC-20).
- Hosts must route every change through `PreviewEditor` so the ack and
  revision rules hold (`apps/android/CLAUDE.md`, Preview bundle traps).
