# ADR 0016: Undo survives process death, through the recovery journal

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** Scorchsoft (product owner's decision)

## Context

Android kills a backgrounded app whenever it needs the memory. The
recovery journal (ADR 0011) already brought every character back, but
the undo stack lived only in memory, so after a kill Undo did nothing at
all, or worse: recovery restored the text with one `replaceAllWith`, so
the first Undo reverted *every* recovered edit at once. Users expect a
relaunched editor to behave like the one they left. The product owner
decided Undo and Redo must work after a kill exactly as before it.

## Options

1. **Persist the undo stack separately** (a second file, rewritten from
   time to time). A second source of truth that can disagree with the
   journal, and a second thing to fsync and repair.
2. **Re-derive steps at replay from the ops and their timestamps**
   (re-run the typing-run rule). Fragile: the rule depends on the clock,
   cursor moves and compound edits the journal never saw, so the steps
   would only be guessed.
3. **Journal the undo stack's own decisions**, in order with the ops,
   and keep the steps as data where the journal restarts (snapshot,
   save). Replay follows the same rules as `UndoStack`.

## Decision

Option 3. Journal format version 2 (`core/history/FORMAT.md`):

- **The stack reports, the journal records.** `UndoStack` tells an
  `UndoListener` when it seals a step (before the op that starts the
  next one, so a typing run costs one marker), and when it undoes or
  redoes a step of n ops (before those ops). `TextDocument` decides
  grouping *before* applying an op, so the order on disk is the order of
  the decisions, and forwards them to `EditObserver`s through new default
  methods (source-compatible). Markers: kind 3 StepEnd, 4 Undo n, 5 Redo
  n. The journal never stores a timestamp for grouping.
- **History as data where the journal restarts.** Record type 4 holds the
  undo and redo steps. Every run starts with it: after the header (a
  fresh run), after the Snapshot (recovery, compaction, a save typed
  through, a resync after a failed write), or alone.
- **Saves keep the history.** A verified save no longer deletes the
  journal while there are undo steps: it writes a *clean* journal (base =
  the saved file, History only). The launch scan tells a clean journal
  apart by its record frames and never offers it for recovery. Its first
  later change is synced at once, so a death right after an edit still
  finds a journal that says "edits began", as a fresh journal's header
  always did.
- **Recovery restores text and steps separately.** The replayed text goes
  into the `TextDocument` as one replace, and `UndoStack.restore` puts the
  steps back as data, replacing the stacks (so that replace is not a
  step). Replaying every op into the piece tree would rebuild the same
  thing slower; the steps are just the ops that lead to the text. Before
  restoring, the newest undo and redo steps are checked against the text;
  steps that do not fit are dropped rather than trusted.
- **Scope.** History persists while the document is open in the session:
  across saves, compaction, recovery, repeated deaths and Save as. It is
  discarded on an outside change (a reload, a resolved conflict, a change
  found at reopen: the old steps would apply to text that is no longer
  there; robustness §2a), when the document is closed or "Don't save" is
  chosen (the journal is deleted, as before), when memory pressure evicts
  a clean document, and for a document the launch does not reopen (its
  clean journal is deleted once the launch settles). A conflicted
  recovery starts with no history.
- **The tail cap.** On disk, the newest steps up to 8 MB
  (`UndoStack.HISTORY_TAIL_MAX_BYTES`, `JournalHistory.TAIL_MAX_BYTES`;
  each op counts 12 bytes plus 2 per char) are kept: undo steps from the
  newest back, then redo steps. In memory the stack keeps up to 32 MB as
  before; after a death, steps beyond the tail are gone. A single step
  larger than the cap is not kept across a death.
- **Version 1 still reads.** A v1 journal replays text only, with no
  history (its ops say nothing reliable about steps). A checked-in v1
  file is replayed by `UndoHistoryJournalTest`.
- **Downgrade is accepted.** A build that only knows v1 reports a v2
  journal as `Unsupported`: the document opens read-only and the journal
  is held untouched until the app is updated (FORMAT.md's existing rule).
  That includes a clean journal, so a downgraded build shows a document
  with no unsaved edits as read-only until it is updated again.

## Consequences

- After a kill, Undo walks back through the steps the user made and Redo
  forward, as before the kill. `RandomKillTest` checks it in every run
  (the recovered steps are the live document's in the recovered state,
  and the walk passes through exactly their texts); `BackgroundKillTest`
  checks it on the emulator after a real SIGKILL.
- The journal is no longer "deleted after each verified save": while a
  document with undo steps is open, a clean journal of up to 8 MB stays in
  app-private storage, holding deleted text. It goes when the document
  closes, and with "Don't save"; Settings → Storage counts it under
  recovery copies. The History on/off setting governs version history,
  not this: like the journal itself, the undo tail is part of crash
  recovery.
- A compaction or save rewrites up to 8 MB of history; the journal thread
  does it, never the main thread. Capturing the steps on the main thread
  copies references only (the ops are immutable).
- Measured on the host (`UndoReplayBenchmarkTest`, a 20 MB document with a
  full 8 MB tail of 1,047 steps): a reopen takes 528 ms from a clean
  journal, 1,232 ms from Snapshot + History and 1,435 ms with the tail as
  ops, against 237 ms for a plain open with no journal. Within the 3 s open
  budget on the host; the phone number is still to be measured (PERF-22,
  quality-bar §4). Replaying each op into the piece tree instead was not
  needed, so not built.
