# ADR 0011: The recovery journal records every op and is written by one thread

- **Status:** Accepted
- **Date:** 2026-11-02 (recording decisions made in iterations 1–3 and the data-safety reviews)
- **Deciders:** Scorchsoft

## Context

Rubric #1 asks that a `kill -9` at any moment loses at most the last ~1 s
of typing, on any file size. Saving the user's file on every keystroke is
too slow on a Storage Access Framework provider and risks the file itself;
`onSaveInstanceState` cannot hold a document (the Bundle's ~1 MB limit
crashes the app). The first build journalled from the main thread and,
while a save finished, from an I/O thread too: an `fsync` stalled typing
and two threads raced on a single-threaded writer (PERF-6, PERF-7). The
data-safety review then found keystrokes lost between disk hops of save,
reload and merge (DS-1 to DS-14).

## Options

1. **Autosave the user's file often.** Simple, but slow on providers,
   rewrites a file the user may not want changed, and is still lossy.
2. **Snapshot the whole buffer to app storage periodically.** Cost grows
   with the file; a 20 MB file cannot be snapshotted per second.
3. **An append-only operation journal in app storage**, framed like
   LevelDB's log, with group-commit `fsync`.

And for threading: journal I/O on the main thread, on any I/O thread, or
on **one dedicated thread fed in main-thread order**.

## Decision

Option 3, with one journal thread:

- `TextDocument` tells an `EditObserver` about every op **before** it is
  applied; `OpenDocument` journals it from there, so no caller can forget
  to report an edit, and a keystroke costs a few bytes on any line length.
- Records are length + CRC32C + sequence + op; a torn tail is truncated;
  the header names the base file's SHA-256, so a journal never replays
  onto the wrong file (`core/history/FORMAT.md`).
- `JournalLink` only queues work, in the order the main thread saw edits,
  saves and resolutions. `DedicatedJournalThread` (`JournalExecutor.kt`)
  runs the queue and the group-commit timer and is the only thread that
  touches a writer. `fsync` runs after ~1 s idle, at least once a second
  while typing, on pause (the main thread waits at most 300 ms) and
  before risky steps.
- No decision spans a suspension: flows re-read the buffer after their
  last disk wait. Only the run that wrote a journal may delete it; one it
  cannot apply is held, then quarantined, never deleted by the app.

## Consequences

- Loss is bounded by the group commit: `RandomKillTest` measured at most
  1000 ms over 1000 seeded kills; `KillLoopTest` repeats it 50 times on
  the emulator.
- Typing never waits for the disk; the journal thread is one more thing
  to drain on pause and to reason about in tests (`InterleavingDispatcher`
  makes every hop explicit).
- The journal is compacted to a snapshot once its ops outgrow the text,
  so replay stays fast.
- The full rules are `docs/robustness.md` §1; the races they close are
  rows in `docs/edge-cases.md`.
